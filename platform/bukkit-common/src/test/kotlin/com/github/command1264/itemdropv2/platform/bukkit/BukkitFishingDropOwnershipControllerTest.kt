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
import org.bukkit.entity.FishHook
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.player.PlayerFishEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitFishingDropOwnershipControllerTest {
    @Test
    fun `assigns caught item before fishing event returns`() {
        val repository = RecordingRepository()
        val item = item()
        val controller = controller(repository)

        controller.onPlayerFish(PlayerFishEvent(player(), item, fishHook(), PlayerFishEvent.State.CAUGHT_FISH))

        assertEquals(
            OWNER,
            repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `ignores fishing states without caught item`() {
        val repository = RecordingRepository()
        val controller = controller(repository)

        controller.onPlayerFish(PlayerFishEvent(player(), null, fishHook(), PlayerFishEvent.State.FISHING))

        assertTrue(repository.states.isEmpty())
    }

    @Test
    fun `ignores caught entity that is not an item`() {
        val repository = RecordingRepository()
        val controller = controller(repository)

        controller.onPlayerFish(PlayerFishEvent(player(), player(), fishHook(), PlayerFishEvent.State.CAUGHT_FISH))

        assertTrue(repository.states.isEmpty())
    }

    private fun controller(repository: RecordingRepository): BukkitFishingDropOwnershipController =
        BukkitFishingDropOwnershipController(
            assignmentService = ItemOwnershipAssignmentService(repository, ItemDisplaySettingsRepository(::settings)),
            warningSink = DisplayWarningSink { error("unexpected warning: $it") },
            itemRefresh = ItemOwnershipRefresh {},
        )

    private fun settings(): ItemDisplaySettings {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return ItemDisplaySettings(true, emptySet(), template, template)
    }

    private fun player(): Player = proxy { method, _ -> if (method.name == "getUniqueId") OWNER else defaultValue(method.returnType) }

    private fun item(): Item {
        val id = UUID.randomUUID()
        return proxy { method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                "getWorld" -> world()
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun fishHook(): FishHook = proxy()

    private fun world(): World = proxy { method, _ -> if (method.name == "getName") "world" else defaultValue(method.returnType) }

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
        private val OWNER = UUID.fromString("00000000-0000-0000-0000-000000000020")

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
