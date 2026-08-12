package com.github.command1264.itemdropv2.platform.bukkit.compatibility

import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemStateRepositoryTest
import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class BukkitCompatibilityBatchMarkerTest {
    private val marker = BukkitCompatibilityBatchMarker()

    @Test
    fun `round trips provisional source and output markers`() {
        val container = container()

        marker.writeSource(container, BATCH_ID)
        assertEquals(CompatibilityMarker.Source(BATCH_ID), marker.read(container))

        marker.writeOutput(container, BATCH_ID, 3)
        assertEquals(CompatibilityMarker.Output(BATCH_ID, 3), marker.read(container))
    }

    @Test
    fun `writing source removes a stale output index`() {
        val container = container()
        marker.writeOutput(container, BATCH_ID, 4)

        marker.writeSource(container, BATCH_ID)

        assertEquals(CompatibilityMarker.Source(BATCH_ID), marker.read(container))
        assertFalse(container.contains(key("compatibility-output-index")))
    }

    @Test
    fun `partial malformed or noncanonical marker is rejected and remains untouched`() {
        val malformed = container().apply { set(batchKey, PersistentDataType.STRING, "not-a-uuid") }
        val noncanonical = container().apply { set(batchKey, PersistentDataType.STRING, "0-0-0-0-101") }
        val partial = container().apply { set(roleKey, PersistentDataType.INTEGER, 1) }

        assertEquals(CompatibilityMarkerReadResult.Rejected("IncompleteMarker"), marker.read(malformed))
        assertEquals(CompatibilityMarkerReadResult.Rejected("IncompleteMarker"), marker.read(noncanonical))
        assertEquals(CompatibilityMarkerReadResult.Rejected("IncompleteMarker"), marker.read(partial))
        assertTrue(malformed.contains(batchKey))
        assertTrue(noncanonical.contains(batchKey))
        assertTrue(partial.contains(roleKey))
    }

    @Test
    fun `complete malformed batch id is rejected without mutation`() {
        val container =
            container().apply {
                set(batchKey, PersistentDataType.STRING, "not-a-uuid")
                set(roleKey, PersistentDataType.INTEGER, 1)
            }

        assertEquals(CompatibilityMarkerReadResult.Rejected("InvalidBatchId"), marker.read(container))
        assertTrue(container.contains(batchKey))
        assertTrue(container.contains(roleKey))
    }

    @Test
    fun `wrong types unknown role and invalid output index fail closed`() {
        val wrongType =
            container().apply {
                set(batchKey, PersistentDataType.LONG, 1L)
                set(roleKey, PersistentDataType.INTEGER, 1)
            }
        val unknownRole = sourceContainer(role = 99)
        val missingIndex = sourceContainer(role = 2)
        val negativeIndex = sourceContainer(role = 2).apply { set(indexKey, PersistentDataType.INTEGER, -1) }
        val sourceWithIndex = sourceContainer(role = 1).apply { set(indexKey, PersistentDataType.INTEGER, 0) }

        assertEquals(CompatibilityMarkerReadResult.Rejected("InvalidType"), marker.read(wrongType))
        assertEquals(CompatibilityMarkerReadResult.Rejected("InvalidRole"), marker.read(unknownRole))
        assertEquals(CompatibilityMarkerReadResult.Rejected("MissingOutputIndex"), marker.read(missingIndex))
        assertEquals(CompatibilityMarkerReadResult.Rejected("InvalidOutputIndex"), marker.read(negativeIndex))
        assertEquals(CompatibilityMarkerReadResult.Rejected("UnexpectedOutputIndex"), marker.read(sourceWithIndex))
    }

    @Test
    fun `clear removes keys only for the exact expected marker`() {
        val container = container()
        val expected = CompatibilityMarker.Output(BATCH_ID, 3)
        marker.writeOutput(container, BATCH_ID, 3)

        assertFalse(marker.clear(container, CompatibilityMarker.Output(BATCH_ID, 2)))
        assertEquals(expected, marker.read(container))
        assertTrue(marker.clear(container, expected))
        assertEquals(CompatibilityMarkerReadResult.Absent, marker.read(container))
        assertTrue(container.isEmpty)
    }

    private fun sourceContainer(role: Int): BukkitItemStateRepositoryTest.FakePersistentDataContainer =
        container().apply {
            set(batchKey, PersistentDataType.STRING, BATCH_ID.toString())
            set(roleKey, PersistentDataType.INTEGER, role)
        }

    private fun container(): BukkitItemStateRepositoryTest.FakePersistentDataContainer =
        BukkitItemStateRepositoryTest.FakePersistentDataContainer()

    @Suppress("DEPRECATION")
    private fun key(value: String): NamespacedKey = NamespacedKey("itemdropv2", value)

    private val batchKey = key("compatibility-batch-id")
    private val roleKey = key("compatibility-batch-role")
    private val indexKey = key("compatibility-output-index")

    private companion object {
        val BATCH_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
    }
}
