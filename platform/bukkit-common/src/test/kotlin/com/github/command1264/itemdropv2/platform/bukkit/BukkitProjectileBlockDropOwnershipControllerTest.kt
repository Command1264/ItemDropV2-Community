package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.BlockDropOwnershipService
import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.entity.EntityType
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitProjectileBlockDropOwnershipControllerTest {
    @Test
    fun `attributes chorus flower broken by a player projectile`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)
        val drop = item(x = 10)

        controller.onProjectileHit(ProjectileHitEvent(projectile(player()), block(Material.CHORUS_FLOWER)))
        controller.onItemSpawn(ItemSpawnEvent(drop))
        delayedTasks.last().invoke()

        assertEquals(
            OWNER_ID,
            repository.states
                .getValue(drop.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `does not attribute chorus flower projectile drops without a player contributor`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)

        controller.onProjectileHit(ProjectileHitEvent(projectile(null), block(Material.CHORUS_FLOWER)))
        controller.onItemSpawn(ItemSpawnEvent(item(x = 10)))
        delayedTasks.forEach { it() }

        assertTrue(repository.states.isEmpty())
    }

    @Test
    fun `does not claim a nearby chorus flower drop from another block`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)

        controller.onProjectileHit(ProjectileHitEvent(projectile(player()), block(Material.CHORUS_FLOWER)))
        controller.onItemSpawn(ItemSpawnEvent(item(x = 11)))
        delayedTasks.forEach { it() }

        assertTrue(repository.states.isEmpty())
    }

    @Test
    fun `does not create projectile ownership context for non chorus flower blocks`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)

        controller.onProjectileHit(ProjectileHitEvent(projectile(player()), block(Material.STONE)))
        controller.onItemSpawn(ItemSpawnEvent(item(x = 10)))
        delayedTasks.forEach { it() }

        assertTrue(repository.states.isEmpty())
    }

    private fun controller(
        repository: RecordingRepository,
        delayedTasks: MutableList<() -> Unit>,
    ): BukkitProjectileBlockDropOwnershipController {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        val settings = ItemDisplaySettings(true, emptySet(), template, template)
        return BukkitProjectileBlockDropOwnershipController(
            service = BlockDropOwnershipService(repository, ItemDisplaySettingsRepository { settings }),
            delayedTaskExecutor = DelayedMainThreadTaskExecutor { _, task -> delayedTasks += task },
            warningSink = DisplayWarningSink { error("unexpected warning: $it") },
            itemRefresh = ItemOwnershipRefresh {},
        )
    }

    private fun block(material: Material): Block =
        proxy { method, _ ->
            when (method.name) {
                "getType" -> material
                "getX" -> 10
                "getY" -> 72
                "getZ" -> 20
                "getWorld" -> world()
                else -> defaultValue(method.returnType)
            }
        }

    private fun item(x: Int): Item {
        val id = UUID.randomUUID()
        return proxy { method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                "getWorld" -> world()
                "getLocation" -> Location(world(), x + 0.5, 72.2, 20.5)
                "getItemStack" -> ItemStack(Material.CHORUS_FLOWER)
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun world(): World =
        proxy { method, _ ->
            when (method.name) {
                "getUID" -> WORLD_ID
                "getName" -> "world"
                else -> defaultValue(method.returnType)
            }
        }

    private fun player(): Player = proxy { method, _ -> if (method.name == "getUniqueId") OWNER_ID else defaultValue(method.returnType) }

    private fun projectile(shooter: Player?): Projectile =
        proxy { method, _ ->
            when (method.name) {
                "getShooter" -> shooter
                "getType" -> EntityType.ARROW
                else -> defaultValue(method.returnType)
            }
        }

    private inline fun <reified T> proxy(crossinline answer: (Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method, args)
        } as T

    private class RecordingRepository : ItemStateRepository {
        val states = mutableMapOf<UUID, ItemState>()

        override fun load(entityId: UUID): ItemStateLoadResult =
            states[entityId]?.let(ItemStateLoadResult::Loaded) ?: ItemStateLoadResult.Absent

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            states[entityId] = state
            return ItemStateWriteResult.Applied
        }
    }

    private companion object {
        private val WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020")

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
