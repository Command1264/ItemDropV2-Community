package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateJournalChunk
import com.github.command1264.itemdropv2.core.ItemStateJournalFingerprint
import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.ItemStateJournalPresentation
import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import com.github.command1264.itemdropv2.core.RevisionedItemStateRepository
import com.github.command1264.itemdropv2.core.RevisionedItemStateWriteResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class ItemStateJournalShadowWriterTest {
    @Test
    fun `stages the exact revision only after the primary PDC write succeeds`() {
        val staged = mutableListOf<Long>()
        val writer =
            ItemStateJournalShadowWriter(
                repository = FakeRevisionedRepository(RevisionedItemStateWriteResult.Applied(7)),
                durability = { record ->
                    staged += record.revision
                    ItemStateDurabilityOutcome.Accepted(coalesced = false)
                },
                snapshotFactory = { _, _, revision -> sampleJournalUpsert(revision) },
            )

        assertEquals(
            ItemStateJournalShadowWriteResult.Applied(7, ItemStateDurabilityOutcome.Accepted(false)),
            writer.save(ENTITY_ID, ItemState(null, 0, 300)),
        )
        assertEquals(listOf(7L), staged)
    }

    @Test
    fun `does not stage when primary write fails or snapshot revision differs`() {
        var stageCalls = 0
        val durability =
            com.github.command1264.itemdropv2.core.ItemStateDurabilityPort {
                stageCalls++
                ItemStateDurabilityOutcome.Accepted(false)
            }
        val failed =
            ItemStateJournalShadowWriter(
                FakeRevisionedRepository(RevisionedItemStateWriteResult.Rejected("InvalidExistingState")),
                durability,
            ) { _, _, revision -> sampleJournalUpsert(revision) }
        val mismatched =
            ItemStateJournalShadowWriter(
                FakeRevisionedRepository(RevisionedItemStateWriteResult.Applied(3)),
                durability,
            ) { _, _, _ -> sampleJournalUpsert(4) }

        assertEquals(
            ItemStateJournalShadowWriteResult.PrimaryRejected("InvalidExistingState"),
            failed.save(ENTITY_ID, ItemState(null, 0, 300)),
        )
        assertEquals(
            ItemStateJournalShadowWriteResult.ShadowRejected(3, "SnapshotMismatch"),
            mismatched.save(ENTITY_ID, ItemState(null, 0, 300)),
        )
        assertEquals(0, stageCalls)
    }

    @Test
    fun `reports snapshot and durability failures without hiding the committed primary revision`() {
        val snapshotFailure =
            ItemStateJournalShadowWriter(
                FakeRevisionedRepository(RevisionedItemStateWriteResult.Applied(5)),
                { ItemStateDurabilityOutcome.Accepted(false) },
            ) { _, _, _ -> throw IllegalArgumentException("invalid snapshot") }
        val durabilityFailure =
            ItemStateJournalShadowWriter(
                FakeRevisionedRepository(RevisionedItemStateWriteResult.Applied(6)),
                { ItemStateDurabilityOutcome.Failed("IOException") },
            ) { _, _, revision -> sampleJournalUpsert(revision) }

        assertEquals(
            ItemStateJournalShadowWriteResult.ShadowFailed(5, "IllegalArgumentException"),
            snapshotFailure.save(ENTITY_ID, ItemState(null, 0, 300)),
        )
        assertEquals(
            ItemStateJournalShadowWriteResult.Applied(6, ItemStateDurabilityOutcome.Failed("IOException")),
            durabilityFailure.save(ENTITY_ID, ItemState(null, 0, 300)),
        )
    }

    private class FakeRevisionedRepository(
        private val result: RevisionedItemStateWriteResult,
    ) : RevisionedItemStateRepository {
        override fun saveRevisioned(
            entityId: UUID,
            state: ItemState,
        ): RevisionedItemStateWriteResult = result
    }

    private fun sampleJournalUpsert(revision: Long): ItemStateJournalRecord =
        ItemStateJournalRecord.upsert(
            identity = ItemStateJournalIdentity(WORLD_ID, ENTITY_ID),
            revision = revision,
            chunk = ItemStateJournalChunk(0, 0),
            sessionId = SESSION_ID,
            fingerprint = ItemStateJournalFingerprint("minecraft:diamond", "ab".repeat(32)),
            state = ItemState(null, 0, 300),
            presentation = ItemStateJournalPresentation(null, false, null, false),
        )

    private companion object {
        private val ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000502")
        private val WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000501")
        private val SESSION_ID = UUID.fromString("00000000-0000-0000-0000-000000000503")
    }
}
