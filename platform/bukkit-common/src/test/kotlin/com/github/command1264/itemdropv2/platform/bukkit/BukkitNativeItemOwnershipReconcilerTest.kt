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
import org.bukkit.entity.Item
import org.bukkit.event.entity.ItemSpawnEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitNativeItemOwnershipReconcilerTest {
    @Test
    fun `imports the native owner while reconciling an eligible item`() {
        val repository = RecordingRepository()
        val reconciler =
            BukkitNativeItemOwnershipReconciler(
                assignmentService = ItemOwnershipAssignmentService(repository, ItemDisplaySettingsRepository(::settings)),
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                ownerResolver = BukkitNativeItemOwnerResolver { NativeItemOwnerResolution.Found(OWNER_ID) },
            )

        reconciler.reconcile(item(pickupDelay = 0))

        assertEquals(OWNER_ID, repository.state?.ownership?.ownerUuid)
        assertEquals(listOf(OWNER_ID), repository.state?.ownership?.eligibleOwnerUuids)
    }

    @Test
    fun `never imports ownership into a never-pickup transient item`() {
        val repository = RecordingRepository()
        val reconciler =
            BukkitNativeItemOwnershipReconciler(
                assignmentService = ItemOwnershipAssignmentService(repository, ItemDisplaySettingsRepository(::settings)),
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                ownerResolver = BukkitNativeItemOwnerResolver { NativeItemOwnerResolution.Found(OWNER_ID) },
            )

        reconciler.reconcile(item(pickupDelay = Short.MAX_VALUE.toInt()))

        assertEquals(null, repository.state)
    }

    @Test
    fun `reflective resolver reads a public runtime getOwner method`() {
        val ownerAwareItem =
            Proxy.newProxyInstance(
                Item::class.java.classLoader,
                arrayOf(Item::class.java, OwnerAwareItem::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getOwner" -> OWNER_ID
                    else -> defaultValue(method.returnType)
                }
            } as Item

        assertEquals(NativeItemOwnerResolution.Found(OWNER_ID), ReflectiveBukkitNativeItemOwnerResolver.resolve(ownerAwareItem))
        assertEquals(NativeItemOwnerResolution.Unsupported, ReflectiveBukkitNativeItemOwnerResolver.resolve(item(0)))
    }

    private fun item(pickupDelay: Int): Item {
        val world = proxy<World> { method -> if (method.name == "getName") "world" else defaultValue(method.returnType) }
        return proxy { method ->
            when (method.name) {
                "getUniqueId" -> ITEM_ID
                "getWorld" -> world
                "getPickupDelay" -> pickupDelay
                "isValid" -> true
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun settings(): ItemDisplaySettings {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return ItemDisplaySettings(true, emptySet(), template, template)
    }

    private inline fun <reified T> proxy(crossinline answer: (java.lang.reflect.Method) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> answer(method) } as T

    private class RecordingRepository : ItemStateRepository {
        var state: ItemState? = null

        override fun load(entityId: UUID): ItemStateLoadResult = state?.let(ItemStateLoadResult::Loaded) ?: ItemStateLoadResult.Absent

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            this.state = state
            return ItemStateWriteResult.Applied
        }
    }

    private interface OwnerAwareItem {
        fun getOwner(): UUID?
    }

    private companion object {
        private val ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000010")
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
