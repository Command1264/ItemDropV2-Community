package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
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
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Item
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent.DamageCause
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityUnleashEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.player.PlayerUnleashEntityEvent
import org.bukkit.inventory.EntityEquipment
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

@Suppress("LargeClass")
class BukkitSpecialEntityDropOwnershipControllerTest {
    @Test
    fun `legacy 1_14 death event receives equipment that CraftBukkit omitted`() {
        val fixture = fixture(minecraftVersion = "1.14.1")
        val equipment = armorStandEquipment()
        val damage = meleeDamage(armorStand(equipment))
        fixture.controller.onEntityDamage(damage)
        val event = EntityDeathEvent(damage.entity as ArmorStand, mutableListOf(ItemStack(Material.ARMOR_STAND)))

        fixture.controller.onLegacyArmorStandDeath(event)

        assertEquals(
            listOf(
                Material.ARMOR_STAND,
                Material.DIAMOND_HELMET,
                Material.DIAMOND_CHESTPLATE,
                Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_BOOTS,
                Material.DIAMOND_SWORD,
                Material.SHIELD,
            ),
            event.drops.map(ItemStack::getType),
        )
    }

    @Test
    fun `legacy repair does not duplicate equipment already exposed by a backport`() {
        val fixture = fixture(minecraftVersion = "1.14")
        val equipment = armorStandEquipment()
        val damage = meleeDamage(armorStand(equipment))
        fixture.controller.onEntityDamage(damage)
        val drops =
            mutableListOf(
                ItemStack(Material.ARMOR_STAND),
                ItemStack(Material.DIAMOND_HELMET),
                ItemStack(Material.DIAMOND_CHESTPLATE),
                ItemStack(Material.DIAMOND_LEGGINGS),
                ItemStack(Material.DIAMOND_BOOTS),
                ItemStack(Material.DIAMOND_SWORD),
                ItemStack(Material.SHIELD),
            )
        val event = EntityDeathEvent(damage.entity as ArmorStand, drops)

        fixture.controller.onLegacyArmorStandDeath(event)

        assertEquals(7, event.drops.size)
    }

    @Test
    fun `fixed 1_14_2 and newer versions never use legacy repair`() {
        listOf("1.14.2", "1.14.3", "1.14.4", "1.15", "26.2").forEach { version ->
            val fixture = fixture(minecraftVersion = version)
            val damage = meleeDamage(armorStand(armorStandEquipment()))
            fixture.controller.onEntityDamage(damage)
            val event = EntityDeathEvent(damage.entity as ArmorStand, mutableListOf(ItemStack(Material.ARMOR_STAND)))

            fixture.controller.onLegacyArmorStandDeath(event)

            assertEquals(listOf(Material.ARMOR_STAND), event.drops.map(ItemStack::getType), version)
        }
    }

    @Test
    fun `legacy repair requires an exact player contributor and melee or projectile damage`() {
        val fixture = fixture(minecraftVersion = "1.14")
        val armorStand = armorStand(armorStandEquipment(), DamageCause.BLOCK_EXPLOSION)
        val event = EntityDeathEvent(armorStand, mutableListOf(ItemStack(Material.ARMOR_STAND)))
        fixture.controller.onEntityDamage(meleeDamage(armorStand))

        fixture.controller.onLegacyArmorStandDeath(event)

        assertEquals(listOf(Material.ARMOR_STAND), event.drops.map(ItemStack::getType))
    }

