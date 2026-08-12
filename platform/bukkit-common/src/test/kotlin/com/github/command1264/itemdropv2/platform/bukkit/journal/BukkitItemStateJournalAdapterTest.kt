package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.DirectItemPresentationJournalView
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.ItemStateJournalPresentation
import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import com.github.command1264.itemdropv2.core.PresentationJournalSnapshotResult
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

class BukkitItemStateJournalAdapterTest {
    @Test
    fun `stages one upsert snapshot with entity identity chunk and allocated revision`() {
        val runtime = CapturingRuntime()
        val presentation = ItemStateJournalPresentation("managed", true, "original", false)
        val adapter =
            BukkitItemStateJournalAdapter(
                runtime = runtime,
                presentation = CapturingPresentation(presentation),
                fingerprintFactory =
                    BukkitItemStateJournalFingerprintFactory(
                        stackSerializer = { mapOf("type" to "STONE") },
                        materialKey = { "minecraft:stone" },
                        ownedPdcRemover = {},
                    ),
                sessionId = SESSION_UUID,
            )
        val state = ItemState(null, 7, 300)

        assertEquals(ItemStateDurabilityOutcome.Accepted(false), adapter.prepare(item(), state, 9))

        val record = requireNotNull(runtime.record)
        assertEquals(ItemStateJournalIdentity(WORLD_UUID, ENTITY_UUID), record.identity)
        assertEquals(2, record.chunk.x)
        assertEquals(-1, record.chunk.z)
        assertEquals(9L, record.revision)
        assertEquals(SESSION_UUID, record.sessionId)
        assertSame(state, record.state)
        assertSame(presentation, record.presentation)
        assertEquals(9L, adapter.latestRevision(item()))
    }

    @Test
    fun `refreshes presentation at the existing state revision`() {
        val runtime = CapturingRuntime()
        val presentation = ItemStateJournalPresentation("managed-1", true, "original", false)
        val adapter =
            BukkitItemStateJournalAdapter(
                runtime = runtime,
                presentation = CapturingPresentation(presentation),
                fingerprintFactory =
                    BukkitItemStateJournalFingerprintFactory(
                        stackSerializer = { mapOf("type" to "STONE") },
                        materialKey = { "minecraft:stone" },
                        ownedPdcRemover = {},
                    ),
                sessionId = SESSION_UUID,
            )
        val state = ItemState(null, 1, 300)

        assertEquals(ItemStateDurabilityOutcome.Accepted(false), adapter.refreshPresentation(item(), state, 9))

        val record = requireNotNull(runtime.presentationRecord)
        assertEquals(9L, record.revision)
        assertSame(state, record.state)
        assertSame(presentation, record.presentation)
    }

    @Test
    fun `discards the durable state by entity identity`() {
        val runtime = CapturingRuntime()
        val adapter =
            BukkitItemStateJournalAdapter(
                runtime = runtime,
                presentation = CapturingPresentation(ItemStateJournalPresentation(null, false, null, false)),
            )

        assertEquals(ItemStateDurabilityOutcome.Accepted(false), adapter.discard(item()))

        assertEquals(ItemStateJournalIdentity(WORLD_UUID, ENTITY_UUID), runtime.discardedIdentity)
    }

    private fun item(): Item {
        val world =
            Proxy.newProxyInstance(
                World::class.java.classLoader,
                arrayOf(World::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getUID" -> WORLD_UUID
                    else -> primitiveDefault(method.returnType)
                }
            } as World
        val stack = ItemStack(Material.STONE, 64)
        return Proxy.newProxyInstance(
            Item::class.java.classLoader,
            arrayOf(Item::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getUniqueId" -> ENTITY_UUID
                "getWorld" -> world
                "getLocation" -> Location(world, 33.0, 64.0, -1.0)
                "getItemStack" -> stack
                else -> primitiveDefault(method.returnType)
            }
        } as Item
    }

    private class CapturingRuntime : ItemStateJournalRuntimeAccess {
        var record: ItemStateJournalRecord? = null
        var presentationRecord: ItemStateJournalRecord? = null
        var discardedIdentity: ItemStateJournalIdentity? = null

        override fun stage(record: ItemStateJournalRecord): ItemStateDurabilityOutcome {
            this.record = record
            return ItemStateDurabilityOutcome.Accepted(false)
        }

        override fun get(identity: ItemStateJournalIdentity): ItemStateJournalRecord? = record?.takeIf { it.identity == identity }

        override fun refreshPresentation(record: ItemStateJournalRecord): ItemStateDurabilityOutcome {
            presentationRecord = record
            return ItemStateDurabilityOutcome.Accepted(false)
        }

        override fun discard(identity: ItemStateJournalIdentity): ItemStateDurabilityOutcome {
            discardedIdentity = identity
            return ItemStateDurabilityOutcome.Accepted(false)
        }

        override fun degrade(reason: String) = Unit
    }

    private class CapturingPresentation(
        private val snapshot: ItemStateJournalPresentation,
    ) : DirectItemPresentationJournalView<Item> {
        override fun capture(target: Item): PresentationJournalSnapshotResult = PresentationJournalSnapshotResult.Captured(snapshot)

        override fun restore(
            target: Item,
            snapshot: ItemStateJournalPresentation,
        ): PresentationJournalSnapshotResult = PresentationJournalSnapshotResult.Applied
    }

    private companion object {
        private val WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000601")
        private val ENTITY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000602")
        private val SESSION_UUID = UUID.fromString("00000000-0000-0000-0000-000000000603")

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
