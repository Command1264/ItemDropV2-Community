package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ItemStateRecoveryServiceTest {
    private val service = ItemStateRecoveryService()
    private val record = sampleJournalUpsert(revision = 4)
    private val state = requireNotNull(record.state)

    @Test
    fun `restores a matching journal upsert when affected Entity PDC is absent or older`() {
        assertEquals(
            ItemStateRecoveryDecision.Restore(record),
            service.decide(ItemStateLoadResult.Absent, record, record.fingerprint, journalRecoveryAllowed = true),
        )
        assertEquals(
            ItemStateRecoveryDecision.Restore(record),
            service.decide(
                ItemStateLoadResult.Loaded(state.copy(elapsedLifetimeSeconds = 1), revision = 3),
                record,
                record.fingerprint,
                journalRecoveryAllowed = true,
            ),
        )
    }

    @Test
    fun `keeps newer PDC authoritative and accepts an identical revision`() {
        val newer = ItemStateLoadResult.Loaded(state.copy(elapsedLifetimeSeconds = 9), revision = 5)

        assertEquals(
            ItemStateRecoveryDecision.PublishPdc(newer.state, 5),
            service.decide(newer, record, record.fingerprint, journalRecoveryAllowed = true),
        )
        assertEquals(
            ItemStateRecoveryDecision.AlreadySynchronized(record),
            service.decide(ItemStateLoadResult.Loaded(state, revision = 4), record, record.fingerprint, true),
        )
    }

    @Test
    fun `fails closed for fingerprint payload tombstone and unsupported state conflicts`() {
        val mismatch = ItemStateJournalFingerprint("minecraft:stone", "cd".repeat(32))
        val tombstone = record.asTombstone(5)

        assertEquals(
            ItemStateRecoveryDecision.Conflict("FingerprintMismatch"),
            service.decide(ItemStateLoadResult.Absent, record, mismatch, true),
        )
        assertEquals(
            ItemStateRecoveryDecision.Conflict("EqualRevisionPayloadMismatch"),
            service.decide(
                ItemStateLoadResult.Loaded(state.copy(elapsedLifetimeSeconds = 8), revision = 4),
                record,
                record.fingerprint,
                true,
            ),
        )
        assertEquals(
            ItemStateRecoveryDecision.Conflict("TombstoneEntityConflict"),
            service.decide(ItemStateLoadResult.Loaded(state, revision = 4), tombstone, record.fingerprint, true),
        )
        assertEquals(
            ItemStateRecoveryDecision.Conflict("InvalidPdc"),
            service.decide(ItemStateLoadResult.Invalid(listOf("broken")), record, record.fingerprint, true),
        )
    }

    @Test
    fun `never restores a newer journal on an unaffected endpoint`() {
        assertEquals(
            ItemStateRecoveryDecision.Conflict("JournalAheadOnNativePdcEndpoint"),
            service.decide(ItemStateLoadResult.Absent, record, record.fingerprint, journalRecoveryAllowed = false),
        )
    }
}