    @Test
    fun `armor stand uses the last exact player source without damage weighting`() {
        val fixture = fixture()
        val armorStand = entity<ArmorStand>(ARMOR_STAND_ID, EntityType.ARMOR_STAND, 0.0)
        val expectedDrops =
            listOf(
                Material.ARMOR_STAND,
                Material.DIAMOND_HELMET,
                Material.DIAMOND_CHESTPLATE,
                Material.DIAMOND_LEGGINGS,
                Material.DIAMOND_BOOTS,
                Material.DIAMOND_SWORD,
                Material.SHIELD,
            )
        fixture.controller.onEntityDamage(
            EntityDamageByEntityEvent(
                entity<Player>(FIRST_PLAYER_ID, EntityType.PLAYER, 0.0),
                armorStand,
                DamageCause.ENTITY_ATTACK,
                1.0,
            ),
        )
        fixture.controller.onEntityDeath(EntityDeathEvent(armorStand, expectedDrops.map(::ItemStack).toMutableList()))

        expectedDrops.zip(ARMOR_STAND_DROP_IDS).forEach { (material, id) ->
            fixture.controller.onItemSpawn(ItemSpawnEvent(item(id, material, 0.2)))
        }
        fixture.runQueuedTasks()

        assertEquals(
            List(expectedDrops.size) { FIRST_PLAYER_ID },
            ARMOR_STAND_DROP_IDS.map { id ->
                fixture.repository.states
                    .getValue(id)
                    .ownership
                    ?.ownerUuid
            },
        )
        assertEquals(ARMOR_STAND_DROP_IDS, fixture.refreshed)
    }

