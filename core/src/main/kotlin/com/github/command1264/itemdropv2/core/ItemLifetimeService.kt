package com.github.command1264.itemdropv2.core

import java.util.UUID

public sealed interface ItemLifetimeRegistrationOutcome {
    public data class Registered(
        public val state: ItemState,
    ) : ItemLifetimeRegistrationOutcome

    public data object ItemExpired : ItemLifetimeRegistrationOutcome

    public data class Rejected(
        public val reason: String,
    ) : ItemLifetimeRegistrationOutcome

    public data class Failed(
        public val errorType: String,
    ) : ItemLifetimeRegistrationOutcome
}

public sealed interface ItemLifetimeProcessingOutcome {
    public data object Active : ItemLifetimeProcessingOutcome

    public data object OwnershipExpired : ItemLifetimeProcessingOutcome

    public data object OwnershipDisplayChanged : ItemLifetimeProcessingOutcome

    public data object DisplayTimeChanged : ItemLifetimeProcessingOutcome

    public data object ItemExpired : ItemLifetimeProcessingOutcome

    public data class Rejected(
        public val reason: String,
    ) : ItemLifetimeProcessingOutcome

    public data class Failed(
        public val errorType: String,
    ) : ItemLifetimeProcessingOutcome
}

@Suppress("TooManyFunctions")
public class ItemLifetimeService(
    private val repository: ItemStateRepository,
    private val settingsRepository: ItemDisplaySettingsRepository,
) {
    public fun isExpired(entityId: UUID): Boolean? =
        when (val loaded = repository.load(entityId)) {
            is ItemStateLoadResult.Loaded -> loaded.state.isExpired(settingsRepository.settings())
            else -> null
        }

    public fun register(
        entityId: UUID,
        materialName: String? = null,
    ): ItemLifetimeRegistrationOutcome =
        when (val loaded = repository.loadForRegistration(entityId)) {
            ItemStateLoadResult.Absent -> registerNew(entityId, materialName)
            is ItemStateLoadResult.Loaded -> recoverLoaded(entityId, loaded, materialName)
            is ItemStateLoadResult.Legacy -> migrateLegacy(entityId, loaded.state, materialName)
            is ItemStateLoadResult.Invalid -> ItemLifetimeRegistrationOutcome.Rejected("InvalidExistingState")
            is ItemStateLoadResult.UnsupportedSchema ->
                ItemLifetimeRegistrationOutcome.Rejected("UnsupportedSchema:${loaded.actualVersion}")
            ItemStateLoadResult.MissingTarget -> ItemLifetimeRegistrationOutcome.Failed("MissingTarget")
            is ItemStateLoadResult.Failed -> ItemLifetimeRegistrationOutcome.Failed(loaded.errorType)
        }

    public fun processSecond(entityId: UUID): ItemLifetimeProcessingOutcome =
        when (val loaded = repository.load(entityId)) {
            is ItemStateLoadResult.Loaded -> processLoadedSecond(entityId, loaded)
            ItemStateLoadResult.Absent -> processing(registerNew(entityId, null, processImmediately = true))
            is ItemStateLoadResult.Legacy -> processing(migrateLegacy(entityId, loaded.state, null))
            is ItemStateLoadResult.Invalid -> ItemLifetimeProcessingOutcome.Rejected("InvalidExistingState")
            is ItemStateLoadResult.UnsupportedSchema ->
                ItemLifetimeProcessingOutcome.Rejected("UnsupportedSchema:${loaded.actualVersion}")
            ItemStateLoadResult.MissingTarget -> ItemLifetimeProcessingOutcome.Failed("MissingTarget")
            is ItemStateLoadResult.Failed -> ItemLifetimeProcessingOutcome.Failed(loaded.errorType)
        }

    @Suppress("ReturnCount")
    private fun processLoadedSecond(
        entityId: UUID,
        loaded: ItemStateLoadResult.Loaded,
    ): ItemLifetimeProcessingOutcome {
        val settings = settingsRepository.settings()
        if (loaded.state.originalLifetimeSeconds == null &&
            loaded.remainingSecondsForLifetimeMigration == null &&
            settings.lifetime.defaultLifetimeSeconds == IMMEDIATE_EXPIRY
        ) {
            return ItemLifetimeProcessingOutcome.ItemExpired
        }
        val current =
            if (loaded.remainingSecondsForLifetimeMigration != null ||
                loaded.state.originalLifetimeSeconds == null
            ) {
                recoverMissingOriginalLifetime(loaded, settings.lifetime.defaultLifetimeSeconds)
            } else {
                loaded.state
            }
        val currentLifetime = current.remainingLifetimeSeconds ?: settings.lifetime.defaultLifetimeSeconds
        if (currentLifetime == IMMEDIATE_EXPIRY || currentLifetime == 1L) {
            return ItemLifetimeProcessingOutcome.ItemExpired
        }
        val nextProtection = current.ownership?.protectionSecondsRemaining?.minus(1)
        val ownershipExpired = nextProtection != null && nextProtection <= 0
        val next =
            current.copy(
                ownership =
                    if (ownershipExpired) null else current.ownership?.copy(protectionSecondsRemaining = requireNotNull(nextProtection)),
                elapsedLifetimeSeconds = current.elapsedLifetimeSeconds.saturatingIncrement(),
            )
        val saved = save(entityId, next)
        val sharedOwnerRotated =
            next.ownership != null &&
                next.ownership.eligibleOwnerUuids.size > 1 &&
                ownershipDisplayIndex(next.ownership, settings) != ownershipDisplayIndex(current.ownership, settings)
        return saved.toProcessingOutcome(
            itemExpired = next.isExpired(settings),
            ownershipExpired = ownershipExpired,
            sharedOwnerRotated = sharedOwnerRotated,
            displayTimeChanged = shouldRefreshTimeDisplay(settings, next),
        )
    }

    private fun migrateLegacy(
        entityId: UUID,
        legacy: LegacyItemState,
        materialName: String?,
    ): ItemLifetimeRegistrationOutcome {
        val ownership =
            if (legacy.ownerUuid != null && legacy.ownerTime != null && legacy.ownerTime > 0) {
                ItemOwnership(legacy.ownerUuid, legacy.ownerTime)
            } else {
                null
            }
        val originalLifetime = resolvedLifetime(materialName)
        val elapsedLifetime = legacy.age ?: 0
        return if (isExpired(originalLifetime, elapsedLifetime)) {
            ItemLifetimeRegistrationOutcome.ItemExpired
        } else {
            save(
                entityId,
                ItemState(
                    ownership = ownership,
                    originalLifetimeSeconds = originalLifetime,
                    elapsedLifetimeSeconds = elapsedLifetime,
                ),
            )
        }
    }

    @Suppress("ReturnCount")
    private fun recoverLoaded(
        entityId: UUID,
        loaded: ItemStateLoadResult.Loaded,
        materialName: String?,
    ): ItemLifetimeRegistrationOutcome {
        val resolved = resolvedLifetime(materialName)
        if (loaded.state.originalLifetimeSeconds == null &&
            loaded.remainingSecondsForLifetimeMigration == null &&
            resolved == IMMEDIATE_EXPIRY
        ) {
            return ItemLifetimeRegistrationOutcome.ItemExpired
        }
        val recovered =
            if (loaded.remainingSecondsForLifetimeMigration != null ||
                loaded.state.originalLifetimeSeconds == null
            ) {
                recoverMissingOriginalLifetime(loaded, resolved)
            } else {
                loaded.state
            }
        if (recovered.remainingLifetimeSeconds == IMMEDIATE_EXPIRY) {
            return ItemLifetimeRegistrationOutcome.ItemExpired
        }
        return if (loaded.requiresSchemaUpgrade || recovered != loaded.state) {
            save(entityId, recovered)
        } else {
            ItemLifetimeRegistrationOutcome.Registered(recovered)
        }
    }

    private fun save(
        entityId: UUID,
        state: ItemState,
    ): ItemLifetimeRegistrationOutcome =
        when (val result = repository.save(entityId, state)) {
            ItemStateWriteResult.Applied -> ItemLifetimeRegistrationOutcome.Registered(state)
            ItemStateWriteResult.MissingTarget -> ItemLifetimeRegistrationOutcome.Failed("MissingTarget")
            is ItemStateWriteResult.Rejected -> ItemLifetimeRegistrationOutcome.Rejected(result.reason)
            is ItemStateWriteResult.Failed -> ItemLifetimeRegistrationOutcome.Failed(result.errorType)
        }

    private fun processing(outcome: ItemLifetimeRegistrationOutcome): ItemLifetimeProcessingOutcome =
        when (outcome) {
            is ItemLifetimeRegistrationOutcome.Registered -> ItemLifetimeProcessingOutcome.Active
            ItemLifetimeRegistrationOutcome.ItemExpired -> ItemLifetimeProcessingOutcome.ItemExpired
            is ItemLifetimeRegistrationOutcome.Rejected -> ItemLifetimeProcessingOutcome.Rejected(outcome.reason)
            is ItemLifetimeRegistrationOutcome.Failed -> ItemLifetimeProcessingOutcome.Failed(outcome.errorType)
        }

    private fun registerNew(
        entityId: UUID,
        materialName: String?,
        processImmediately: Boolean = false,
    ): ItemLifetimeRegistrationOutcome {
        val lifetime = resolvedLifetime(materialName)
        val elapsed = if (processImmediately && lifetime != IMMEDIATE_EXPIRY) 1L else 0L
        return if (isExpired(lifetime, elapsed)) {
            ItemLifetimeRegistrationOutcome.ItemExpired
        } else {
            save(
                entityId,
                ItemState(
                    ownership = null,
                    originalLifetimeSeconds = lifetime,
                    elapsedLifetimeSeconds = elapsed,
                ),
            )
        }
    }

    private fun resolvedLifetime(materialName: String?): Long = settingsRepository.settings().lifetime.resolve(materialName)

    private fun ItemState.isExpired(settings: ItemDisplaySettings): Boolean =
        (remainingLifetimeSeconds ?: settings.lifetime.defaultLifetimeSeconds) == IMMEDIATE_EXPIRY

    private fun recoverMissingOriginalLifetime(
        loaded: ItemStateLoadResult.Loaded,
        resolvedLifetime: Long,
    ): ItemState {
        val remaining = loaded.remainingSecondsForLifetimeMigration
        return if (remaining == null) {
            loaded.state.copy(
                originalLifetimeSeconds = resolvedLifetime,
                elapsedLifetimeSeconds = loaded.elapsedSecondsForLifetimeMigration,
            )
        } else {
            val original =
                when {
                    remaining == ItemLifetimeSettings.NEVER_EXPIRES -> ItemLifetimeSettings.NEVER_EXPIRES
                    resolvedLifetime > 0 && resolvedLifetime >= remaining -> resolvedLifetime
                    else -> remaining
                }
            val elapsed =
                if (original > 0 && remaining >= 0) {
                    original - remaining
                } else {
                    0
                }
            loaded.state.copy(
                originalLifetimeSeconds = original,
                elapsedLifetimeSeconds = elapsed,
            )
        }
    }

    private fun isExpired(
        originalLifetimeSeconds: Long,
        elapsedLifetimeSeconds: Long,
    ): Boolean =
        originalLifetimeSeconds == IMMEDIATE_EXPIRY ||
            originalLifetimeSeconds > 0 && elapsedLifetimeSeconds >= originalLifetimeSeconds

    @Suppress("ReturnCount")
    private fun ownershipDisplayIndex(
        ownership: ItemOwnership?,
        settings: ItemDisplaySettings,
    ): Int? {
        ownership ?: return null
        if (ownership.eligibleOwnerUuids.size <= 1) return 0
        val elapsed =
            (settings.ownership.protectionSeconds - ownership.protectionSecondsRemaining)
                .coerceAtLeast(0)
        return ((elapsed / settings.ownership.display.rotationSeconds) % ownership.eligibleOwnerUuids.size).toInt()
    }

    private companion object {
        private const val IMMEDIATE_EXPIRY = 0L
    }
}

