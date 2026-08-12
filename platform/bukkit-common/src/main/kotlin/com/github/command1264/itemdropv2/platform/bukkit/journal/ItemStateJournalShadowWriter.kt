package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateDurabilityPort
import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import com.github.command1264.itemdropv2.core.RevisionedItemStateRepository
import com.github.command1264.itemdropv2.core.RevisionedItemStateWriteResult
import java.util.UUID

/**
 * Dormant J2 adapter. Runtime wiring remains intentionally absent until J3 can provide validated
 * Bukkit identity, fingerprint, chunk and presentation snapshots.
 */
public class ItemStateJournalShadowWriter(
    private val repository: RevisionedItemStateRepository,
    private val durability: ItemStateDurabilityPort,
    private val snapshotFactory: (UUID, ItemState, Long) -> ItemStateJournalRecord,
) {
    public fun save(
        entityId: UUID,
        state: ItemState,
    ): ItemStateJournalShadowWriteResult =
        when (val primary = repository.saveRevisioned(entityId, state)) {
            is RevisionedItemStateWriteResult.Applied -> stageSnapshot(entityId, state, primary.revision)
            RevisionedItemStateWriteResult.MissingTarget -> ItemStateJournalShadowWriteResult.PrimaryMissingTarget
            is RevisionedItemStateWriteResult.Rejected ->
                ItemStateJournalShadowWriteResult.PrimaryRejected(primary.reason)
            is RevisionedItemStateWriteResult.Failed -> ItemStateJournalShadowWriteResult.PrimaryFailed(primary.errorType)
        }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun stageSnapshot(
        entityId: UUID,
        state: ItemState,
        revision: Long,
    ): ItemStateJournalShadowWriteResult {
        val snapshot =
            try {
                snapshotFactory(entityId, state, revision)
            } catch (error: RuntimeException) {
                return ItemStateJournalShadowWriteResult.ShadowFailed(revision, error.javaClass.simpleName)
            }
        if (snapshot.identity.entityUuid != entityId || snapshot.revision != revision || snapshot.state != state) {
            return ItemStateJournalShadowWriteResult.ShadowRejected(revision, "SnapshotMismatch")
        }
        return ItemStateJournalShadowWriteResult.Applied(revision, durability.stage(snapshot))
    }
}

public sealed interface ItemStateJournalShadowWriteResult {
    public data class Applied(
        public val revision: Long,
        public val durability: ItemStateDurabilityOutcome,
    ) : ItemStateJournalShadowWriteResult

    public data object PrimaryMissingTarget : ItemStateJournalShadowWriteResult

    public data class PrimaryRejected(
        public val reason: String,
    ) : ItemStateJournalShadowWriteResult

    public data class PrimaryFailed(
        public val errorType: String,
    ) : ItemStateJournalShadowWriteResult

    public data class ShadowRejected(
        public val revision: Long,
        public val reason: String,
    ) : ItemStateJournalShadowWriteResult

    public data class ShadowFailed(
        public val revision: Long,
        public val errorType: String,
    ) : ItemStateJournalShadowWriteResult
}
