package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentOutcome
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentRequest
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentService
import org.bukkit.Material
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Hanging
import org.bukkit.entity.Item
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityUnleashEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.player.PlayerUnleashEntityEvent
import org.bukkit.inventory.ItemStack
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.UUID

@Suppress("TooManyFunctions")
public class BukkitSpecialEntityDropOwnershipController internal constructor(
    private val assignmentService: ItemOwnershipAssignmentService,
    private val settingsRepository: ItemDisplaySettingsRepository,
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val itemRefresh: ItemOwnershipRefresh,
    minecraftVersion: String = UNKNOWN_MINECRAFT_VERSION,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    private val dropTracker: EntityDeathDropTracker = EntityDeathDropTracker(),
    private val itemStackMatcher: (ItemStack, ItemStack) -> Boolean = ItemStack::isSimilar,
) : Listener,
    AutoCloseable {
    private val armorStandContributors = linkedMapOf<UUID, ContributorContext>()
    private val deadHolderContexts = linkedMapOf<UUID, Long>()
    private val repairsLegacyArmorStandEquipmentDrops =
        isLegacyArmorStandEquipmentDropAffectedVersion(minecraftVersion)
    private var nextContributorContextId = 1L
    private var nextDeadHolderContextId = 1L

    public constructor(
        assignmentService: ItemOwnershipAssignmentService,
        settingsRepository: ItemDisplaySettingsRepository,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
    ) : this(
        assignmentService,
        settingsRepository,
        delayedTaskExecutor,
        warningSink,
        itemRefresh,
        UNKNOWN_MINECRAFT_VERSION,
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
        EntityDeathDropTracker(),
        ItemStack::isSimilar,
    )

    public constructor(
        assignmentService: ItemOwnershipAssignmentService,
        settingsRepository: ItemDisplaySettingsRepository,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
        minecraftVersion: String,
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
    ) : this(
        assignmentService,
        settingsRepository,
        delayedTaskExecutor,
        warningSink,
        itemRefresh,
        minecraftVersion,
        transientTargetLeaseFactory,
        EntityDeathDropTracker(),
        ItemStack::isSimilar,
    )

    public constructor(
        assignmentService: ItemOwnershipAssignmentService,
        settingsRepository: ItemDisplaySettingsRepository,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
    ) : this(
        assignmentService,
        settingsRepository,
        delayedTaskExecutor,
        warningSink,
        itemRefresh,
        UNKNOWN_MINECRAFT_VERSION,
        transientTargetLeaseFactory,
        EntityDeathDropTracker(),
        ItemStack::isSimilar,
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("ReturnCount")
    public fun onEntityDamage(event: EntityDamageByEntityEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "EntityDamageByEntityEvent") || event.isCancelled) return
        val ownerUuid = resolvePlayerContributor(event.damager) ?: return
        when (val target = event.entity) {
            is ArmorStand -> recordArmorStandContributor(target, ownerUuid)
            is ItemFrame -> recordItemFrameContent(target, ownerUuid)
        }
    }

    /**
     * CraftBukkit SPIGOT-4982 was fixed after 1.14.1 and before 1.14.2. On the affected
     * player/projectile path the death event fires while all six equipment slots are still on the
     * Armor Stand, then the entity is removed before those late-collected stacks can be spawned.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    @Suppress("ReturnCount")
    public fun onLegacyArmorStandDeath(event: EntityDeathEvent) {
        if (!repairsLegacyArmorStandEquipmentDrops) return
        if (!ensureSynchronous(event.isAsynchronous, "EntityDeathEvent")) return
        val armorStand = event.entity as? ArmorStand ?: return
        if (!isEnabled(armorStand.world.name) || armorStand.uniqueId !in armorStandContributors) return
        if (armorStand.lastDamageCause?.cause !in LEGACY_REPAIR_DAMAGE_CAUSES) return
        val equipment = armorStand.equipment ?: return
        val equippedStacks =
            listOf(
                equipment.helmet,
                equipment.chestplate,
                equipment.leggings,
                equipment.boots,
                equipment.itemInMainHand,
                equipment.itemInOffHand,
            ).filterNotNull().filter(::isActualItem)
        appendMissingStacks(event.drops, equippedStacks)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @Suppress("ReturnCount")
    public fun onEntityDeath(event: EntityDeathEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "EntityDeathEvent")) return
        val armorStand = event.entity as? ArmorStand ?: return
        if (!isEnabled(armorStand.world.name) || event.drops.isEmpty()) return
        val ownerUuid = armorStandContributors.remove(armorStand.uniqueId)?.ownerUuid ?: return
        recordDrops(armorStand, event.drops, ownerUuid)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @Suppress("ReturnCount")
    public fun onPlayerDeath(event: PlayerDeathEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "PlayerDeathEvent")) return
        val player = event.entity
        if (!isEnabled(player.world.name)) return
        val contextId = nextDeadHolderContextId++
        deadHolderContexts.remove(player.uniqueId)
        deadHolderContexts[player.uniqueId] = contextId
        while (deadHolderContexts.size > MAX_DEAD_HOLDER_CONTEXTS) {
            deadHolderContexts.keys.firstOrNull()?.let(deadHolderContexts::remove)
        }
        scheduleDeadHolderExpiry(player.uniqueId, contextId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("ReturnCount")
    public fun onHangingBreak(event: HangingBreakByEntityEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "HangingBreakByEntityEvent") || event.isCancelled) return
        val ownerUuid = event.remover?.let(::resolvePlayerContributor) ?: return
        val hanging = event.entity
        if (!isEnabled(hanging.world.name)) return
        if (isLeashKnotTypeName(hanging.type.name)) {
            recordLinkedLeashDrops(hanging, ownerUuid)
            return
        }
        val expectedDrops = expectedHangingDrops(hanging)
        if (expectedDrops.isEmpty()) return
        recordDrops(hanging, expectedDrops, ownerUuid)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("ReturnCount")
    public fun onPlayerUnleash(event: PlayerUnleashEntityEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "PlayerUnleashEntityEvent") || event.isCancelled) return
        val entity = event.entity
        if (!isEnabled(entity.world.name)) return
        recordDrops(entity, listOf(ItemStack(Material.LEAD)), event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("ReturnCount")
    public fun onEntityUnleash(event: EntityUnleashEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "EntityUnleashEvent")) return
        val entity = event.entity
        if (!isEnabled(entity.world.name)) return
        if (entity.isDead) return
        val holder = leashHolder(entity) as? Player ?: return
        val ownerUuid =
            when (event.reason) {
                EntityUnleashEvent.UnleashReason.DISTANCE -> holder.uniqueId
                EntityUnleashEvent.UnleashReason.HOLDER_GONE ->
                    holder.takeIf(Player::isDead)?.uniqueId?.takeIf(deadHolderContexts::containsKey)
                else -> null
            } ?: return
        recordDrops(entity, listOf(ItemStack(Material.LEAD)), ownerUuid)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    public fun onItemSpawn(event: ItemSpawnEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "ItemSpawnEvent") || event.isCancelled) return
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
            "special entity",
        )
    }

    private fun recordArmorStandContributor(
        armorStand: ArmorStand,
        ownerUuid: UUID,
    ) {
        if (!isEnabled(armorStand.world.name)) return
        val contextId = nextContributorContextId++
        armorStandContributors[armorStand.uniqueId] = ContributorContext(contextId, ownerUuid)
        while (armorStandContributors.size > MAX_ARMOR_STAND_CONTEXTS) {
            armorStandContributors.keys.firstOrNull()?.let(armorStandContributors::remove)
        }
        scheduleArmorStandExpiry(armorStand.uniqueId, contextId)
    }

    private fun recordItemFrameContent(
        frame: ItemFrame,
        ownerUuid: UUID,
    ) {
        if (!isEnabled(frame.world.name)) return
        val content = frame.item
        if (content.type == Material.AIR || content.amount <= 0) return
        recordDrops(frame, listOf(content), ownerUuid)
    }

    private fun expectedHangingDrops(hanging: Hanging): List<ItemStack> {
        val body =
            when (hanging.type.name) {
                "ITEM_FRAME" -> ItemStack(Material.ITEM_FRAME)
                "GLOW_ITEM_FRAME" -> Material.matchMaterial("GLOW_ITEM_FRAME")?.let(::ItemStack)
                "PAINTING" -> ItemStack(Material.PAINTING)
                else -> null
            }
        return listOfNotNull(body)
    }

    private fun recordLinkedLeashDrops(
        hitch: Hanging,
        ownerUuid: UUID,
    ) {
        hitch
            .getNearbyEntities(LEASH_HITCH_SEARCH_RADIUS, LEASH_HITCH_SEARCH_RADIUS, LEASH_HITCH_SEARCH_RADIUS)
            .asSequence()
            .filterIsInstance<LivingEntity>()
            .filter { entity -> entity.isLeashed && leashHolderId(entity) == hitch.uniqueId }
            .forEach { entity -> recordDrops(entity, listOf(ItemStack(Material.LEAD)), ownerUuid) }
    }

    private fun leashHolderId(entity: LivingEntity): UUID? = leashHolder(entity)?.uniqueId

    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    private fun leashHolder(entity: org.bukkit.entity.Entity): org.bukkit.entity.Entity? {
        if (entity is LivingEntity) {
            return try {
                entity.leashHolder
            } catch (_: IllegalStateException) {
                null
            }
        }
        val method = LEASH_HOLDER_METHODS.get(entity.javaClass).firstOrNull() ?: return null
        return try {
            method.invoke(entity) as? org.bukkit.entity.Entity
        } catch (error: InvocationTargetException) {
            if (error.cause !is IllegalStateException) {
                warningSink.warn("public leash holder access failed (${error.cause?.javaClass?.simpleName ?: "unknown"})")
            }
            null
        } catch (error: ReflectiveOperationException) {
            warningSink.warn("public leash holder access failed (${error.javaClass.simpleName})")
            null
        }
    }

    private fun recordDrops(
        source: org.bukkit.entity.Entity,
        expectedDrops: List<ItemStack>,
        ownerUuid: UUID,
    ) {
        val location = source.location
        val contextId =
            dropTracker.record(
                EntityDeathDropContext(
                    worldUuid = source.world.uid,
                    x = location.x,
                    y = location.y,
                    z = location.z,
                    expectedDrops = expectedDrops,
                    eligibleOwnerUuids = listOf(ownerUuid),
                ),
            )
        scheduleDropExpiry(contextId)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleArmorStandExpiry(
        entityId: UUID,
        contextId: Long,
    ) {
        try {
            delayedTaskExecutor.execute(ARMOR_STAND_CONTEXT_LIFETIME_TICKS) {
                if (armorStandContributors[entityId]?.id == contextId) {
                    armorStandContributors.remove(entityId)
                }
            }
        } catch (error: RuntimeException) {
            armorStandContributors.remove(entityId)
            warningSink.warn("armor stand ownership context scheduling failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleDeadHolderExpiry(
        playerId: UUID,
        contextId: Long,
    ) {
        try {
            delayedTaskExecutor.execute(DEAD_HOLDER_CONTEXT_LIFETIME_TICKS) {
                if (deadHolderContexts[playerId] == contextId) {
                    deadHolderContexts.remove(playerId)
                }
            }
        } catch (error: RuntimeException) {
            if (deadHolderContexts[playerId] == contextId) {
                deadHolderContexts.remove(playerId)
            }
            warningSink.warn("dead holder ownership context scheduling failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleDropExpiry(contextId: Long) {
        try {
            delayedTaskExecutor.execute(DROP_CONTEXT_LIFETIME_TICKS) { dropTracker.expire(contextId) }
        } catch (error: RuntimeException) {
            dropTracker.expire(contextId)
            warningSink.warn("special entity drop context scheduling failed (${error.javaClass.simpleName})")
        }
    }

    private fun isEnabled(worldName: String): Boolean {
        val settings = settingsRepository.settings()
        return settings.ownership.enabled && worldName !in settings.blockedWorlds
    }

    private fun appendMissingStacks(
        drops: MutableList<ItemStack>,
        expected: List<ItemStack>,
    ) {
        val existingDrops = drops.toList()
        val unclaimedAmounts = existingDrops.map(ItemStack::getAmount).toMutableList()
        expected.forEach { expectedStack ->
            var missingAmount = expectedStack.amount
            existingDrops.forEachIndexed { index, existingStack ->
                if (missingAmount <= 0 ||
                    unclaimedAmounts[index] <= 0 ||
                    !itemStackMatcher(expectedStack, existingStack)
                ) {
                    return@forEachIndexed
                }
                val matchedAmount = minOf(missingAmount, unclaimedAmounts[index])
                missingAmount -= matchedAmount
                unclaimedAmounts[index] -= matchedAmount
            }
            if (missingAmount > 0) {
                drops += expectedStack.clone().also { it.amount = missingAmount }
            }
        }
    }

    private fun isActualItem(itemStack: ItemStack): Boolean = itemStack.type != Material.AIR && itemStack.amount > 0

    private fun ensureSynchronous(
        asynchronous: Boolean,
        eventName: String,
    ): Boolean {
        if (!asynchronous) return true
        warningSink.warn("ignored asynchronous $eventName")
        return false
    }

    override fun close() {
        armorStandContributors.clear()
        deadHolderContexts.clear()
        dropTracker.clear()
    }

    private data class ContributorContext(
        val id: Long,
        val ownerUuid: UUID,
    )

    private companion object {
        private const val ARMOR_STAND_CONTEXT_LIFETIME_TICKS = 100L
        private const val DEAD_HOLDER_CONTEXT_LIFETIME_TICKS = 3L
        private const val DROP_CONTEXT_LIFETIME_TICKS = 3L
        private const val MAX_ARMOR_STAND_CONTEXTS = 1_024
        private const val MAX_DEAD_HOLDER_CONTEXTS = 1_024
        private const val LEASH_HITCH_SEARCH_RADIUS = 7.0
        private const val UNKNOWN_MINECRAFT_VERSION = "unknown"
        private val LEASH_HOLDER_METHODS =
            object : ClassValue<List<Method>>() {
                override fun computeValue(type: Class<*>): List<Method> =
                    type.methods.filter { method ->
                        method.name == "getLeashHolder" &&
                            method.parameterCount == 0 &&
                            org.bukkit.entity.Entity::class.java.isAssignableFrom(method.returnType)
                    }
            }
        private val LEGACY_REPAIR_DAMAGE_CAUSES =
            setOf(
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK,
                org.bukkit.event.entity.EntityDamageEvent.DamageCause.PROJECTILE,
            )
    }
}

internal fun isLeashKnotTypeName(entityTypeName: String): Boolean = entityTypeName == "LEASH_HITCH" || entityTypeName == "LEASH_KNOT"

internal fun isLegacyArmorStandEquipmentDropAffectedVersion(minecraftVersion: String): Boolean =
    minecraftVersion == "1.14" || minecraftVersion == "1.14.1"