private fun Long.saturatingIncrement(): Long = if (this == Long.MAX_VALUE) this else this + 1

private fun ItemLifetimeRegistrationOutcome.toProcessingOutcome(
    itemExpired: Boolean,
    ownershipExpired: Boolean,
    sharedOwnerRotated: Boolean,
    displayTimeChanged: Boolean,
): ItemLifetimeProcessingOutcome =
    when (this) {
        is ItemLifetimeRegistrationOutcome.Registered ->
            when {
                itemExpired -> ItemLifetimeProcessingOutcome.ItemExpired
                ownershipExpired -> ItemLifetimeProcessingOutcome.OwnershipExpired
                sharedOwnerRotated -> ItemLifetimeProcessingOutcome.OwnershipDisplayChanged
                displayTimeChanged -> ItemLifetimeProcessingOutcome.DisplayTimeChanged
                else -> ItemLifetimeProcessingOutcome.Active
            }
        ItemLifetimeRegistrationOutcome.ItemExpired -> ItemLifetimeProcessingOutcome.ItemExpired
        is ItemLifetimeRegistrationOutcome.Rejected -> ItemLifetimeProcessingOutcome.Rejected(reason)
        is ItemLifetimeRegistrationOutcome.Failed -> ItemLifetimeProcessingOutcome.Failed(errorType)
    }

private fun shouldRefreshTimeDisplay(
    settings: ItemDisplaySettings,
    state: ItemState,
): Boolean {
    val templates = listOf(settings.singleItemTemplate, settings.multipleItemTemplate)
    val ownerTemplates =
        listOf(
            settings.ownership.display.singleOwnerPrefixTemplate,
            settings.ownership.display.multipleOwnersPrefixTemplate,
        )
    val protectionChanges =
        state.ownership != null &&
            (
                templates.any(DisplayTemplate::usesProtectionRemaining) ||
                    ownerTemplates.any(OwnerDisplayTemplate::usesProtectionRemaining)
            )
    val finiteLifetimeChanges =
        state.originalLifetimeSeconds != null &&
            state.originalLifetimeSeconds != ItemLifetimeSettings.NEVER_EXPIRES &&
            templates.any(DisplayTemplate::usesLifetimeRemaining)
    val elapsedLifetimeChanges =
        state.originalLifetimeSeconds != null &&
            templates.any(DisplayTemplate::usesLifetimeElapsed)
    return protectionChanges || finiteLifetimeChanges || elapsedLifetimeChanges
}
