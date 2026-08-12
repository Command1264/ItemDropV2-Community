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
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityDropItemEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitShearingDropOwnershipControllerTest {
    @Test
    fun `assigns item dropped by freshly sheared entity to player`() {
        val repository = RecordingRepository()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = controller(repository, tasks)
        val source = entity(SOURCE, "world")
        val item = item("world")

        controller.onPlayerShear(PlayerShearEntityEvent(player(OWNER), source))
        controller.onEntityDropItem(EntityDropItemEvent(source, item))

        assertEquals(listOf(1L), tasks.map { it.first })
        assertEquals(
            OWNER,
            repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `ignores entity item drop without shearing context`() {
        val repository = RecordingRepository()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = controller(repository, tasks)

        controller.onEntityDropItem(EntityDropItemEvent(entity(SOURCE, "world"), item("world")))

        assertTrue(tasks.isEmpty())
        assertTrue(repository.states.isEmpty())
    }

    @Test
    fun `ignores cancelled shearing and cancelled item drop events`() {
        val repository = RecordingRepository()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = controller(repository, tasks)
        val source = entity(SOURCE, "world")
        val cancelledShear = PlayerShearEntityEvent(player(OWNER), source).apply { isCancelled = true }

        controller.onPlayerShear(cancelledShear)
        assertTrue(tasks.isEmpty())

        controller.onPlayerShear(PlayerShearEntityEvent(player(OWNER), source))
        val cancelledDrop = EntityDropItemEvent(source, item("world")).apply { isCancelled = true }
        controller.onEntityDropItem(cancelledDrop)

        assertEquals(1, tasks.size)
        assertTrue(repository.states.isEmpty())
    }

    @Test
    fun `does not attribute drops after context expires`() {
        val repository = RecordingRepository()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = controller(repository, tasks)
        val source = entity(SOURCE, "world")

        controller.onPlayerShear(PlayerShearEntityEvent(player(OWNER), source))
        tasks.single().second()
        controller.onEntityDropItem(EntityDropItemEvent(source, item("world")))

        assertEquals(1, tasks.size)
        assertTrue(repository.states.isEmpty())
    }

    @Test
    fun `ignores matching entity id from a different world`() {
        val repository = RecordingRepository()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = controller(repository, tasks)

        controller.onPlayerShear(PlayerShearEntityEvent(player(OWNER), entity(SOURCE, "world")))
        controller.onEntityDropItem(EntityDropItemEvent(entity(SOURCE, "other"), item("other")))

        assertEquals(1, tasks.size)
        assertTrue(repository.states.isEmpty())
    }

    @Test
    fun `old expiry does not remove renewed context for the same entity`() {
        val repository = RecordingRepository()
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller = controller(repository, tasks)
        val source = entity(SOURCE, "world")
        val item = item("world")

        controller.onPlayerShear(PlayerShearEntityEvent(player(OWNER), source))
        val oldExpiry = tasks.single().second
        controller.onPlayerShear(PlayerShearEntityEvent(player(NEW_OWNER), source))
        oldExpiry()
        controller.onEntityDropItem(EntityDropItemEvent(source, item))
        tasks.last().second()

        assertEquals(
            NEW_OWNER,
            repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `renewed context remains newest when bounded tracker evicts eldest entry`() {
        val tracker = ShearingContextTracker()
        tracker.record(SOURCE, "world", OWNER)
        repeat(1_023) { index -> tracker.record(UUID(1L, index.toLong()), "world", OWNER) }

        tracker.record(SOURCE, "world", NEW_OWNER)
        tracker.record(UUID(2L, 0L), "world", OWNER)

        assertEquals(NEW_OWNER, tracker.claim(SOURCE, "world", "world"))
    }

    @Test
    fun `context accepts at most sixty four item drops`() {
        val tracker = ShearingContextTracker()
        tracker.record(SOURCE, "world", OWNER)

        repeat(64) { assertEquals(OWNER, tracker.claim(SOURCE, "world", "world")) }

        assertEquals(null, tracker.claim(SOURCE, "world", "world"))
    }

    private fun controller(
        repository: RecordingRepository,
        tasks: MutableList<Pair<Long, () -> Unit>>,
    ): BukkitShearingDropOwnershipController =
        BukkitShearingDropOwnershipController(
            assignmentService = ItemOwnershipAssignmentService(repository, ItemDisplaySettingsRepository(::settings)),
            delayedTaskExecutor = DelayedMainThreadTaskExecutor { delay, task -> tasks += delay to task },
            warningSink = DisplayWarningSink { error("unexpected warning: $it") },
            itemRefresh = ItemOwnershipRefresh {},
        )

    private fun settings(): ItemDisplaySettings {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return ItemDisplaySettings(true, emptySet(), template, template)
    }

    private fun player(id: UUID): Player = proxy { method, _ -> if (method.name == "getUniqueId") id else defaultValue(method.returnType) }

    private fun entity(
        id: UUID,
        worldName: String,
    ): Entity =
        proxy { method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                "getWorld" -> world(worldName)
                else -> defaultValue(method.returnType)
            }
        }

    private fun item(worldName: String): Item {
        val id = UUID.randomUUID()
        return proxy { method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                "getWorld" -> world(worldName)
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun world(name: String): World = proxy { method, _ -> if (method.name == "getName") name else defaultValue(method.returnType) }

    private inline fun <reified T> proxy(
        crossinline answer: (Method, Array<out Any?>?) -> Any? = { method, _ -> defaultValue(method.returnType) },
    ): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> answer(method, args) } as T

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
        private val SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000030")
        private val OWNER = UUID.fromString("00000000-0000-0000-0000-000000000031")
        private val NEW_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000032")

        private fun defaultValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                else -> null
            }
    }
}
