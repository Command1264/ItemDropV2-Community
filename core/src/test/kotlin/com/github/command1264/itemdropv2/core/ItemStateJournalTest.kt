package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class ItemStateJournalTest {
    @Test
    fun `accepts immutable upsert and tombstone records`() {
        val upsert = sampleJournalUpsert(revision = 7)
        val tombstone =
            ItemStateJournalRecord.tombstone(
                identity = upsert.identity,
                revision = 8,
                chunk = upsert.chunk,
                sessionId = upsert.sessionId,
                fingerprint = upsert.fingerprint,
            )

        assertEquals(ItemStateJournalRecordType.UPSERT, upsert.type)
        assertEquals(ItemStateJournalRecordType.TOMBSTONE, tombstone.type)
        assertEquals(null, tombstone.state)
        assertEquals(null, tombstone.presentation)
    }

    @Test
    fun `rejects invalid identity revision and fingerprint boundaries`() {
        assertThrows(IllegalArgumentException::class.java) {
            ItemStateJournalFingerprint("DIAMOND", "0".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ItemStateJournalFingerprint("minecraft:diamond", "xyz")
        }
        assertThrows(IllegalArgumentException::class.java) {
            sampleJournalUpsert(revision = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ItemStateJournalIdentity(UUID(0, 0), UUID.fromString("00000000-0000-0000-0000-000000000502"))
        }
        val valid = sampleJournalUpsert()
        assertThrows(IllegalArgumentException::class.java) {
            ItemStateJournalRecord.upsert(
                valid.identity,
                valid.revision,
                valid.chunk,
                UUID(0, 0),
                valid.fingerprint,
                requireNotNull(valid.state),
                requireNotNull(valid.presentation),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ItemStateJournalPresentation(
                managedName = null,
                originalNamePresent = false,
                originalName = "unexpected",
                customNameVisible = true,
            )
        }
    }
}

internal fun sampleJournalUpsert(revision: Long = 1): ItemStateJournalRecord =
    ItemStateJournalRecord.upsert(
        identity =
            ItemStateJournalIdentity(
                worldUuid = UUID.fromString("00000000-0000-0000-0000-000000000501"),
                entityUuid = UUID.fromString("00000000-0000-0000-0000-000000000502"),
            ),
        revision = revision,
        chunk = ItemStateJournalChunk(x = -12, z = 34),
        sessionId = UUID.fromString("00000000-0000-0000-0000-000000000503"),
        fingerprint =
            ItemStateJournalFingerprint(
                materialKey = "minecraft:diamond_sword",
                metadataSha256 = "ab".repeat(32),
            ),
        state =
            ItemState(
                ownership =
                    ItemOwnership(
                        ownerUuid = UUID.fromString("00000000-0000-0000-0000-000000000504"),
                        protectionSecondsRemaining = 17,
                    ),
                elapsedLifetimeSeconds = 3,
                originalLifetimeSeconds = 300,
                virtualAmount = VirtualItemAmount.of(8192),
            ),
        presentation =
            ItemStateJournalPresentation(
                managedName = "Diamond Sword x8192",
                originalNamePresent = true,
                originalName = "Original Sword",
                customNameVisible = true,
            ),
    )
