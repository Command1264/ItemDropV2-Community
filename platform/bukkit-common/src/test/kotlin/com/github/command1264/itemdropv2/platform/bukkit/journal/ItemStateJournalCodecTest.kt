package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemOwnership
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateJournalChunk
import com.github.command1264.itemdropv2.core.ItemStateJournalFingerprint
import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.ItemStateJournalPresentation
import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import com.github.command1264.itemdropv2.core.VirtualItemAmount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.CRC32

class ItemStateJournalCodecTest {
    private val codec = ItemStateJournalCodec()

    @Test
    fun `round trips upserts and tombstones`() {
        val upsert = sampleJournalUpsert(revision = 41)
        val tombstone = upsert.asTombstone(revision = 42)
        val bytes = ByteArrayOutputStream().also { output -> codec.write(output, listOf(upsert, tombstone)) }.toByteArray()

        val loaded = assertInstanceOf(JournalDecodeResult.Loaded::class.java, codec.read(bytes.inputStream()))

        assertEquals(listOf(upsert, tombstone), loaded.records)
        assertEquals(bytes.size.toLong(), loaded.validBytes)
        assertEquals(false, loaded.truncatedTail)
    }

    @Test
    fun `stops at a torn tail after the last complete record`() {
        val first = sampleJournalUpsert(revision = 1)
        val second = sampleJournalUpsert(revision = 2)
        val complete = ByteArrayOutputStream().also { codec.write(it, listOf(first)) }.toByteArray()
        val secondBytes = ByteArrayOutputStream().also { codec.write(it, listOf(second)) }.toByteArray()
        val torn = complete + secondBytes.copyOf(secondBytes.size - 5)

        val loaded = assertInstanceOf(JournalDecodeResult.Loaded::class.java, codec.read(torn.inputStream()))

        assertEquals(listOf(first), loaded.records)
        assertEquals(complete.size.toLong(), loaded.validBytes)
        assertTrue(loaded.truncatedTail)
    }

    @Test
    fun `rejects checksum damage unknown versions and oversized frames`() {
        val valid = ByteArrayOutputStream().also { codec.write(it, listOf(sampleJournalUpsert())) }.toByteArray()
        val checksumDamage = valid.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertInstanceOf(JournalDecodeResult.Invalid::class.java, codec.read(checksumDamage.inputStream()))

        val unknownVersion = valid.copyOf().also { it[5] = 2 }
        assertInstanceOf(JournalDecodeResult.Invalid::class.java, codec.read(unknownVersion.inputStream()))

        val malformedUtf8 = valid.copyOf()
        val managedNameOffset = malformedUtf8.indexOf("Diamond Sword x8192".toByteArray(Charsets.UTF_8))
        malformedUtf8[managedNameOffset] = 0xc3.toByte()
        val payloadLength = ByteBuffer.wrap(malformedUtf8, 7, Int.SIZE_BYTES).int
        val checksum = CRC32().apply { update(malformedUtf8, 11, payloadLength) }.value.toInt()
        ByteBuffer.wrap(malformedUtf8, 11 + payloadLength, Int.SIZE_BYTES).putInt(checksum)
        assertInstanceOf(JournalDecodeResult.Invalid::class.java, codec.read(malformedUtf8.inputStream()))

        val oversized =
            valid.copyOf().also {
                it[7] = 0x7f
                it[8] = 0xff.toByte()
                it[9] = 0xff.toByte()
                it[10] = 0xff.toByte()
            }
        assertInstanceOf(JournalDecodeResult.Invalid::class.java, codec.read(oversized.inputStream()))
    }

    @Test
    fun `rejects input beyond the configured record bound`() {
        val bytes =
            ByteArrayOutputStream()
                .also {
                    codec.write(it, listOf(sampleJournalUpsert(1), sampleJournalUpsert(2)))
                }.toByteArray()

        val invalid = assertInstanceOf(JournalDecodeResult.Invalid::class.java, codec.read(bytes.inputStream(), 1))

        assertEquals("journal record count exceeds supported limit", invalid.reason)
    }
}

private fun ByteArray.indexOf(needle: ByteArray): Int {
    for (start in 0..size - needle.size) {
        if (needle.indices.all { this[start + it] == needle[it] }) return start
    }
    error("test fixture sequence was not found")
}

internal fun sampleJournalUpsert(
    revision: Long = 1,
    entityUuid: UUID = UUID.fromString("00000000-0000-0000-0000-000000000502"),
): ItemStateJournalRecord =
    ItemStateJournalRecord.upsert(
        identity =
            ItemStateJournalIdentity(
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                entityUuid,
            ),
        revision = revision,
        chunk = ItemStateJournalChunk(-12, 34),
        sessionId = UUID.fromString("00000000-0000-0000-0000-000000000503"),
        fingerprint = ItemStateJournalFingerprint("minecraft:diamond_sword", "ab".repeat(32)),
        state =
            ItemState(
                ownership =
                    ItemOwnership(
                        UUID.fromString("00000000-0000-0000-0000-000000000504"),
                        protectionSecondsRemaining = 17,
                    ),
                elapsedLifetimeSeconds = 3,
                originalLifetimeSeconds = 300,
                virtualAmount = VirtualItemAmount.of(8192),
            ),
        presentation = ItemStateJournalPresentation("Diamond Sword x8192", true, "Original Sword", true),
    )
