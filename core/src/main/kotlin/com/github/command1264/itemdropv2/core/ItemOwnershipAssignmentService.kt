package com.github.command1264.itemdropv2.core

import java.util.UUID

public data class ItemOwnershipAssignmentRequest(
    public val entityId: UUID,
    public val worldName: String,
    public val eligibleOwnerUuids: List<UUID>,
) {
    init {
        require(worldName.isNotBlank()) { "worldName must not be blank" }
        require(worldName.none(Char::isISOControl)) { "worldName must not contain control characters" }
        require(eligibleOwnerUuids.isNotEmpty()) { "eligible owners must not be empty" }
        require(eligibleOwnerUuids.size <= ItemOwnership.MAX_ELIGIBLE_OWNERS) { "eligible owners exceed supported count" }
        require(eligibleOwnerUuids.distinct().size == eligibleOwnerUuids.size) { "eligible owners must be unique" }
    }
}

public enum class ItemOwnershipAssignmentIgnoredReason {
    DISABLED,
    BLOCKED_WORLD,
}

public sealed interface ItemOwnershipAssignmentOutcome {
    public data class Assigned(
        public val state: ItemState,
    ) : ItemOwnershipAssignmentOutcome

    public data class Ignored(
        public val reason: ItemOwnershipAssignmentIgnoredReason,
    ) : ItemOwnershipAssignmentOutcome

    public data class Rejected(
        public val reason: String,
    ) : ItemOwnershipAssignmentOutcome

    public data class Failed(
        public val errorType: String,
    ) : ItemOwnershipAssignmentOutcome
}

public class ItemOwnershipAssignmentService(
    private val repository: ItemStateRepository,
    private val settingsRepository: ItemDisplaySettingsRepository,
) {
    public fun assign(request: ItemOwnershipAssignmentRequest): ItemOwnershipAssignmentOutcome {
        val settings = settingsRepository.settings()
        val ignoredReason =
            when {
                !settings.ownership.enabled -> ItemOwnershipAssignmentIgnoredReason.DISABLED
                request.worldName in settings.blockedWorlds -> ItemOwnershipAssignmentIgnoredReason.BLOCKED_WORLD
                else -> null
            }
        return ignoredReason
            ?.let(ItemOwnershipAssignmentOutcome::Ignored)
            ?: prepareState(request.entityId).let { prepared ->
                when (prepared) {
                    is PreparedAssignmentState.Ready -> save(request, prepared.state, settings.ownership.protectionSeconds)
                    is PreparedAssignmentState.Rejected -> ItemOwnershipAssignmentOutcome.Rejected(prepared.reason)
                    is PreparedAssignmentState.Failed -> ItemOwnershipAssignmentOutcome.Failed(prepared.errorType)
                }
            }
    }

    public fun reconcile(
        entityId: UUID,
        state: ItemState,
    ): ItemOwnershipAssignmentOutcome = persist(entityId, state)

    private fun prepareState(entityId: UUID): PreparedAssignmentState =
        when (val loaded = repository.load(entityId)) {
            ItemStateLoadResult.Absent -> PreparedAssignmentState.Ready(ItemState(null, remainingLifetimeSeconds = null))
            is ItemStateLoadResult.Loaded -> PreparedAssignmentState.Ready(loaded.state)
            is ItemStateLoadResult.Legacy -> PreparedAssignmentState.Rejected("LegacyStateRequiresMigration")
            is ItemStateLoadResult.Invalid -> PreparedAssignmentState.Rejected("InvalidExistingState")
            is ItemStateLoadResult.UnsupportedSchema ->
                PreparedAssignmentState.Rejected("UnsupportedSchema:${loaded.actualVersion}")
            ItemStateLoadResult.MissingTarget -> PreparedAssignmentState.Failed("MissingTarget")
            is ItemStateLoadResult.Failed -> PreparedAssignmentState.Failed(loaded.errorType)
        }

    private fun save(
        request: ItemOwnershipAssignmentRequest,
        state: ItemState,
        protectionSeconds: Long,
    ): ItemOwnershipAssignmentOutcome {
        val ownership =
            protectionSeconds
                .takeIf { it > 0 }
                ?.let { seconds ->
                    ItemOwnership(
                        ownerUuid = request.eligibleOwnerUuids.first(),
                        protectionSecondsRemaining = seconds,
                        eligibleOwnerUuids = request.eligibleOwnerUuids,
                    )
                }
        return persist(request.entityId, state.copy(ownership = ownership))
    }

    private fun persist(
        entityId: UUID,
        state: ItemState,
    ): ItemOwnershipAssignmentOutcome =
        when (val result = repository.save(entityId, state)) {
            ItemStateWriteResult.Applied -> ItemOwnershipAssignmentOutcome.Assigned(state)
            ItemStateWriteResult.MissingTarget -> ItemOwnershipAssignmentOutcome.Failed("MissingTarget")
            is ItemStateWriteResult.Rejected -> ItemOwnershipAssignmentOutcome.Rejected(result.reason)
            is ItemStateWriteResult.Failed -> ItemOwnershipAssignmentOutcome.Failed(result.errorType)
        }
}

private sealed interface PreparedAssignmentState {
    data class Ready(
        val state: ItemState,
    ) : PreparedAssignmentState

    data class Rejected(
        val reason: String,
    ) : PreparedAssignmentState

    data class Failed(
        val errorType: String,
    ) : PreparedAssignmentState
}
