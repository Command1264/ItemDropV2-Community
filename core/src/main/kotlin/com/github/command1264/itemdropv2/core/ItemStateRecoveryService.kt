package com.github.command1264.itemdropv2.core

public class ItemStatePersistenceModeSelector {
    @Suppress("ReturnCount")
    public fun select(fingerprint: ServerFingerprint): ItemStatePersistenceMode {
        val release = MinecraftRelease.parse(fingerprint.minecraftVersion) ?: return ItemStatePersistenceMode.UNSUPPORTED
        if (fingerprint.platform == ServerPlatform.UNKNOWN || release < MINIMUM_RELEASE) {
            return ItemStatePersistenceMode.UNSUPPORTED
        }
        return if (release in AFFECTED_RELEASES) {
            ItemStatePersistenceMode.ENTITY_PDC_WITH_JOURNAL
        } else {
            ItemStatePersistenceMode.ENTITY_PDC
        }
    }

    private data class MinecraftRelease(
        val major: Int,
        val minor: Int,
        val patch: Int,
    ) : Comparable<MinecraftRelease> {
        override fun compareTo(other: MinecraftRelease): Int =
            compareValuesBy(this, other, MinecraftRelease::major, MinecraftRelease::minor, MinecraftRelease::patch)

        companion object {
            private val RELEASE = Regex("([0-9]+)\\.([0-9]+)(?:\\.([0-9]+))?")

            @Suppress("MagicNumber")
            fun parse(value: String): MinecraftRelease? {
                val match = RELEASE.matchEntire(value) ?: return null
                return try {
                    MinecraftRelease(
                        match.groupValues[1].toInt(),
                        match.groupValues[2].toInt(),
                        match.groupValues[3].ifEmpty { "0" }.toInt(),
                    )
                } catch (_: NumberFormatException) {
                    null
                }
            }
        }
    }

    private companion object {
        private val MINIMUM_RELEASE = MinecraftRelease(1, 14, 0)
        private val AFFECTED_RELEASES = MinecraftRelease(1, 14, 0)..MinecraftRelease(1, 14, 1)
    }
}

public class ItemStateRecoveryService {
    @Suppress("ReturnCount")
    public fun decide(
        pdc: ItemStateLoadResult,
        journal: ItemStateJournalRecord?,
        currentFingerprint: ItemStateJournalFingerprint,
        journalRecoveryAllowed: Boolean,
    ): ItemStateRecoveryDecision {
        if (journal == null) return withoutJournal(pdc)
        if (journal.fingerprint != currentFingerprint) return ItemStateRecoveryDecision.Conflict("FingerprintMismatch")
        if (journal.type == ItemStateJournalRecordType.TOMBSTONE) {
            return if (pdc == ItemStateLoadResult.Absent || pdc == ItemStateLoadResult.MissingTarget) {
                ItemStateRecoveryDecision.NoAction
            } else {
                ItemStateRecoveryDecision.Conflict("TombstoneEntityConflict")
            }
        }
        return when (pdc) {
            ItemStateLoadResult.Absent -> restoreOrReject(journal, journalRecoveryAllowed)
            is ItemStateLoadResult.Loaded -> compareLoaded(pdc, journal, journalRecoveryAllowed)
            is ItemStateLoadResult.Invalid -> ItemStateRecoveryDecision.Conflict("InvalidPdc")
            is ItemStateLoadResult.UnsupportedSchema -> ItemStateRecoveryDecision.Conflict("UnsupportedPdcSchema")
            is ItemStateLoadResult.Legacy -> ItemStateRecoveryDecision.Conflict("LegacyPdcConflict")
            ItemStateLoadResult.MissingTarget -> ItemStateRecoveryDecision.NoAction
            is ItemStateLoadResult.Failed -> ItemStateRecoveryDecision.Failed(pdc.errorType)
        }
    }

    private fun withoutJournal(pdc: ItemStateLoadResult): ItemStateRecoveryDecision =
        when (pdc) {
            is ItemStateLoadResult.Loaded ->
                if (pdc.revision > 0) {
                    ItemStateRecoveryDecision.PublishPdc(pdc.state, pdc.revision)
                } else {
                    ItemStateRecoveryDecision.NoAction
                }
            is ItemStateLoadResult.Invalid -> ItemStateRecoveryDecision.Conflict("InvalidPdc")
            is ItemStateLoadResult.UnsupportedSchema -> ItemStateRecoveryDecision.Conflict("UnsupportedPdcSchema")
            is ItemStateLoadResult.Failed -> ItemStateRecoveryDecision.Failed(pdc.errorType)
            else -> ItemStateRecoveryDecision.NoAction
        }

    private fun compareLoaded(
        pdc: ItemStateLoadResult.Loaded,
        journal: ItemStateJournalRecord,
        recoveryAllowed: Boolean,
    ): ItemStateRecoveryDecision =
        when {
            pdc.revision > journal.revision -> ItemStateRecoveryDecision.PublishPdc(pdc.state, pdc.revision)
            pdc.revision < journal.revision -> restoreOrReject(journal, recoveryAllowed)
            pdc.state != journal.state -> ItemStateRecoveryDecision.Conflict("EqualRevisionPayloadMismatch")
            else -> ItemStateRecoveryDecision.AlreadySynchronized(journal)
        }

    private fun restoreOrReject(
        journal: ItemStateJournalRecord,
        recoveryAllowed: Boolean,
    ): ItemStateRecoveryDecision =
        if (recoveryAllowed) {
            ItemStateRecoveryDecision.Restore(journal)
        } else {
            ItemStateRecoveryDecision.Conflict("JournalAheadOnNativePdcEndpoint")
        }
}

public sealed interface ItemStateRecoveryDecision {
    public data object NoAction : ItemStateRecoveryDecision

    public data class Restore(
        public val record: ItemStateJournalRecord,
    ) : ItemStateRecoveryDecision

    public data class PublishPdc(
        public val state: ItemState,
        public val revision: Long,
    ) : ItemStateRecoveryDecision

    public data class AlreadySynchronized(
        public val record: ItemStateJournalRecord,
    ) : ItemStateRecoveryDecision

    public data class Conflict(
        public val reason: String,
    ) : ItemStateRecoveryDecision

    public data class Failed(
        public val errorType: String,
    ) : ItemStateRecoveryDecision
}
