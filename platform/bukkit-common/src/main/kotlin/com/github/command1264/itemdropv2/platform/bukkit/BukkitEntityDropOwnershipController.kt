package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.EntityDamageAttributionRequest
import com.github.command1264.itemdropv2.core.EntityDamageAttributionResult
import com.github.command1264.itemdropv2.core.EntityDamageAttributionService
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentOutcome
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentRequest
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentService
import org.bukkit.Material
import org.bukkit.attribute.Attribute
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Tameable
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityUnleashEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID

public class BukkitEntityDropOwnershipController internal constructor(
    private val attributionService: EntityDamageAttributionService,
    private val assignmentService: ItemOwnershipAssignmentService,
    private val settingsRepository: ItemDisplaySettingsRepository,
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val currentTick: () -> Long,
    private val warningSink: DisplayWarningSink,
    private val itemRefresh: ItemOwnershipRefresh,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    private val ledger: EntityDamageLedger = EntityDamageLedger(),
    private val dropTracker: EntityDeathDropTracker = EntityDeathDropTracker(),
) : Listener,
    AutoCloseable {
    private val leashDeathOwners = linkedMapOf<UUID, LeashDeathOwnerContext>()
    private var nextLeashDeathContextId = 1L

    public constructor(
        attributionService: EntityDamageAttributionService,
        assignmentService: ItemOwnershipAssignmentService,
        settingsRepository: ItemDisplaySettingsRepository,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        currentTick: () -> Long,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
    ) : this(
        attributionService,
        assignmentService,
        settingsRepository,
        delayedTaskExecutor,
        currentTick,
        warningSink,
        itemRefresh,
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
        EntityDamageLedger(),
        EntityDeathDropTracker(),
    )

    public constructor(
        attributionService: EntityDamageAttributionService,
        assignmentService: ItemOwnershipAssignmentService,
        settingsRepository: ItemDisplaySettingsRepository,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        currentTick: () -> Long,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
    ) : this(
        attributionService,
        assignmentService,
        settingsRepository,
        delayedTaskExecutor,
        currentTick,
        warningSink,
        itemRefresh,
        transientTargetLeaseFactory,
        EntityDamageLedger(),
        EntityDeathDropTracker(),
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("ReturnCount")
    public fun onEntityDamage(event: EntityDamageByEntityEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "EntityDamageByEntityEvent")) return
        val target = event.entity as? LivingEntity ?: return
        if (target is Player || target is ArmorStand) return
        val contributor = resolvePlayerContributor(event.damager) ?: return
        val settings = settingsRepository.settings()
        if (!settings.ownership.enabled || target.world.name in settings.blockedWorlds) return
        ledger.record(
            entityUuid = target.uniqueId,
            playerUuid = contributor,
            finalDamage = event.finalDamage,
            healthBeforeDamage = target.health,
            currentTick = currentTick(),
            timeoutTicks = settings.ownership.entity.combatTimeoutSeconds * TICKS_PER_SECOND,
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @Suppress("ReturnCount")
    public fun onEntityDeath(event: EntityDeathEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "EntityDeathEvent")) return
        val entity = event.entity
        if (entity is Player || entity is ArmorStand) return
        val settings = settingsRepository.settings()
        if (!settings.ownership.enabled || entity.world.name in settings.blockedWorlds) return
        val wasLeashed = entity.isLeashed
        if (event.drops.isEmpty() && !wasLeashed) return
        val timeoutTicks = settings.ownership.entity.combatTimeoutSeconds * TICKS_PER_SECOND
        val contributions =
            if (event.drops.isEmpty()) {
                ledger.peek(entity.uniqueId, currentTick(), timeoutTicks)
            } else {
                ledger.consume(entity.uniqueId, currentTick(), timeoutTicks)
            } ?: return
        val result =
            attributionService.attribute(
                EntityDamageAttributionRequest(
                    contributions = contributions,
                    strategy = settings.ownership.entity.strategy,
                    maximumHealth = entity.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: return,
                    minimumContributionPercent = settings.ownership.entity.minimumDamagePercentOfMaxHealth,
                ),
            )
        if (result !is EntityDamageAttributionResult.Attributed) return
        if (event.drops.isNotEmpty()) {
            recordDropContext(entity, event.drops, result.eligibleOwnerUuids)
        }
        if (wasLeashed) recordLeashDeathOwners(entity.uniqueId, result.eligibleOwnerUuids)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("ReturnCount")
    public fun onEntityUnleash(event: EntityUnleashEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "EntityUnleashEvent")) return
        val entity = event.entity
        if (!entity.isDead) return
        val owners = leashDeathOwners.remove(entity.uniqueId)?.eligibleOwnerUuids ?: return
        ledger.discard(entity.uniqueId)
        recordDropContext(entity, listOf(ItemStack(Material.LEAD)), owners)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("TooGenericExceptionCaught")
    public fun onItemSpawn(event: ItemSpawnEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "ItemSpawnEvent")) return
        val item = event.entity
        val location = item.location
        val owners =
            dropTracker.claim(
                EntitySpawnedDrop(
                    worldUuid = item.world.uid,
                    x = location.x,
                    y = location.y,
                    z = location.z,
                    itemStack = item.itemStack,
                ),
            ) ?: return
        assignItemOwnershipImmediately(
            item,
            owners,
            assignmentService,
            transientTargetLeaseFactory,
            itemRefresh,
            warningSink,
            "entity",
        )
    }

    public fun purgeExpiredCombats() {
        val timeoutTicks =
            settingsRepository
                .settings()
                .ownership.entity.combatTimeoutSeconds * TICKS_PER_SECOND
        ledger.purgeExpired(currentTick(), timeoutTicks)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleContextExpiry(contextId: Long) {
        try {
            delayedTaskExecutor.execute(DEATH_CONTEXT_LIFETIME_TICKS) { dropTracker.expire(contextId) }
        } catch (error: RuntimeException) {
            dropTracker.expire(contextId)
            warningSink.warn("entity death ownership context scheduling failed (${error.javaClass.simpleName})")
        }
    }

    private fun recordDropContext(
        entity: org.bukkit.entity.Entity,
        expectedDrops: List<ItemStack>,
        eligibleOwnerUuids: List<UUID>,
    ) {
        val location = entity.location
        val contextId =
            dropTracker.record(
                EntityDeathDropContext(
                    worldUuid = entity.world.uid,
                    x = location.x,
                    y = location.y,
                    z = location.z,
                    expectedDrops = expectedDrops,
                    eligibleOwnerUuids = eligibleOwnerUuids,
                ),
            )
        scheduleContextExpiry(contextId)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun recordLeashDeathOwners(
        entityUuid: UUID,
        eligibleOwnerUuids: List<UUID>,
    ) {
        val contextId = nextLeashDeathContextId++
        leashDeathOwners[entityUuid] = LeashDeathOwnerContext(contextId, eligibleOwnerUuids)
        try {
            delayedTaskExecutor.execute(DEATH_CONTEXT_LIFETIME_TICKS) {
                if (leashDeathOwners[entityUuid]?.id == contextId) {
                    leashDeathOwners.remove(entityUuid)
                    ledger.discard(entityUuid)
                }
            }
        } catch (error: RuntimeException) {
            leashDeathOwners.remove(entityUuid)
            warningSink.warn("leash death ownership context scheduling failed (${error.javaClass.simpleName})")
        }
    }

    private fun ensureSynchronous(
        asynchronous: Boolean,
        eventName: String,
    ): Boolean {
        if (!asynchronous) return true
        warningSink.warn("ignored asynchronous $eventName")
        return false
    }

    override fun close() {
        ledger.clear()
        dropTracker.clear()
        leashDeathOwners.clear()
    }

    private data class LeashDeathOwnerContext(
        val id: Long,
        val eligibleOwnerUuids: List<UUID>,
    )

    private companion object {
        private const val TICKS_PER_SECOND = 20L
        private const val DEATH_CONTEXT_LIFETIME_TICKS = 3L
    }
}

internal fun resolvePlayerContributor(damager: org.bukkit.entity.Entity): UUID? =
    when (damager) {
        is Player -> damager.uniqueId
        is Projectile ->
            when (val shooter = damager.shooter) {
                is Player -> shooter.uniqueId
                is Tameable -> shooter.owner?.uniqueId
                else -> null
            }
        is Tameable -> damager.owner?.uniqueId
        else -> null
    }
