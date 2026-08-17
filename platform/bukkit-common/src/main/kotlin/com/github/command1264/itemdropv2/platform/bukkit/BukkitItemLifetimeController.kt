package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemLifetimeProcessingOutcome
import com.github.command1264.itemdropv2.core.ItemLifetimeRegistrationOutcome
import com.github.command1264.itemdropv2.core.ItemLifetimeService
import com.github.command1264.itemdropv2.core.ItemProcessingWheel
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import java.util.UUID

@Suppress("TooManyFunctions")
public class BukkitItemLifetimeController(
    private val server: Server,
    private val service: ItemLifetimeService,
    private val wheel: ItemProcessingWheel,
    private val ownershipRefresh: ItemOwnershipRefresh,
    private val warningSink: DisplayWarningSink,
    private val taskExecutor: MainThreadTaskExecutor,
    private val carrierNormalization: VirtualItemCarrierNormalization =
        VirtualItemCarrierNormalization { VirtualItemCarrierNormalizationOutcome.Unmanaged },
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    private val loadedChunkCheck: (Item) -> Boolean = ::isItemChunkLoaded,
    private val loadedItemReadiness: (Item) -> Boolean = { true },
) : Listener,
    AutoCloseable {
    private val pendingRegistrations = mutableMapOf<UUID, PendingLifetimeRegistration>()
    private val pendingCarrierNormalizations = mutableMapOf<UUID, Item>()
    private val consecutiveAvailabilityMisses = mutableMapOf<UUID, Int>()
    private val registeredItems = mutableMapOf<UUID, Item>()

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    @Suppress("TooGenericExceptionCaught")
    public fun onItemSpawn(event: ItemSpawnEvent) {
        if (event.isAsynchronous) {
            warningSink.warn("ignored asynchronous ItemSpawnEvent lifetime registration")
            return
        }
        if (!event.entity.isItemDropLifecycleEligible()) return
        beginRegistration(event.entity)
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public fun onPendingLifetimeMerge(event: ItemMergeEvent) {
        if (isPendingInitialization(event.entity.uniqueId) || isPendingInitialization(event.target.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    @Suppress("TooGenericExceptionCaught")
    public fun onItemDespawn(event: ItemDespawnEvent) {
        if (!event.entity.isItemDropLifecycleEligible()) return
        try {
            withTransientTarget(event.entity) {
                when (service.isExpired(event.entity.uniqueId)) {
                    false -> event.isCancelled = true
                    true -> wheel.forget(event.entity.uniqueId)
                    null -> Unit
                }
            }
        } catch (error: RuntimeException) {
            warningSink.warn("item lifetime despawn inspection failed (${error.javaClass.simpleName})")
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public fun onChunkLoad(event: ChunkLoadEvent) {
        val visibleItems = event.chunk.entities.filterIsInstance<Item>()
        visibleItems.forEach(::register)
        scheduleLoadedChunkRetry(
            event.chunk,
            LOADED_CHUNK_ENTITY_VISIBILITY_ATTEMPTS,
            visibleItems.mapTo(mutableSetOf(), Item::getUniqueId),
            requireRecoveryReadiness = false,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onChunkUnload(event: ChunkUnloadEvent) {
        event.chunk.entities
            .filterIsInstance<Item>()
            .forEach {
                pendingCarrierNormalizations.remove(it.uniqueId)
                forget(it.uniqueId)
            }
    }

    public fun registerLoadedItems() {
        server.worlds
            .asSequence()
            .flatMap { it.loadedChunks.asSequence() }
            .forEach { chunk ->
                val visibleItems = chunk.entities.filterIsInstance<Item>()
                visibleItems.filter(::isLoadedItemReady).forEach(::register)
                scheduleLoadedChunkRetry(
                    chunk,
                    LOADED_CHUNK_ENTITY_VISIBILITY_ATTEMPTS,
                    visibleItems.mapTo(mutableSetOf(), Item::getUniqueId),
                    requireRecoveryReadiness = true,
                )
            }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun isLoadedItemReady(item: Item): Boolean =
        try {
            loadedItemReadiness(item)
        } catch (error: RuntimeException) {
            warningSink.warn("loaded item recovery readiness failed (${error.javaClass.simpleName})")
            false
        }

    /** Called once per server tick; only one of the twenty fixed slots is processed. */
    public fun processNextSlot() {
        wheel.advance().forEach(::processSecond)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun processSecond(entityId: UUID) {
        val item = resolveProcessableItem(entityId) ?: return
        try {
            withTransientTarget(item) {
                when (val outcome = service.processSecond(entityId)) {
                    ItemLifetimeProcessingOutcome.Active -> Unit
                    ItemLifetimeProcessingOutcome.OwnershipExpired,
                    ItemLifetimeProcessingOutcome.OwnershipDisplayChanged,
                    ItemLifetimeProcessingOutcome.DisplayTimeChanged,
                    -> ownershipRefresh.refresh(item)
                    ItemLifetimeProcessingOutcome.ItemExpired -> {
                        item.remove()
                        forget(entityId)
                    }
                    is ItemLifetimeProcessingOutcome.Rejected -> {
                        forget(entityId)
                        warningSink.warn("item lifetime processing rejected (${outcome.reason})")
                    }
                    is ItemLifetimeProcessingOutcome.Failed -> {
                        forget(entityId)
                        warningSink.warn("item lifetime processing failed (${outcome.errorType})")
                    }
                }
            }
        } catch (error: RuntimeException) {
            warningSink.warn("item lifetime processing target access failed (${error.javaClass.simpleName})")
        }
    }

    private fun resolveProcessableItem(entityId: UUID): Item? {
        val registered = registeredItems[entityId]
        val item =
            if (registered != null && !registered.isDead && !registered.isValid) {
                resolveServerItem(server, entityId)?.also { registeredItems[entityId] = it } ?: registered
            } else {
                registered ?: resolveServerItem(server, entityId)
            }
        return when {
            item == null -> {
                retainForTransientAvailabilityMiss(entityId)
                null
            }
            item.isDead -> {
                forget(entityId)
                null
            }
            !item.isValid -> {
                retainForTransientAvailabilityMiss(entityId)
                null
            }
            !isProcessable(item) -> {
                forget(entityId)
                null
            }
            else -> {
                consecutiveAvailabilityMisses.remove(entityId)
                item
            }
        }
    }

    private fun isProcessable(item: Item): Boolean {
        if (!loadedChunkCheck(item)) return false
        val stack = item.itemStack
        return stack.type != Material.AIR && stack.amount > 0
    }

    @Suppress("TooGenericExceptionCaught")
    private fun register(
        entityId: UUID,
        attemptsRemaining: Int,
    ) {
        val pending = pendingRegistrations[entityId] ?: return
        val outcome =
            try {
                service.register(entityId, pending.materialName)
            } catch (error: RuntimeException) {
                finishRegistration(entityId)
                warningSink.warn("item lifetime registration failed (${error.javaClass.simpleName})")
                return
            }
        when (outcome) {
            is ItemLifetimeRegistrationOutcome.Registered -> {
                registeredItems[entityId] = pending.item
                wheel.register(entityId)
                val normalizationOutcome =
                    try {
                        normalizeCarrier(pending.item)
                    } catch (error: RuntimeException) {
                        VirtualItemCarrierNormalizationOutcome.Failed(error.javaClass.simpleName)
                    }
                try {
                    handleCarrierNormalizationOutcome(
                        pending.item,
                        normalizationOutcome,
                        CARRIER_NORMALIZATION_ATTEMPTS,
                    )
                } finally {
                    finishRegistration(entityId)
                }
            }
            ItemLifetimeRegistrationOutcome.ItemExpired -> {
                try {
                    pending.item.takeIf(::isAvailable)?.remove()
                } finally {
                    finishRegistration(entityId)
                    forget(entityId)
                }
            }
            is ItemLifetimeRegistrationOutcome.Rejected -> {
                finishRegistration(entityId)
                warningSink.warn("item lifetime registration rejected (${outcome.reason})")
            }
            is ItemLifetimeRegistrationOutcome.Failed ->
                if (outcome.errorType == MISSING_TARGET) {
                    if (attemptsRemaining > 1) {
                        scheduleRegistrationRetry(entityId, attemptsRemaining - 1)
                    } else {
                        finishRegistration(entityId)
                        warnIfRegistrationTargetShouldStillExist(pending)
                    }
                } else {
                    finishRegistration(entityId)
                    warningSink.warn("item lifetime registration failed (${outcome.errorType})")
                }
        }
    }

    private fun normalizeCarrier(item: Item): VirtualItemCarrierNormalizationOutcome = carrierNormalization.normalize(item)

    private fun handleCarrierNormalizationOutcome(
        item: Item,
        outcome: VirtualItemCarrierNormalizationOutcome,
        attemptsRemaining: Int,
    ) {
        when (outcome) {
            is VirtualItemCarrierNormalizationOutcome.Normalized,
            VirtualItemCarrierNormalizationOutcome.Unmanaged,
            -> pendingCarrierNormalizations.remove(item.uniqueId)
            is VirtualItemCarrierNormalizationOutcome.Rejected ->
                if (outcome.reason == STATE_NOT_READY && attemptsRemaining > 1) {
                    scheduleCarrierNormalizationRetry(item, attemptsRemaining - 1)
                } else {
                    pendingCarrierNormalizations.remove(item.uniqueId)
                    warningSink.warn("virtual item carrier normalization rejected (${outcome.reason})")
                }
            is VirtualItemCarrierNormalizationOutcome.Failed ->
                if (outcome.errorType == MISSING_TARGET && attemptsRemaining > 1) {
                    scheduleCarrierNormalizationRetry(item, attemptsRemaining - 1)
                } else {
                    pendingCarrierNormalizations.remove(item.uniqueId)
                    warningSink.warn("virtual item carrier normalization failed (${outcome.errorType})")
                }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleCarrierNormalizationRetry(
        item: Item,
        attemptsRemaining: Int,
    ) {
        pendingCarrierNormalizations[item.uniqueId] = item
        try {
            taskExecutor.execute {
                try {
                    if (pendingCarrierNormalizations[item.uniqueId] !== item) return@execute
                    if (item.isDead) {
                        pendingCarrierNormalizations.remove(item.uniqueId)
                        return@execute
                    }
                    val outcome = withTransientTarget(item) { normalizeCarrier(item) }
                    handleCarrierNormalizationOutcome(item, outcome, attemptsRemaining)
                } catch (error: RuntimeException) {
                    pendingCarrierNormalizations.remove(item.uniqueId)
                    warningSink.warn(
                        "virtual item carrier normalization execution failed (${error.javaClass.simpleName})",
                    )
                }
            }
        } catch (error: RuntimeException) {
            pendingCarrierNormalizations.remove(item.uniqueId)
            warningSink.warn("virtual item carrier normalization scheduling failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleLoadedChunkRetry(
        chunk: org.bukkit.Chunk,
        attemptsRemaining: Int,
        observedEntityIds: MutableSet<UUID>,
        requireRecoveryReadiness: Boolean,
    ) {
        try {
            taskExecutor.execute {
                if (chunk.isLoaded) {
                    chunk.entities
                        .filterIsInstance<Item>()
                        .filter { item ->
                            observedEntityIds.add(item.uniqueId) || !wheel.isRegistered(item.uniqueId)
                        }.filter { item ->
                            !requireRecoveryReadiness || isLoadedItemReady(item)
                        }.forEach(::register)
                    if (attemptsRemaining > 1) {
                        scheduleLoadedChunkRetry(
                            chunk,
                            attemptsRemaining - 1,
                            observedEntityIds,
                            requireRecoveryReadiness,
                        )
                    }
                }
            }
        } catch (error: RuntimeException) {
            warningSink.warn("loaded chunk item retry scheduling failed (${error.javaClass.simpleName})")
        }
    }

    private fun register(item: Item) {
        if (!item.isItemDropLifecycleEligible()) return
        beginRegistration(item)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleRegistrationRetry(
        entityId: UUID,
        attemptsRemaining: Int,
    ) {
        try {
            taskExecutor.execute { register(entityId, attemptsRemaining) }
        } catch (error: RuntimeException) {
            finishRegistration(entityId)
            warningSink.warn("item lifetime registration retry scheduling failed (${error.javaClass.simpleName})")
        }
    }

    override fun close() {
        pendingRegistrations.values.forEach { it.lease.release() }
        pendingRegistrations.clear()
        pendingCarrierNormalizations.clear()
        consecutiveAvailabilityMisses.clear()
        registeredItems.clear()
        wheel.clear()
    }

    private fun retainForTransientAvailabilityMiss(entityId: UUID) {
        val misses = consecutiveAvailabilityMisses.getOrDefault(entityId, 0) + 1
        if (misses >= AVAILABILITY_MISS_ATTEMPTS) {
            forget(entityId)
        } else {
            consecutiveAvailabilityMisses[entityId] = misses
        }
    }

    private fun forget(entityId: UUID) {
        consecutiveAvailabilityMisses.remove(entityId)
        registeredItems.remove(entityId)
        wheel.forget(entityId)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun beginRegistration(item: Item) {
        if (item.uniqueId in pendingRegistrations) return
        try {
            val location = item.location
            pendingRegistrations[item.uniqueId] =
                PendingLifetimeRegistration(
                    item = item,
                    materialName = item.itemStack.type.name,
                    amount = item.itemStack.amount,
                    worldName = item.world.name,
                    blockX = location.blockX,
                    blockY = location.blockY,
                    blockZ = location.blockZ,
                    lease = transientTargetLeaseFactory.acquire(item),
                )
            register(item.uniqueId, attemptsRemaining = REGISTRATION_ATTEMPTS)
        } catch (error: RuntimeException) {
            finishRegistration(item.uniqueId)
            warningSink.warn("item lifetime registration initialization failed (${error.javaClass.simpleName})")
        }
    }

    private fun finishRegistration(entityId: UUID) {
        pendingRegistrations.remove(entityId)?.lease?.release()
    }

    private fun isPendingInitialization(entityId: UUID): Boolean =
        entityId in pendingRegistrations || entityId in pendingCarrierNormalizations

    private inline fun <T> withTransientTarget(
        item: Item,
        action: () -> T,
    ): T {
        val lease = transientTargetLeaseFactory.acquire(item)
        return try {
            action()
        } finally {
            lease.release()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun warnIfRegistrationTargetShouldStillExist(pending: PendingLifetimeRegistration) {
        val shouldExist =
            try {
                isAvailable(pending.item) && loadedChunkCheck(pending.item)
            } catch (error: RuntimeException) {
                warningSink.warn(
                    "LIFETIME-REGISTRATION-TARGET-INSPECTION-FAILED: " +
                        "entity=${pending.item.uniqueId}, error=${error.javaClass.simpleName}",
                )
                return
            }
        if (!shouldExist) return
        warningSink.warn(
            "LIFETIME-REGISTRATION-MISSING-TARGET: registration abandoned after " +
                "$REGISTRATION_ATTEMPTS attempts over ${REGISTRATION_ATTEMPTS - 1} retry ticks; " +
                "item=${pending.materialName} x${pending.amount}, entity=${pending.item.uniqueId}, " +
                "world=${pending.worldName}, position=(${pending.blockX},${pending.blockY},${pending.blockZ}), " +
                "valid=${pending.item.isValid}, dead=${pending.item.isDead}, chunkLoaded=true; " +
                "this item may use vanilla despawn timing",
        )
    }

    private companion object {
        private const val MISSING_TARGET = "MissingTarget"
        private const val STATE_NOT_READY = "StateNotReady"

        // Modern Spigot can delay UUID/chunk entity visibility for several seconds after a burst spawn.
        // Explicit unload, despawn, dead and empty signals still forget immediately; only an ambiguous
        // resolver miss or temporarily invalid canonical wrapper receives this bounded grace window.
        private const val AVAILABILITY_MISS_ATTEMPTS = 20
        private const val REGISTRATION_ATTEMPTS = 20
        private const val CARRIER_NORMALIZATION_ATTEMPTS = 20
        private const val LOADED_CHUNK_ENTITY_VISIBILITY_ATTEMPTS = 100
    }

    private data class PendingLifetimeRegistration(
        val item: Item,
        val materialName: String,
        val amount: Int,
        val worldName: String,
        val blockX: Int,
        val blockY: Int,
        val blockZ: Int,
        val lease: TransientItemTargetLease,
    )
}

private fun isAvailable(item: Item): Boolean = item.isValid && !item.isDead

private fun isItemChunkLoaded(item: Item): Boolean {
    val location = item.location
    return item.world.isChunkLoaded(location.blockX shr CHUNK_SHIFT, location.blockZ shr CHUNK_SHIFT)
}

private const val CHUNK_SHIFT = 4
