package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.EntityDamageAttributionService
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentService
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.attribute.AttributeInstance
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityUnleashEvent
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitEntityDropOwnershipControllerTest {
    @Test
    fun `living entity keeps damage ledger through an empty death event before actual drops`() {
        val ledger = EntityDamageLedger()
        ledger.record(ENTITY_ID, OWNER_ID, 10.0, 20.0, currentTick = 10L, timeoutTicks = 6_000L)
        val tracker = EntityDeathDropTracker { expected, actual -> expected.type == actual.type }
        val settings = settings()
        val controller =
            BukkitEntityDropOwnershipController(
                attributionService = EntityDamageAttributionService(),
                assignmentService = ItemOwnershipAssignmentService(NoOpRepository, settings),
                settingsRepository = settings,
                delayedTaskExecutor = DelayedMainThreadTaskExecutor { _, _ -> },
                currentTick = { 11L },
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                itemRefresh = ItemOwnershipRefresh { },
                ledger = ledger,
                dropTracker = tracker,
            )
        val entity = livingEntity()

        controller.onEntityDeath(EntityDeathEvent(entity, mutableListOf()))
        controller.onEntityDeath(EntityDeathEvent(entity, mutableListOf(ItemStack(Material.ROTTEN_FLESH))))

        assertEquals(
            listOf(OWNER_ID),
            tracker.claim(EntitySpawnedDrop(WORLD_ID, 0.0, 64.0, 0.0, ItemStack(Material.ROTTEN_FLESH))),
        )
    }

    @Test
    fun `leashed entity keeps damage ledger through an empty death event before actual drops`() {
        val ledger = EntityDamageLedger()
        ledger.record(ENTITY_ID, OWNER_ID, 10.0, 20.0, currentTick = 10L, timeoutTicks = 6_000L)
        val tracker = EntityDeathDropTracker { expected, actual -> expected.type == actual.type }
        val settings = settings()
        val controller =
            BukkitEntityDropOwnershipController(
                attributionService = EntityDamageAttributionService(),
                assignmentService = ItemOwnershipAssignmentService(NoOpRepository, settings),
                settingsRepository = settings,
                delayedTaskExecutor = DelayedMainThreadTaskExecutor { _, _ -> },
                currentTick = { 11L },
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                itemRefresh = ItemOwnershipRefresh { },
                ledger = ledger,
                dropTracker = tracker,
            )
        val entity = livingEntity(isLeashed = true, isDead = true)

        controller.onEntityDeath(EntityDeathEvent(entity, mutableListOf()))
        controller.onEntityDeath(EntityDeathEvent(entity, mutableListOf(ItemStack(Material.ROTTEN_FLESH))))

        assertEquals(
            listOf(OWNER_ID),
            tracker.claim(EntitySpawnedDrop(WORLD_ID, 0.0, 64.0, 0.0, ItemStack(Material.ROTTEN_FLESH))),
        )
    }

    @Test
    fun `leashed entity death attributes its separately spawned lead to the damage winner`() {
        val ledger = EntityDamageLedger()
        ledger.record(ENTITY_ID, OWNER_ID, 20.0, 20.0, currentTick = 10L, timeoutTicks = 6_000L)
        val tracker = EntityDeathDropTracker { expected, actual -> expected.type == actual.type }
        val settings = settings()
        val controller =
            BukkitEntityDropOwnershipController(
                attributionService = EntityDamageAttributionService(),
                assignmentService = ItemOwnershipAssignmentService(NoOpRepository, settings),
                settingsRepository = settings,
                delayedTaskExecutor = DelayedMainThreadTaskExecutor { _, _ -> },
                currentTick = { 11L },
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                itemRefresh = ItemOwnershipRefresh { },
                ledger = ledger,
                dropTracker = tracker,
            )

        val entity = livingEntity(isLeashed = true, isDead = true)
        controller.onEntityDeath(
            EntityDeathEvent(
                entity,
                mutableListOf(ItemStack(Material.ROTTEN_FLESH)),
            ),
        )
        assertEquals(
            null,
            tracker.claim(EntitySpawnedDrop(WORLD_ID, 0.0, 64.0, 0.0, ItemStack(Material.LEAD))),
        )

        controller.onEntityUnleash(
            EntityUnleashEvent(entity, EntityUnleashEvent.UnleashReason.PLAYER_UNLEASH),
        )

        assertEquals(
            listOf(OWNER_ID),
            tracker.claim(EntitySpawnedDrop(WORLD_ID, 0.0, 64.0, 0.0, ItemStack(Material.LEAD))),
        )
    }

    @Test
    fun `environmental leashed entity death does not guess a lead owner`() {
        val tracker = EntityDeathDropTracker { expected, actual -> expected.type == actual.type }
        val settings = settings()
        val controller =
            BukkitEntityDropOwnershipController(
                attributionService = EntityDamageAttributionService(),
                assignmentService = ItemOwnershipAssignmentService(NoOpRepository, settings),
                settingsRepository = settings,
                delayedTaskExecutor = DelayedMainThreadTaskExecutor { _, _ -> },
                currentTick = { 11L },
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                itemRefresh = ItemOwnershipRefresh { },
                ledger = EntityDamageLedger(),
                dropTracker = tracker,
            )

        val entity = livingEntity(isLeashed = true, isDead = true)
        controller.onEntityDeath(
            EntityDeathEvent(
                entity,
                mutableListOf(ItemStack(Material.ROTTEN_FLESH)),
            ),
        )
        controller.onEntityUnleash(
            EntityUnleashEvent(entity, EntityUnleashEvent.UnleashReason.PLAYER_UNLEASH),
        )

        assertEquals(
            null,
            tracker.claim(EntitySpawnedDrop(WORLD_ID, 0.0, 64.0, 0.0, ItemStack(Material.LEAD))),
        )
    }

    private fun settings(): ItemDisplaySettingsRepository {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return ItemDisplaySettingsRepository {
            ItemDisplaySettings(
                enabled = true,
                blockedWorlds = emptySet(),
                singleItemTemplate = template,
                multipleItemTemplate = template,
            )
        }
    }

    private fun livingEntity(
        isLeashed: Boolean = false,
        isDead: Boolean = false,
    ): LivingEntity {
        val world =
            proxy<World> { method ->
                when (method.name) {
                    "getName" -> "world"
                    "getUID" -> WORLD_ID
                    else -> defaultValue(method.returnType)
                }
            }
        val maximumHealth = proxy<AttributeInstance> { method -> if (method.name == "getValue") 20.0 else defaultValue(method.returnType) }
        return proxy { method ->
            when (method.name) {
                "getUniqueId" -> ENTITY_ID
                "getType" -> EntityType.ZOMBIE
                "getWorld" -> world
                "getLocation" -> Location(world, 0.0, 64.0, 0.0)
                "getAttribute" -> maximumHealth
                "isLeashed" -> isLeashed
                "isDead" -> isDead
                else -> defaultValue(method.returnType)
            }
        }
    }

    private inline fun <reified T> proxy(crossinline answer: (java.lang.reflect.Method) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method)
        } as T

    private object NoOpRepository : ItemStateRepository {
        override fun load(entityId: UUID): ItemStateLoadResult = ItemStateLoadResult.Absent

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult = ItemStateWriteResult.Applied
    }

    private companion object {
        private val WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000201")
        private val ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000202")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000203")

        private fun defaultValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0F
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }
    }
}
