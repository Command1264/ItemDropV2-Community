package com.github.command1264.itemdropv2.core

import java.util.UUID

public enum class ItemMergeRejection { OWNER_MISMATCH, LEGACY_STATE, INVALID_STATE, UNSUPPORTED_SCHEMA }

public sealed interface ItemMergeOutcome {
    public data object Untracked : ItemMergeOutcome

    public data class Merged(
        public val state: ItemState,
    ) : ItemMergeOutcome

    public data class Rejected(
        public val reason: ItemMergeRejection,
    ) : ItemMergeOutcome

    public data class Failed(
        public val errorType: String,
    ) : ItemMergeOutcome
}

public class ItemMergeService(
    private val repository: ItemStateRepository,
    private val settingsRepository: ItemDisplaySettingsRepository,
) {
    @Suppress("ReturnCount")
    public fun merge(
        sourceEntityId: UUID,
        targetEntityId: UUID,
    ): ItemMergeOutcome {
        val settings = settingsRepository.settings()
        val source =
            when (val loaded = load(sourceEntityId)) {
                is MergeStateLoad.Ready -> loaded
                is MergeStateLoad.Stopped -> return loaded.outcome
            }
        val target =
            when (val loaded = load(targetEntityId)) {
                is MergeStateLoad.Ready -> loaded
                is MergeStateLoad.Stopped -> return loaded.outcome
            }
        return mergeReady(source, target, targetEntityId, settings)
    }

    private fun mergeReady(
        source: MergeStateLoad.Ready,
        target: MergeStateLoad.Ready,
        targetEntityId: UUID,
        settings: ItemDisplaySettings,
    ): ItemMergeOutcome =
        when {
            !ownershipMatches(source.state.ownership, target.state.ownership) ->
                ItemMergeOutcome.Rejected(ItemMergeRejection.OWNER_MISMATCH)
            source.wasAbsent && target.wasAbsent -> ItemMergeOutcome.Untracked
            else -> mergeCompatible(source.state, target.state, targetEntityId, settings)
        }

    private fun mergeCompatible(
        source: ItemState,
        target: ItemState,
        targetEntityId: UUID,
        settings: ItemDisplaySettings,
    ): ItemMergeOutcome {
        val selectedElapsed =
            ItemElapsedLifetimeSelector.select(
                settings.merge.lifetimeStrategy,
                source.elapsedLifetimeSeconds,
                target.elapsedLifetimeSeconds,
            )
        val ownership =
            ItemOwnershipProtectionSelector.select(
                strategy = settings.merge.ownershipStrategy,
                source = source.ownership,
                target = target.ownership,
                resetProtectionSeconds = settings.ownership.protectionSeconds,
            )
        val merged =
            target.copy(
                ownership = ownership,
                elapsedLifetimeSeconds = selectedElapsed.coerceBeforeExpiry(target.originalLifetimeSeconds),
            )
        return when (val saved = repository.save(targetEntityId, merged)) {
            ItemStateWriteResult.Applied -> ItemMergeOutcome.Merged(merged)
            ItemStateWriteResult.MissingTarget -> ItemMergeOutcome.Failed("MissingTarget")
            is ItemStateWriteResult.Rejected -> ItemMergeOutcome.Failed(saved.reason)
            is ItemStateWriteResult.Failed -> ItemMergeOutcome.Failed(saved.errorType)
        }
    }

    private fun load(entityId: UUID): MergeStateLoad =
        when (val result = repository.load(entityId)) {
            ItemStateLoadResult.Absent -> MergeStateLoad.Ready(ItemState(null, null), wasAbsent = true)
            is ItemStateLoadResult.Loaded -> MergeStateLoad.Ready(result.state, wasAbsent = false)
            is ItemStateLoadResult.Legacy -> stopped(ItemMergeRejection.LEGACY_STATE)
            is ItemStateLoadResult.Invalid -> stopped(ItemMergeRejection.INVALID_STATE)
            is ItemStateLoadResult.UnsupportedSchema -> stopped(ItemMergeRejection.UNSUPPORTED_SCHEMA)
            ItemStateLoadResult.MissingTarget -> MergeStateLoad.Stopped(ItemMergeOutcome.Failed("MissingTarget"))
            is ItemStateLoadResult.Failed -> MergeStateLoad.Stopped(ItemMergeOutcome.Failed(result.errorType))
        }

    private fun stopped(reason: ItemMergeRejection): MergeStateLoad = MergeStateLoad.Stopped(ItemMergeOutcome.Rejected(reason))

    private fun ownershipMatches(
        source: ItemOwnership?,
        target: ItemOwnership?,
    ): Boolean = source?.ownerUuid == target?.ownerUuid && source?.eligibleOwnerUuids == target?.eligibleOwnerUuids
}

private fun Long.coerceBeforeExpiry(originalLifetimeSeconds: Long?): Long =
    when {
        originalLifetimeSeconds == null ||
            originalLifetimeSeconds == ItemLifetimeSettings.NEVER_EXPIRES ->
            this
        this >= originalLifetimeSeconds -> originalLifetimeSeconds - 1
        else -> this
    }

private sealed interface MergeStateLoad {
    data class Ready(
        val state: ItemState,
        val wasAbsent: Boolean,
    ) : MergeStateLoad

    data class Stopped(
        val outcome: ItemMergeOutcome,
    ) : MergeStateLoad
}
