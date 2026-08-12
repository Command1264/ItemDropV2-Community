package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.ItemStateJournalRecord

public class ItemStateJournalIndex {
    private val latest = linkedMapOf<ItemStateJournalIdentity, ItemStateJournalRecord>()

    public operator fun get(identity: ItemStateJournalIdentity): ItemStateJournalRecord? = latest[identity]

    public fun apply(record: ItemStateJournalRecord): JournalIndexUpdate {
        val existing = latest[record.identity]
        if (existing == null) {
            latest[record.identity] = record
            return JournalIndexUpdate.Applied(record)
        }
        return when {
            record.revision < existing.revision -> JournalIndexUpdate.IgnoredOlder(existing.revision)
            record.revision > existing.revision -> {
                latest[record.identity] = record
                JournalIndexUpdate.Replaced(existing.revision, record)
            }
            record == existing -> JournalIndexUpdate.Duplicate(record.revision)
            record.isPresentationRefreshOf(existing) -> {
                latest[record.identity] = record
                JournalIndexUpdate.PresentationRefreshed(record)
            }
            else -> JournalIndexUpdate.Conflict(record.identity, record.revision)
        }
    }

    public fun snapshot(): List<ItemStateJournalRecord> = latest.values.sortedWith(RECORD_ORDER)

    private companion object {
        private val RECORD_ORDER =
            compareBy<ItemStateJournalRecord>(
                { it.identity.worldUuid.toString() },
                { it.identity.entityUuid.toString() },
            )
    }
}

public sealed interface JournalIndexUpdate {
    public data class Applied(
        public val record: ItemStateJournalRecord,
    ) : JournalIndexUpdate

    public data class Replaced(
        public val previousRevision: Long,
        public val record: ItemStateJournalRecord,
    ) : JournalIndexUpdate

    public data class Duplicate(
        public val revision: Long,
    ) : JournalIndexUpdate

    public data class PresentationRefreshed(
        public val record: ItemStateJournalRecord,
    ) : JournalIndexUpdate

    public data class IgnoredOlder(
        public val currentRevision: Long,
    ) : JournalIndexUpdate

    public data class Conflict(
        public val identity: ItemStateJournalIdentity,
        public val revision: Long,
    ) : JournalIndexUpdate
}
