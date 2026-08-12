package com.github.command1264.itemdropv2.core

import java.util.UUID

public data class BlockDropOwnershipRequest(
    public val entityId: UUID,
    public val worldName: String,
    public val ownerUuid: UUID,
) {
    init {
        require(worldName.isNotBlank()) { "worldName must not be blank" }
        require(worldName.none(Char::isISOControl)) { "worldName must not contain control characters" }
    }
}

public enum class BlockDropOwnershipIgnoredReason {
    DISABLED,
    BLOCKED_WORLD,
}

public sealed interface BlockDropOwnershipOutcome {
    public data class Assigned(
        public val state: ItemState,
    ) : BlockDropOwnershipOutcome

    public data class Ignored(
        public val reason: BlockDropOwnershipIgnoredReason,
    ) : BlockDropOwnershipOutcome

    public data class Rejected(
        public val reason: String,
    ) : BlockDropOwnershipOutcome

    public data class Failed(
        public val errorType: String,
    ) : BlockDropOwnershipOutcome
}

public class BlockDropOwnershipService(
    repository: ItemStateRepository,
    settingsRepository: ItemDisplaySettingsRepository,
) {
    private val assignmentService = ItemOwnershipAssignmentService(repository, settingsRepository)

    public fun assign(request: BlockDropOwnershipRequest): BlockDropOwnershipOutcome =
        when (
            val outcome =
                assignmentService.assign(
                    ItemOwnershipAssignmentRequest(request.entityId, request.worldName, listOf(request.ownerUuid)),
                )
        ) {
            is ItemOwnershipAssignmentOutcome.Assigned -> BlockDropOwnershipOutcome.Assigned(outcome.state)
            is ItemOwnershipAssignmentOutcome.Ignored ->
                BlockDropOwnershipOutcome.Ignored(
                    when (outcome.reason) {
                        ItemOwnershipAssignmentIgnoredReason.DISABLED -> BlockDropOwnershipIgnoredReason.DISABLED
                        ItemOwnershipAssignmentIgnoredReason.BLOCKED_WORLD -> BlockDropOwnershipIgnoredReason.BLOCKED_WORLD
                    },
                )
            is ItemOwnershipAssignmentOutcome.Rejected -> BlockDropOwnershipOutcome.Rejected(outcome.reason)
            is ItemOwnershipAssignmentOutcome.Failed -> BlockDropOwnershipOutcome.Failed(outcome.errorType)
        }

    public fun reconcile(
        entityId: UUID,
        state: ItemState,
    ): BlockDropOwnershipOutcome =
        when (val outcome = assignmentService.reconcile(entityId, state)) {
            is ItemOwnershipAssignmentOutcome.Assigned -> BlockDropOwnershipOutcome.Assigned(outcome.state)
            is ItemOwnershipAssignmentOutcome.Ignored ->
                BlockDropOwnershipOutcome.Ignored(
                    when (outcome.reason) {
                        ItemOwnershipAssignmentIgnoredReason.DISABLED -> BlockDropOwnershipIgnoredReason.DISABLED
                        ItemOwnershipAssignmentIgnoredReason.BLOCKED_WORLD -> BlockDropOwnershipIgnoredReason.BLOCKED_WORLD
                    },
                )
            is ItemOwnershipAssignmentOutcome.Rejected -> BlockDropOwnershipOutcome.Rejected(outcome.reason)
            is ItemOwnershipAssignmentOutcome.Failed -> BlockDropOwnershipOutcome.Failed(outcome.errorType)
        }
}
