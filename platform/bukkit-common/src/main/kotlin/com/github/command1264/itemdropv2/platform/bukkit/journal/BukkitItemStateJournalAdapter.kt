package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.DirectItemPresentationJournalView
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateJournalChunk
import com.github.command1264.itemdropv2.core.ItemStateJournalFingerprint
import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.ItemStateJournalPresentation
import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import com.github.command1264.itemdropv2.core.PresentationJournalSnapshotResult
import com.github.command1264.itemdropv2.platform.bukkit.ItemStateRevisionDurabilityGate
import org.bukkit.entity.Item
import java.util.UUID

public class BukkitItemStateJournalAdapter(
    private val runtime: ItemStateJournalRuntimeAccess,
    private val presentation: DirectItemPresentationJournalView<Item>,
    private val fingerprintFactory: BukkitItemStateJournalFingerprintFactory = BukkitItemStateJournalFingerprintFactory(),
    private val sessionId: UUID = UUID.randomUUID(),
) : ItemStateRevisionDurabilityGate,
    BukkitItemStateJournalEntityPort {
    init {
        require(sessionId != UUID(0, 0)) { "journal session UUID must not be nil" }
    }

    override fun prepare(
        item: Item,
        state: ItemState,
        revision: Long,
    ): ItemStateDurabilityOutcome = publish(item, state, revision)

    override fun latestRevision(item: Item): Long? = runtime[ItemStateJournalIdentity(item.world.uid, item.uniqueId)]?.revision

    override fun discard(item: Item): ItemStateDurabilityOutcome = runtime.discard(ItemStateJournalIdentity(item.world.uid, item.uniqueId))

    public fun refreshPresentation(
        item: Item,
        state: ItemState,
        revision: Long,
    ): ItemStateDurabilityOutcome =
        createRecord(item, state, revision).let { result ->
            when (result) {
                is JournalRecordCreation.Created -> runtime.refreshPresentation(result.record)
                is JournalRecordCreation.Failed -> result.outcome
            }
        }

    @Suppress("ReturnCount")
    override fun publish(
        item: Item,
        state: ItemState,
        revision: Long,
    ): ItemStateDurabilityOutcome =
        createRecord(item, state, revision).let { result ->
            when (result) {
                is JournalRecordCreation.Created -> runtime.stage(result.record)
                is JournalRecordCreation.Failed -> result.outcome
            }
        }

    @Suppress("ReturnCount")
    private fun createRecord(
        item: Item,
        state: ItemState,
        revision: Long,
    ): JournalRecordCreation {
        val fingerprint = fingerprint(item)
        if (fingerprint is BukkitItemStateJournalFingerprintResult.Failed) {
            return JournalRecordCreation.Failed(ItemStateDurabilityOutcome.Failed("Fingerprint:${fingerprint.errorType}"))
        }
        val presentationSnapshot = presentation.capture(item)
        if (presentationSnapshot !is PresentationJournalSnapshotResult.Captured) {
            return JournalRecordCreation.Failed(presentationSnapshot.toDurabilityFailure())
        }
        val location = item.location
        val record =
            ItemStateJournalRecord.upsert(
                identity = ItemStateJournalIdentity(item.world.uid, item.uniqueId),
                revision = revision,
                chunk = ItemStateJournalChunk(location.blockX shr CHUNK_SHIFT, location.blockZ shr CHUNK_SHIFT),
                sessionId = sessionId,
                fingerprint = (fingerprint as BukkitItemStateJournalFingerprintResult.Created).fingerprint,
                state = state,
                presentation = presentationSnapshot.snapshot,
            )
        return JournalRecordCreation.Created(record)
    }

    override fun fingerprint(item: Item): BukkitItemStateJournalFingerprintResult = fingerprintFactory.create(item)

    override fun restorePresentation(
        item: Item,
        snapshot: ItemStateJournalPresentation,
    ): PresentationJournalSnapshotResult = presentation.restore(item, snapshot)

    private companion object {
        private const val CHUNK_SHIFT = 4
    }

    private sealed interface JournalRecordCreation {
        data class Created(
            val record: ItemStateJournalRecord,
        ) : JournalRecordCreation

        data class Failed(
            val outcome: ItemStateDurabilityOutcome,
        ) : JournalRecordCreation
    }
}

public interface BukkitItemStateJournalEntityPort {
    public fun publish(
        item: Item,
        state: ItemState,
        revision: Long,
    ): ItemStateDurabilityOutcome

    public fun fingerprint(item: Item): BukkitItemStateJournalFingerprintResult

    public fun restorePresentation(
        item: Item,
        snapshot: ItemStateJournalPresentation,
    ): PresentationJournalSnapshotResult
}

private fun PresentationJournalSnapshotResult.toDurabilityFailure(): ItemStateDurabilityOutcome =
    when (this) {
        is PresentationJournalSnapshotResult.Rejected -> ItemStateDurabilityOutcome.Rejected("Presentation:$reason")
        is PresentationJournalSnapshotResult.Failed -> ItemStateDurabilityOutcome.Failed("Presentation:$errorType")
        PresentationJournalSnapshotResult.MissingTarget -> ItemStateDurabilityOutcome.Failed("Presentation:MissingTarget")
        PresentationJournalSnapshotResult.Applied -> ItemStateDurabilityOutcome.Failed("Presentation:UnexpectedApplied")
        is PresentationJournalSnapshotResult.Captured -> error("captured presentation is not a failure")
    }
