package com.github.command1264.itemdropv2.core

import java.util.UUID

public data class ItemOwnership(
    public val ownerUuid: UUID,
    public val protectionSecondsRemaining: Long,
    public val eligibleOwnerUuids: List<UUID> = listOf(ownerUuid),
) {
    init {
        require(protectionSecondsRemaining > 0) { "protection seconds must be positive" }
        require(protectionSecondsRemaining <= Int.MAX_VALUE.toLong()) { "protection seconds exceed supported range" }
        require(eligibleOwnerUuids.isNotEmpty()) { "eligible owners must not be empty" }
        require(eligibleOwnerUuids.size <= MAX_ELIGIBLE_OWNERS) { "eligible owners exceed supported count" }
        require(ownerUuid in eligibleOwnerUuids) { "primary owner must be eligible" }
        require(eligibleOwnerUuids.distinct().size == eligibleOwnerUuids.size) { "eligible owners must not contain duplicates" }
    }

    public fun canPickup(playerUuid: UUID): Boolean = playerUuid in eligibleOwnerUuids

    public companion object {
        public const val MAX_ELIGIBLE_OWNERS: Int = 64
    }
}

public data class ItemState(
    public val ownership: ItemOwnership?,
    public val elapsedLifetimeSeconds: Long,
    public val originalLifetimeSeconds: Long?,
    public val virtualAmount: VirtualItemAmount? = null,
) {
    init {
        require(
            originalLifetimeSeconds == null ||
                originalLifetimeSeconds == ItemLifetimeSettings.NEVER_EXPIRES ||
                originalLifetimeSeconds > 0,
        ) {
            "original item lifetime must be -1, positive, or absent"
        }
        require(elapsedLifetimeSeconds >= 0) { "elapsed item lifetime must not be negative" }
    }

    public constructor(
        ownership: ItemOwnership?,
        remainingLifetimeSeconds: Long?,
        virtualAmount: VirtualItemAmount? = null,
    ) : this(
        ownership = ownership,
        elapsedLifetimeSeconds = 0,
        originalLifetimeSeconds = remainingLifetimeSeconds,
        virtualAmount = virtualAmount,
    )

    public val ageSeconds: Long
        get() = elapsedLifetimeSeconds

    public val lifetimeSeconds: Long?
        get() = originalLifetimeSeconds

    public val remainingLifetimeSeconds: Long?
        get() =
            when (originalLifetimeSeconds) {
                null -> null
                ItemLifetimeSettings.NEVER_EXPIRES -> ItemLifetimeSettings.NEVER_EXPIRES
                else -> (originalLifetimeSeconds - elapsedLifetimeSeconds).coerceAtLeast(0)
            }
}

@JvmInline
public value class VirtualItemAmount private constructor(
    public val value: Long,
) {
    public companion object {
        public val ONE: VirtualItemAmount = VirtualItemAmount(1)

        public fun of(value: Long): VirtualItemAmount {
            require(value > 0) { "virtual item amount must be positive" }
            return VirtualItemAmount(value)
        }
    }
}

public data class LegacyItemState(
    public val age: Long?,
    public val amount: Long?,
    public val ownerUuid: UUID?,
    public val ownerTime: Long?,
) {
    init {
        require(age == null || age in 0..Int.MAX_VALUE.toLong()) { "legacy age is out of range" }
        require(amount == null || amount in 1..Int.MAX_VALUE.toLong()) { "legacy amount is out of range" }
        require(ownerTime == null || ownerTime in 0..Int.MAX_VALUE.toLong()) {
            "legacy owner time is out of range"
        }
        require((ownerUuid == null) == (ownerTime == null)) {
            "legacy owner and owner time must either both be present or both be absent"
        }
    }
}

public interface ItemStateRepository {
    public fun load(entityId: UUID): ItemStateLoadResult

    public fun loadForRegistration(entityId: UUID): ItemStateLoadResult = load(entityId)

    public fun save(
        entityId: UUID,
        state: ItemState,
    ): ItemStateWriteResult
}

public fun interface RevisionedItemStateRepository {
    public fun saveRevisioned(
        entityId: UUID,
        state: ItemState,
    ): RevisionedItemStateWriteResult
}

public sealed interface ItemStateLoadResult {
    public data object Absent : ItemStateLoadResult

    public data class Loaded(
        public val state: ItemState,
        public val requiresSchemaUpgrade: Boolean = false,
        public val elapsedSecondsForLifetimeMigration: Long = 0,
        public val remainingSecondsForLifetimeMigration: Long? = null,
        public val revision: Long = 0,
    ) : ItemStateLoadResult {
        init {
            require(revision >= 0) { "item state revision must not be negative" }
        }
    }

    public data class Legacy(
        public val state: LegacyItemState,
    ) : ItemStateLoadResult

    public data class Invalid(
        public val errors: List<String>,
    ) : ItemStateLoadResult {
        init {
            require(errors.isNotEmpty()) { "invalid state must include at least one error" }
        }
    }

    public data class UnsupportedSchema(
        public val actualVersion: Int,
    ) : ItemStateLoadResult

    public data object MissingTarget : ItemStateLoadResult

    public data class Failed(
        public val errorType: String,
    ) : ItemStateLoadResult
}

public sealed interface ItemStateWriteResult {
    public data object Applied : ItemStateWriteResult

    public data object MissingTarget : ItemStateWriteResult

    public data class Rejected(
        public val reason: String,
    ) : ItemStateWriteResult

    public data class Failed(
        public val errorType: String,
    ) : ItemStateWriteResult
}

public sealed interface RevisionedItemStateWriteResult {
    public data class Applied(
        public val revision: Long,
    ) : RevisionedItemStateWriteResult {
        init {
            require(revision > 0) { "applied item state revision must be positive" }
        }
    }

    public data object MissingTarget : RevisionedItemStateWriteResult

    public data class Rejected(
        public val reason: String,
    ) : RevisionedItemStateWriteResult

    public data class Failed(
        public val errorType: String,
    ) : RevisionedItemStateWriteResult
}
