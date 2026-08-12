package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.DirectItemPresentationView
import com.github.command1264.itemdropv2.core.ItemPresentation
import com.github.command1264.itemdropv2.core.ItemPresentationView
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.ItemStateJournalPresentation
import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import com.github.command1264.itemdropv2.core.PresentationJournalSnapshotResult
import com.github.command1264.itemdropv2.core.PresentationResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitJournalAwareItemPresentationViewTest {
    @Test
    fun `refreshes journal only after successful uuid presentation`() {
        val item = item()
        val backend = CapturingView()
        val refreshed = mutableListOf<Item>()
        val view = BukkitJournalAwareItemPresentationView(backend, backend, refreshed::add) { item }

        assertEquals(PresentationResult.Applied, view.present(ItemPresentation(ENTITY_UUID, "name", true)))
        backend.result = PresentationResult.Failed("Nope")
        assertEquals(PresentationResult.Failed("Nope"), view.clear(ENTITY_UUID))

        assertEquals(1, refreshed.size)
        assertSame(item, refreshed.single())
    }

    @Test
    fun `refreshes journal only after successful direct presentation`() {
        val item = item()
        val backend = CapturingView()
        val refreshed = mutableListOf<Item>()
        val view = BukkitJournalAwareItemPresentationView(backend, backend, refreshed::add) { null }

        assertEquals(PresentationResult.Applied, view.clear(item))
        backend.result = PresentationResult.MissingTarget
        assertEquals(
            PresentationResult.MissingTarget,
            view.present(item, ItemPresentation(ENTITY_UUID, "name", true)),
        )

        assertEquals(1, refreshed.size)
        assertSame(item, refreshed.single())
    }

    @Test
    fun `presentation refresher uses current journal state and degrades only once on failure`() {
        val item = item()
        val runtime = CapturingRuntime()
        val snapshot = MutablePresentationSnapshot()
        val adapter =
            BukkitItemStateJournalAdapter(
                runtime = runtime,
                presentation = snapshot,
                fingerprintFactory =
                    BukkitItemStateJournalFingerprintFactory(
                        stackSerializer = { mapOf("type" to "STONE") },
                        materialKey = { "minecraft:stone" },
                        ownedPdcRemover = {},
                    ),
                sessionId = SESSION_UUID,
            )
        val state = ItemState(null, 7, 300)
        adapter.prepare(item, state, 9)
        snapshot.managedName = "current-name"
        runtime.refreshOutcome = ItemStateDurabilityOutcome.Rejected("DiskBusy")
        val failures = mutableListOf<String>()
        val refresher = BukkitItemStateJournalPresentationRefresher(runtime, adapter, failures::add)

        refresher.refresh(item)
        refresher.refresh(item)

        assertEquals(1, runtime.refreshCalls)
        assertEquals(1, runtime.degradedReasons.size)
        assertEquals(1, failures.size)
        assertEquals(9L, runtime.presentationRecord?.revision)
        assertSame(state, runtime.presentationRecord?.state)
        assertEquals("current-name", runtime.presentationRecord?.presentation?.managedName)
    }

    private class CapturingView :
        ItemPresentationView,
        DirectItemPresentationView<Item> {
        var result: PresentationResult = PresentationResult.Applied

        override fun present(presentation: ItemPresentation): PresentationResult = result

        override fun clear(entityId: UUID): PresentationResult = result

        override fun present(
            target: Item,
            presentation: ItemPresentation,
        ): PresentationResult = result

        override fun clear(target: Item): PresentationResult = result
    }

    private class CapturingRuntime : ItemStateJournalRuntimeAccess {
        var record: ItemStateJournalRecord? = null
        var presentationRecord: ItemStateJournalRecord? = null
        var refreshOutcome: ItemStateDurabilityOutcome = ItemStateDurabilityOutcome.Accepted(false)
        var refreshCalls = 0
        val degradedReasons = mutableListOf<String>()

        override fun stage(record: ItemStateJournalRecord): ItemStateDurabilityOutcome {
            this.record = record
            return ItemStateDurabilityOutcome.Accepted(false)
        }

        override fun get(identity: ItemStateJournalIdentity): ItemStateJournalRecord? = record?.takeIf { it.identity == identity }

        override fun refreshPresentation(record: ItemStateJournalRecord): ItemStateDurabilityOutcome {
            refreshCalls++
            presentationRecord = record
            return refreshOutcome
        }

        override fun degrade(reason: String) {
            degradedReasons += reason
        }
    }

    private class MutablePresentationSnapshot : com.github.command1264.itemdropv2.core.DirectItemPresentationJournalView<Item> {
        var managedName = "initial-name"

        override fun capture(target: Item): PresentationJournalSnapshotResult =
            PresentationJournalSnapshotResult.Captured(
                ItemStateJournalPresentation(managedName, true, "original", false),
            )

        override fun restore(
            target: Item,
            snapshot: ItemStateJournalPresentation,
        ): PresentationJournalSnapshotResult = PresentationJournalSnapshotResult.Applied
    }

    private companion object {
        private val ENTITY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000701")
        private val WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000702")
        private val SESSION_UUID = UUID.fromString("00000000-0000-0000-0000-000000000703")

        private fun item(): Item {
            val world =
                Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { _, method, _ ->
                    when (method.name) {
                        "getUID" -> WORLD_UUID
                        else -> primitiveDefault(method.returnType)
                    }
                } as World
            val stack = ItemStack(Material.STONE, 1)
            return Proxy.newProxyInstance(Item::class.java.classLoader, arrayOf(Item::class.java)) { _, method, _ ->
                when (method.name) {
                    "getUniqueId" -> ENTITY_UUID
                    "getWorld" -> world
                    "getLocation" -> Location(world, 0.0, 64.0, 0.0)
                    "getItemStack" -> stack
                    "toString" -> "journal-aware-item"
                    else -> primitiveDefault(method.returnType)
                }
            } as Item
        }

        private fun primitiveDefault(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0f
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }
    }
}
