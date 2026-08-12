package com.github.command1264.itemdropv2.core

import java.util.UUID

public enum class ItemStatePersistenceMode {
    ENTITY_PDC,
    ENTITY_PDC_WITH_JOURNAL,
    JOURNAL_ONLY,
    UNSUPPORTED,
}

public data class ItemStateJournalIdentity(
    public val worldUuid: UUID,
    public val entityUuid: UUID,
) {
    init {
        require(worldUuid != ZERO_UUID) { "journal world UUID must not be nil" }
        require(entityUuid != ZERO_UUID) { "journal entity UUID must not be nil" }
    }

    private companion object {
        private val ZERO_UUID = UUID(0, 0)
    }
}

public data class ItemStateJournalChunk(
    public val x: Int,
    public val z: Int,
)

public data class ItemStateJournalFingerprint(
    public val materialKey: String,
    public val metadataSha256: String,
) {
    init {
        require(MATERIAL_KEY.matches(materialKey)) { "material key must be a canonical namespaced key" }
        require(SHA_256.matches(metadataSha256)) { "metadata fingerprint must be lowercase SHA-256 hex" }
    }

    private companion object {
        private val MATERIAL_KEY = Regex("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,192}")
        private val SHA_256 = Regex("[0-9a-f]{64}")
    }
}

public data class ItemStateJournalPresentation(
    public val managedName: String?,
    public val originalNamePresent: Boolean,
    public val originalName: String?,
    public val customNameVisible: Boolean,
) {
    init {
        require(originalNamePresent || originalName == null) {
            "original name cannot have a value when the presence guard is false"
        }
        require(managedName == null || managedName.length <= MAX_NAME_CHARACTERS) {
            "managed name exceeds supported length"
        }
        require(originalName == null || originalName.length <= MAX_NAME_CHARACTERS) {
            "original name exceeds supported length"
        }
    }

    public companion object {
        public const val MAX_NAME_CHARACTERS: Int = 4096
    }
}

public interface DirectItemPresentationJournalView<T : Any> {
    public fun capture(target: T): PresentationJournalSnapshotResult

    public fun restore(
        target: T,
        snapshot: ItemStateJournalPresentation,
    ): PresentationJournalSnapshotResult
}

public sealed interface PresentationJournalSnapshotResult {
    public data class Captured(
        public val snapshot: ItemStateJournalPresentation,
    ) : PresentationJournalSnapshotResult

    public data object Applied : PresentationJournalSnapshotResult

    public data object MissingTarget : PresentationJournalSnapshotResult

    public data class Rejected(
        public val reason: String,
    ) : PresentationJournalSnapshotResult

    public data class Failed(
        public val errorType: String,
    ) : PresentationJournalSnapshotResult
}

public enum class ItemStateJournalRecordType {
    UPSERT,
    TOMBSTONE,
}

@ConsistentCopyVisibility
public data class ItemStateJournalRecord private constructor(
    public val identity: ItemStateJournalIdentity,
    public val revision: Long,
    public val chunk: ItemStateJournalChunk,
    public val sessionId: UUID,
    public val fingerprint: ItemStateJournalFingerprint,
    public val type: ItemStateJournalRecordType,
    public val state: ItemState?,
    public val presentation: ItemStateJournalPresentation?,
) {
    init {
        require(revision > 0) { "journal revision must be positive" }
        require(sessionId != ZERO_UUID) { "journal session UUID must not be nil" }
        require(
            (type == ItemStateJournalRecordType.UPSERT && state != null && presentation != null) ||
                (type == ItemStateJournalRecordType.TOMBSTONE && state == null && presentation == null),
        ) { "journal payload must match record type" }
    }

    public fun asTombstone(revision: Long): ItemStateJournalRecord = tombstone(identity, revision, chunk, sessionId, fingerprint)

    public fun withPresentation(
        presentation: ItemStateJournalPresentation,
        chunk: ItemStateJournalChunk = this.chunk,
        sessionId: UUID = this.sessionId,
    ): ItemStateJournalRecord {
        require(type == ItemStateJournalRecordType.UPSERT) { "only an upsert can refresh presentation" }
        return upsert(identity, revision, chunk, sessionId, fingerprint, requireNotNull(state), presentation)
    }

    public fun isPresentationRefreshOf(existing: ItemStateJournalRecord): Boolean =
        type == ItemStateJournalRecordType.UPSERT &&
            existing.type == ItemStateJournalRecordType.UPSERT &&
            identity == existing.identity &&
            revision == existing.revision &&
            fingerprint == existing.fingerprint &&
            state == existing.state

    public companion object {
        private val ZERO_UUID = UUID(0, 0)

        public fun upsert(
            identity: ItemStateJournalIdentity,
            revision: Long,
            chunk: ItemStateJournalChunk,
            sessionId: UUID,
            fingerprint: ItemStateJournalFingerprint,
            state: ItemState,
            presentation: ItemStateJournalPresentation,
        ): ItemStateJournalRecord =
            ItemStateJournalRecord(
                identity,
                revision,
                chunk,
                sessionId,
                fingerprint,
                ItemStateJournalRecordType.UPSERT,
                state,
                presentation,
            )

        public fun tombstone(
            identity: ItemStateJournalIdentity,
            revision: Long,
            chunk: ItemStateJournalChunk,
            sessionId: UUID,
            fingerprint: ItemStateJournalFingerprint,
        ): ItemStateJournalRecord =
            ItemStateJournalRecord(
                identity,
                revision,
                chunk,
                sessionId,
                fingerprint,
                ItemStateJournalRecordType.TOMBSTONE,
                null,
                null,
            )
    }
}

public fun interface ItemStateDurabilityPort {
    public fun stage(record: ItemStateJournalRecord): ItemStateDurabilityOutcome
}

public sealed interface ItemStateDurabilityOutcome {
    public data class Accepted(
        public val coalesced: Boolean,
    ) : ItemStateDurabilityOutcome

    public data class Rejected(
        public val reason: String,
    ) : ItemStateDurabilityOutcome

    public data class Failed(
        public val errorType: String,
    ) : ItemStateDurabilityOutcome
}