    @Test
    fun `armor stand keeps contributor through an empty death event before equipment drops`() {
        val fixture = fixture()
        val armorStand = entity<ArmorStand>(ARMOR_STAND_ID, EntityType.ARMOR_STAND, 0.0)
        fixture.controller.onEntityDamage(
            EntityDamageByEntityEvent(
                entity<Player>(FIRST_PLAYER_ID, EntityType.PLAYER, 0.0),
                armorStand,
                DamageCause.ENTITY_ATTACK,
                1.0,
            ),
        )

        fixture.controller.onEntityDeath(EntityDeathEvent(armorStand, mutableListOf()))
        fixture.controller.onEntityDeath(
            EntityDeathEvent(armorStand, mutableListOf(ItemStack(Material.DIAMOND_HELMET))),
        )
        fixture.controller.onItemSpawn(
            ItemSpawnEvent(item(ARMOR_STAND_DROP_IDS.first(), Material.DIAMOND_HELMET, 0.2)),
        )

        assertEquals(
            FIRST_PLAYER_ID,
            fixture.repository.states
                .getValue(ARMOR_STAND_DROP_IDS.first())
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `adjacent item frames keep content and frame body ownership isolated`() {
        val fixture = fixture()
        val firstFrame = itemFrame(FIRST_FRAME_ID, 0.0, ItemStack(Material.DIAMOND, 3))
        val secondFrame = itemFrame(SECOND_FRAME_ID, 1.0, ItemStack(Material.DIAMOND, 2))
        val firstPlayer = entity<Player>(FIRST_PLAYER_ID, EntityType.PLAYER, 0.0)
        val secondPlayer = entity<Player>(SECOND_PLAYER_ID, EntityType.PLAYER, 1.0)

        fixture.controller.onEntityDamage(EntityDamageByEntityEvent(firstPlayer, firstFrame, DamageCause.ENTITY_ATTACK, 1.0))
        fixture.controller.onEntityDamage(EntityDamageByEntityEvent(secondPlayer, secondFrame, DamageCause.ENTITY_ATTACK, 1.0))
        fixture.controller.onHangingBreak(HangingBreakByEntityEvent(firstFrame, firstPlayer))
        fixture.controller.onHangingBreak(HangingBreakByEntityEvent(secondFrame, secondPlayer))

        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_CONTENT_DROP_ID, Material.DIAMOND, 0.1, amount = 3)))
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(SECOND_CONTENT_DROP_ID, Material.DIAMOND, 1.1, amount = 2)))
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_FRAME_DROP_ID, Material.ITEM_FRAME, 0.1)))
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(SECOND_FRAME_DROP_ID, Material.ITEM_FRAME, 1.1)))
        fixture.runQueuedTasks()

        assertEquals(
            FIRST_PLAYER_ID,
            fixture.repository.states
                .getValue(FIRST_CONTENT_DROP_ID)
                .ownership
                ?.ownerUuid,
        )
        assertEquals(
            SECOND_PLAYER_ID,
            fixture.repository.states
                .getValue(SECOND_CONTENT_DROP_ID)
                .ownership
                ?.ownerUuid,
        )
        assertEquals(
            FIRST_PLAYER_ID,
            fixture.repository.states
                .getValue(FIRST_FRAME_DROP_ID)
                .ownership
                ?.ownerUuid,
        )
        assertEquals(
            SECOND_PLAYER_ID,
            fixture.repository.states
                .getValue(SECOND_FRAME_DROP_ID)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `player unleash records one lead at the unleashed entity location`() {
        val fixture = fixture()
        val player = entity<Player>(FIRST_PLAYER_ID, EntityType.PLAYER, 0.0)
        val leashed = entity<LivingEntity>(FIRST_LEASHED_ID, EntityType.COW, 6.5)

        fixture.controller.onPlayerUnleash(PlayerUnleashEntityEvent(leashed, player))
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 6.5)))
        fixture.runQueuedTasks()

        assertEquals(
            FIRST_PLAYER_ID,
            fixture.repository.states
                .getValue(FIRST_LEAD_DROP_ID)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `adjacent players unleashing different entities keep lead ownership isolated`() {
        val fixture = fixture()
        val firstPlayer = entity<Player>(FIRST_PLAYER_ID, EntityType.PLAYER, 0.0)
        val secondPlayer = entity<Player>(SECOND_PLAYER_ID, EntityType.PLAYER, 1.0)
        val firstLeashed = entity<LivingEntity>(FIRST_LEASHED_ID, EntityType.COW, 0.0)
        val secondLeashed = entity<LivingEntity>(SECOND_LEASHED_ID, EntityType.SHEEP, 1.0)

        fixture.controller.onPlayerUnleash(PlayerUnleashEntityEvent(firstLeashed, firstPlayer))
        fixture.controller.onPlayerUnleash(PlayerUnleashEntityEvent(secondLeashed, secondPlayer))
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 0.1)))
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(SECOND_LEAD_DROP_ID, Material.LEAD, 1.1)))
        fixture.runQueuedTasks()

        assertEquals(
            listOf(FIRST_PLAYER_ID, SECOND_PLAYER_ID),
            listOf(FIRST_LEAD_DROP_ID, SECOND_LEAD_DROP_ID).map { id ->
                fixture.repository.states
                    .getValue(id)
                    .ownership
                    ?.ownerUuid
            },
        )
    }

    @Test
    fun `cancelled player unleash does not claim a later lead`() {
        val fixture = fixture()
        val event =
            PlayerUnleashEntityEvent(
                entity<LivingEntity>(FIRST_LEASHED_ID, EntityType.COW, 0.0),
                entity<Player>(FIRST_PLAYER_ID, EntityType.PLAYER, 0.0),
            )
        event.isCancelled = true

        fixture.controller.onPlayerUnleash(event)
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 0.0)))
        fixture.runQueuedTasks()

        assertTrue(fixture.repository.states.isEmpty())
    }

    @Test
    fun `breaking leash hitch records one lead at each exactly linked entity`() {
        val fixture = fixture()
        val linked =
            listOf(
                leashedEntity(FIRST_LEASHED_ID, 5.5, LEASH_HITCH_ID),
                leashedEntity(SECOND_LEASHED_ID, -6.5, LEASH_HITCH_ID),
            )
        val unrelated = leashedEntity(UNRELATED_LEASHED_ID, 1.0, OTHER_HOLDER_ID)
        val hitch = leashHitch(LEASH_HITCH_ID, linked + unrelated)

        fixture.controller.onHangingBreak(
            HangingBreakByEntityEvent(
                hitch,
                entity<Player>(FIRST_PLAYER_ID, EntityType.PLAYER, 0.0),
            ),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 5.5)))
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(SECOND_LEAD_DROP_ID, Material.LEAD, -6.5)))
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(UNRELATED_LEAD_DROP_ID, Material.LEAD, 1.0)))
        fixture.runQueuedTasks()

        assertEquals(
            listOf(FIRST_PLAYER_ID, FIRST_PLAYER_ID),
            listOf(FIRST_LEAD_DROP_ID, SECOND_LEAD_DROP_ID).map { id ->
                fixture.repository.states
                    .getValue(id)
                    .ownership
                    ?.ownerUuid
            },
        )
        assertTrue(UNRELATED_LEAD_DROP_ID !in fixture.repository.states)
    }

    @Test
    fun `distance unleash assigns the lead to the exact player leash holder`() {
        val fixture = fixture()
        val player = entity<Player>(FIRST_PLAYER_ID, EntityType.PLAYER, 0.0)
        val leashed = leashedEntity(FIRST_LEASHED_ID, 10.5, player)

        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.DISTANCE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))
        fixture.runQueuedTasks()

        assertEquals(
            FIRST_PLAYER_ID,
            fixture.repository.states
                .getValue(FIRST_LEAD_DROP_ID)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `adjacent distance unleashes keep exact player holders isolated`() {
        val fixture = fixture()
        val first = leashedEntity(FIRST_LEASHED_ID, 0.0, entity<Player>(FIRST_PLAYER_ID, EntityType.PLAYER, 0.0))
        val second = leashedEntity(SECOND_LEASHED_ID, 1.0, entity<Player>(SECOND_PLAYER_ID, EntityType.PLAYER, 1.0))

        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(first, EntityUnleashEvent.UnleashReason.DISTANCE),
        )
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(second, EntityUnleashEvent.UnleashReason.DISTANCE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 0.1)))
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(SECOND_LEAD_DROP_ID, Material.LEAD, 1.1)))
        fixture.runQueuedTasks()

        assertEquals(
            listOf(FIRST_PLAYER_ID, SECOND_PLAYER_ID),
            listOf(FIRST_LEAD_DROP_ID, SECOND_LEAD_DROP_ID).map { id ->
                fixture.repository.states
                    .getValue(id)
                    .ownership
                    ?.ownerUuid
            },
        )
    }

    @Test
    fun `distance unleash supports modern non-living leashable entities`() {
        val fixture = fixture()
        val player = entity<Player>(FIRST_PLAYER_ID, EntityType.PLAYER, 0.0)
        val leashed =
            proxy<TestLeashableEntity> { method ->
                when (method.name) {
                    "getUniqueId" -> FIRST_LEASHED_ID
                    "getType" -> EntityType.BOAT
                    "getWorld" -> world()
                    "getLocation" -> Location(world(), 10.5, 64.0, 0.0)
                    "getLeashHolder" -> player
                    else -> defaultValue(method.returnType)
                }
            }

        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.DISTANCE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))
        fixture.runQueuedTasks()

        assertEquals(
            FIRST_PLAYER_ID,
            fixture.repository.states
                .getValue(FIRST_LEAD_DROP_ID)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `distance unleash does not guess an owner when the holder is not a player`() {
        val fixture = fixture()
        val hitch = entity<Entity>(LEASH_HITCH_ID, EntityType.LEASH_HITCH, 0.0)
        val leashed = leashedEntity(FIRST_LEASHED_ID, 10.5, hitch)

        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.DISTANCE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))
        fixture.runQueuedTasks()

        assertTrue(fixture.repository.states.isEmpty())
    }

    @Test
    fun `holder gone assigns the lead to an exact dead player holder`() {
        val fixture = fixture()
        val deadPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val leashed = leashedEntity(FIRST_LEASHED_ID, 10.5, deadPlayer)

        fixture.controller.onPlayerDeath(playerDeath(deadPlayer))
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))
        fixture.runQueuedTasks()

        assertEquals(
            FIRST_PLAYER_ID,
            fixture.repository.states
                .getValue(FIRST_LEAD_DROP_ID)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `holder gone without player death event stays unowned even when holder reports dead`() {
        val fixture = fixture()
        val removedPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val leashed = leashedEntity(FIRST_LEASHED_ID, 10.5, removedPlayer)

        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))
        fixture.runQueuedTasks()

        assertTrue(fixture.repository.states.isEmpty())
    }

    @Test
    fun `holder gone does not override damage attribution when the leashed entity is also dead`() {
        val fixture = fixture()
        val deadPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val deadLeashed = leashedEntity(FIRST_LEASHED_ID, 10.5, deadPlayer, isDead = true)

        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(deadLeashed, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))
        fixture.runQueuedTasks()

        assertTrue(fixture.repository.states.isEmpty())
    }

    @Test
    fun `dead player holder owns each lead from multiple leashed entities`() {
        val fixture = fixture()
        val deadPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val first = leashedEntity(FIRST_LEASHED_ID, 0.0, deadPlayer)
        val second = leashedEntity(SECOND_LEASHED_ID, 1.0, deadPlayer)

        fixture.controller.onPlayerDeath(playerDeath(deadPlayer))
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(first, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(second, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 0.1)))
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(SECOND_LEAD_DROP_ID, Material.LEAD, 1.1)))
        fixture.runQueuedTasks()

        assertEquals(
            listOf(FIRST_PLAYER_ID, FIRST_PLAYER_ID),
            listOf(FIRST_LEAD_DROP_ID, SECOND_LEAD_DROP_ID).map { id ->
                fixture.repository.states
                    .getValue(id)
                    .ownership
                    ?.ownerUuid
            },
        )
    }

    @Test
    fun `holder gone lead stays unowned after death context expires`() {
        val fixture = fixture()
        val deadPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val leashed = leashedEntity(FIRST_LEASHED_ID, 10.5, deadPlayer)

        fixture.controller.onPlayerDeath(playerDeath(deadPlayer))
        assertEquals(listOf(3L), fixture.scheduledDelayTicks)
        fixture.runQueuedTasks()
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))

        assertTrue(fixture.repository.states.isEmpty())
    }

    @Test
    fun `newer death context survives expiry task from earlier death`() {
        val fixture = fixture()
        val deadPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val leashed = leashedEntity(FIRST_LEASHED_ID, 10.5, deadPlayer)

        fixture.controller.onPlayerDeath(playerDeath(deadPlayer))
        fixture.controller.onPlayerDeath(playerDeath(deadPlayer))
        fixture.runNextQueuedTask()
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))

        assertEquals(
            FIRST_PLAYER_ID,
            fixture.repository.states
                .getValue(FIRST_LEAD_DROP_ID)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `one death context owns leads from multiple leashed entities`() {
        val fixture = fixture()
        val deadPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val first = leashedEntity(FIRST_LEASHED_ID, 0.0, deadPlayer)
        val second = leashedEntity(SECOND_LEASHED_ID, 1.0, deadPlayer)

        fixture.controller.onPlayerDeath(playerDeath(deadPlayer))
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(first, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(second, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 0.1)))
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(SECOND_LEAD_DROP_ID, Material.LEAD, 1.1)))

        assertEquals(
            listOf(FIRST_PLAYER_ID, FIRST_PLAYER_ID),
            listOf(FIRST_LEAD_DROP_ID, SECOND_LEAD_DROP_ID).map { id ->
                fixture.repository.states
                    .getValue(id)
                    .ownership
                    ?.ownerUuid
            },
        )
    }

    @Test
    fun `close clears death context before a holder gone lead`() {
        val fixture = fixture()
        val deadPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val leashed = leashedEntity(FIRST_LEASHED_ID, 10.5, deadPlayer)

        fixture.controller.onPlayerDeath(playerDeath(deadPlayer))
        fixture.controller.close()
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))

        assertTrue(fixture.repository.states.isEmpty())
    }

    @Test
    fun `rejected death context expiry does not leave holder gone lead owned`() {
        val fixture = fixture(DelayedMainThreadTaskExecutor { _, _ -> error("scheduler rejected") })
        val deadPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val leashed = leashedEntity(FIRST_LEASHED_ID, 10.5, deadPlayer)

        fixture.controller.onPlayerDeath(playerDeath(deadPlayer))
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))

        assertTrue(fixture.repository.states.isEmpty())
        assertTrue(fixture.warnings.isNotEmpty())
    }

    @Test
    fun `oldest death context remains eligible at exact capacity`() {
        val fixture = fixture()
        val firstDeadPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val leashed = leashedEntity(FIRST_LEASHED_ID, 10.5, firstDeadPlayer)

        fixture.controller.onPlayerDeath(playerDeath(firstDeadPlayer))
        (1..1_023).forEach { suffix ->
            fixture.controller.onPlayerDeath(playerDeath(player(UUID(0L, 9_000L + suffix), isDead = true)))
        }
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))

        assertEquals(
            FIRST_PLAYER_ID,
            fixture.repository.states
                .getValue(FIRST_LEAD_DROP_ID)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `oldest death context is evicted when capacity is exceeded`() {
        val fixture = fixture()
        val firstDeadPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val leashed = leashedEntity(FIRST_LEASHED_ID, 10.5, firstDeadPlayer)

        fixture.controller.onPlayerDeath(playerDeath(firstDeadPlayer))
        (1..1_024).forEach { suffix ->
            fixture.controller.onPlayerDeath(playerDeath(player(UUID(0L, 10_000L + suffix), isDead = true)))
        }
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))

        assertTrue(fixture.repository.states.isEmpty())
    }

    @Test
    fun `blocked world death context does not replay after the world is enabled`() {
        val fixture = fixture()
        val deadPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val leashed = leashedEntity(FIRST_LEASHED_ID, 10.5, deadPlayer)

        fixture.settings.blockedWorlds = setOf("world")
        fixture.controller.onPlayerDeath(playerDeath(deadPlayer))
        fixture.settings.blockedWorlds = emptySet()
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))

        assertTrue(fixture.repository.states.isEmpty())
    }

    @Test
    fun `holder gone stays unowned when a recorded death holder is live`() {
        val fixture = fixture()
        val deadPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val livePlayer = player(FIRST_PLAYER_ID, isDead = false)
        val leashed = leashedEntity(FIRST_LEASHED_ID, 10.5, livePlayer)

        fixture.controller.onPlayerDeath(playerDeath(deadPlayer))
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))

        assertTrue(fixture.repository.states.isEmpty())
    }

    @Test
    fun `replaced death context remains eligible after capacity overflow`() {
        val fixture = fixture()
        val firstDeadPlayer = player(FIRST_PLAYER_ID, isDead = true)
        val leashed = leashedEntity(FIRST_LEASHED_ID, 10.5, firstDeadPlayer)

        fixture.controller.onPlayerDeath(playerDeath(firstDeadPlayer))
        (1..1_023).forEach { suffix ->
            fixture.controller.onPlayerDeath(playerDeath(player(UUID(0L, 20_000L + suffix), isDead = true)))
        }
        fixture.controller.onPlayerDeath(playerDeath(firstDeadPlayer))
        fixture.controller.onPlayerDeath(playerDeath(player(UUID(0L, 21_024L), isDead = true)))
        fixture.controller.onEntityUnleash(
            EntityUnleashEvent(leashed, EntityUnleashEvent.UnleashReason.HOLDER_GONE),
        )
        fixture.controller.onItemSpawn(ItemSpawnEvent(item(FIRST_LEAD_DROP_ID, Material.LEAD, 10.5)))

        assertEquals(
            FIRST_PLAYER_ID,
            fixture.repository.states
                .getValue(FIRST_LEAD_DROP_ID)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `legacy hitch and modern knot entity type names are both recognized`() {
        assertTrue(isLeashKnotTypeName("LEASH_HITCH"))
        assertTrue(isLeashKnotTypeName("LEASH_KNOT"))
    }

    private fun fixture(
        delayedTaskExecutor: DelayedMainThreadTaskExecutor? = null,
        minecraftVersion: String = "1.14.4",
    ): Fixture {
        val repository = Repository()
        val settings = settings()
        val queued = mutableListOf<() -> Unit>()
        val refreshed = mutableListOf<UUID>()
        val warnings = mutableListOf<String>()
        val scheduledDelayTicks = mutableListOf<Long>()
        val controller =
            BukkitSpecialEntityDropOwnershipController(
                assignmentService = ItemOwnershipAssignmentService(repository, settings),
                settingsRepository = settings,
                delayedTaskExecutor =
                    delayedTaskExecutor ?: DelayedMainThreadTaskExecutor { delayTicks, task ->
                        scheduledDelayTicks += delayTicks
                        queued += task
                    },
                warningSink = DisplayWarningSink(warnings::add),
                itemRefresh = ItemOwnershipRefresh(refreshed::add),
                minecraftVersion = minecraftVersion,
                dropTracker = EntityDeathDropTracker { expected, actual -> expected.type == actual.type },
                itemStackMatcher = { expected, actual -> expected.type == actual.type },
            )
        return Fixture(
            controller = controller,
            repository = repository,
            settings = settings,
            refreshed = refreshed,
            warnings = warnings,
            scheduledDelayTicks = scheduledDelayTicks,
            runNextQueuedTask = { queued.removeAt(0).invoke() },
            runQueuedTasks = { while (queued.isNotEmpty()) queued.removeAt(0).invoke() },
        )
    }

    private fun playerDeath(player: Player): PlayerDeathEvent = PlayerDeathEvent(player, mutableListOf(), 0, "fixture")

    private fun meleeDamage(armorStand: ArmorStand): EntityDamageByEntityEvent =
        EntityDamageByEntityEvent(
            entity<Player>(FIRST_PLAYER_ID, EntityType.PLAYER, 0.0),
            armorStand,
            DamageCause.ENTITY_ATTACK,
            1.0,
        )

    private fun armorStand(
        equipment: EntityEquipment,
        lastDamageCause: DamageCause = DamageCause.ENTITY_ATTACK,
    ): ArmorStand {
        val damageEvent =
            EntityDamageByEntityEvent(
                entity<Player>(FIRST_PLAYER_ID, EntityType.PLAYER, 0.0),
                entity<ArmorStand>(ARMOR_STAND_ID, EntityType.ARMOR_STAND, 0.0),
                lastDamageCause,
                1.0,
            )
        return proxy { method ->
            when (method.name) {
                "getUniqueId" -> ARMOR_STAND_ID
                "getType" -> EntityType.ARMOR_STAND
                "getWorld" -> world()
                "getLocation" -> Location(world(), 0.0, 64.0, 0.0)
                "getEquipment" -> equipment
                "getLastDamageCause" -> damageEvent
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun armorStandEquipment(): EntityEquipment =
        proxy { method ->
            when (method.name) {
                "getHelmet" -> ItemStack(Material.DIAMOND_HELMET)
                "getChestplate" -> ItemStack(Material.DIAMOND_CHESTPLATE)
                "getLeggings" -> ItemStack(Material.DIAMOND_LEGGINGS)
                "getBoots" -> ItemStack(Material.DIAMOND_BOOTS)
                "getItemInMainHand" -> ItemStack(Material.DIAMOND_SWORD)
                "getItemInOffHand" -> ItemStack(Material.SHIELD)
                else -> defaultValue(method.returnType)
            }
        }

    private fun settings(): MutableSettingsRepository {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return MutableSettingsRepository(template)
    }

    private fun itemFrame(
        id: UUID,
        x: Double,
        content: ItemStack,
    ): ItemFrame =
        proxy { method ->
            when (method.name) {
                "getUniqueId" -> id
                "getType" -> EntityType.ITEM_FRAME
                "getWorld" -> world()
                "getLocation" -> Location(world(), x, 64.0, 0.0)
                "getItem" -> content
                else -> defaultValue(method.returnType)
            }
        }

    private fun leashHitch(
        id: UUID,
        nearbyEntities: List<Entity>,
    ): org.bukkit.entity.Hanging =
        proxy { method ->
            when (method.name) {
                "getUniqueId" -> id
                "getType" -> EntityType.LEASH_HITCH
                "getWorld" -> world()
                "getLocation" -> Location(world(), 0.0, 64.0, 0.0)
                "getNearbyEntities" -> nearbyEntities
                else -> defaultValue(method.returnType)
            }
        }

    private fun leashedEntity(
        id: UUID,
        x: Double,
        holderId: UUID,
    ): LivingEntity = leashedEntity(id, x, entity<Entity>(holderId, EntityType.LEASH_HITCH, 0.0))

    private fun leashedEntity(
        id: UUID,
        x: Double,
        holder: Entity,
        isDead: Boolean = false,
    ): LivingEntity =
        proxy { method ->
            when (method.name) {
                "getUniqueId" -> id
                "getType" -> EntityType.COW
                "getWorld" -> world()
                "getLocation" -> Location(world(), x, 64.0, 0.0)
                "isLeashed" -> true
                "getLeashHolder" -> holder
                "isDead" -> isDead
                else -> defaultValue(method.returnType)
            }
        }

    private fun item(
        id: UUID,
        material: Material,
        x: Double,
        amount: Int = 1,
    ): Item =
        proxy { method ->
            when (method.name) {
                "getUniqueId" -> id
                "getType" -> EntityType.DROPPED_ITEM
                "getWorld" -> world()
                "getLocation" -> Location(world(), x, 64.0, 0.0)
                "getItemStack" -> ItemStack(material, amount)
                else -> defaultValue(method.returnType)
            }
        }

    private inline fun <reified T : Entity> entity(
        id: UUID,
        type: EntityType,
        x: Double,
    ): T =
        proxy { method ->
            when (method.name) {
                "getUniqueId" -> id
                "getType" -> type
                "getWorld" -> world()
                "getLocation" -> Location(world(), x, 64.0, 0.0)
                else -> defaultValue(method.returnType)
            }
        }

    private fun player(
        id: UUID,
        isDead: Boolean,
    ): Player =
        proxy { method ->
            when (method.name) {
                "getUniqueId" -> id
                "getType" -> EntityType.PLAYER
                "getWorld" -> world()
                "getLocation" -> Location(world(), 0.0, 64.0, 0.0)
                "isDead" -> isDead
                else -> defaultValue(method.returnType)
            }
        }

    private fun world(): World =
        proxy { method ->
            when (method.name) {
                "getUID" -> WORLD_ID
                "getName" -> "world"
                else -> defaultValue(method.returnType)
            }
        }

    private inline fun <reified T> proxy(crossinline answer: (java.lang.reflect.Method) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method)
        } as T

    private data class Fixture(
        val controller: BukkitSpecialEntityDropOwnershipController,
        val repository: Repository,
        val settings: MutableSettingsRepository,
        val refreshed: List<UUID>,
        val warnings: List<String>,
        val scheduledDelayTicks: List<Long>,
        val runNextQueuedTask: () -> Unit,
        val runQueuedTasks: () -> Unit,
    )

    private interface TestLeashableEntity : Entity {
        fun getLeashHolder(): Entity
    }

    private class MutableSettingsRepository(
        private val template: DisplayTemplate,
        var blockedWorlds: Set<String> = emptySet(),
    ) : ItemDisplaySettingsRepository {
        override fun settings(): ItemDisplaySettings =
            ItemDisplaySettings(
                enabled = true,
                blockedWorlds = blockedWorlds,
                singleItemTemplate = template,
                multipleItemTemplate = template,
            )
    }

    private class Repository : ItemStateRepository {
        val states = mutableMapOf<UUID, ItemState>()

        override fun load(entityId: UUID): ItemStateLoadResult = ItemStateLoadResult.Loaded(states[entityId] ?: ItemState(null, 300))

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            states[entityId] = state
            return ItemStateWriteResult.Applied
        }
    }

    private companion object {
        private val WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        private val FIRST_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000102")
        private val SECOND_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000103")
        private val ARMOR_STAND_ID = UUID.fromString("00000000-0000-0000-0000-000000000104")
        private val FIRST_FRAME_ID = UUID.fromString("00000000-0000-0000-0000-000000000105")
        private val SECOND_FRAME_ID = UUID.fromString("00000000-0000-0000-0000-000000000106")
        private val ARMOR_STAND_DROP_IDS =
            (120..126).map { suffix ->
                UUID.fromString("00000000-0000-0000-0000-${suffix.toString().padStart(12, '0')}")
            }
        private val FIRST_CONTENT_DROP_ID = UUID.fromString("00000000-0000-0000-0000-000000000108")
        private val SECOND_CONTENT_DROP_ID = UUID.fromString("00000000-0000-0000-0000-000000000109")
        private val FIRST_FRAME_DROP_ID = UUID.fromString("00000000-0000-0000-0000-000000000110")
        private val SECOND_FRAME_DROP_ID = UUID.fromString("00000000-0000-0000-0000-000000000111")
        private val LEASH_HITCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000112")
        private val OTHER_HOLDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000113")
        private val FIRST_LEASHED_ID = UUID.fromString("00000000-0000-0000-0000-000000000114")
        private val SECOND_LEASHED_ID = UUID.fromString("00000000-0000-0000-0000-000000000115")
        private val UNRELATED_LEASHED_ID = UUID.fromString("00000000-0000-0000-0000-000000000116")
        private val FIRST_LEAD_DROP_ID = UUID.fromString("00000000-0000-0000-0000-000000000117")
        private val SECOND_LEAD_DROP_ID = UUID.fromString("00000000-0000-0000-0000-000000000118")
        private val UNRELATED_LEAD_DROP_ID = UUID.fromString("00000000-0000-0000-0000-000000000119")

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
