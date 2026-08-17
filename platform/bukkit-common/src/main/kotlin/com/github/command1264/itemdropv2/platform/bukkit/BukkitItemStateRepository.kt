package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemOwnership
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import com.github.command1264.itemdropv2.core.LegacyItemState
import com.github.command1264.itemdropv2.core.RevisionedItemStateRepository
import com.github.command1264.itemdropv2.core.RevisionedItemStateWriteResult
import com.github.command1264.itemdropv2.core.VirtualItemAmount
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

@Suppress("TooManyFunctions")
public class BukkitItemStateRepository internal constructor(
    private val targetResolver: (UUID) -> PersistentDataContainer?,
    private val primaryThreadCheck: () -> Boolean,
    private val epochMillis: () -> Long = System::currentTimeMillis,
    private val fallbackTargetResolver: (UUID) -> PersistentFallbackTarget? = { null },
    private val itemResolver: (UUID) -> Item? = { null },
    private val revisionGate: ItemStateRevisionDurabilityGate? = null,
    private val transientCanonicalTargetResolver: (UUID) -> PersistentDataContainer? = targetResolver,
) : ItemStateRepository,
    RevisionedItemStateRepository {
    private val transientTargets = mutableMapOf<UUID, TransientTargetEntry>()

    public constructor() : this(revisionGate = null)

    public constructor(revisionGate: ItemStateRevisionDurabilityGate?) : this(
        revisionGate = revisionGate,
        loadedEntityCanonicalFallback = true,
    )

    public constructor(
        revisionGate: ItemStateRevisionDurabilityGate?,
        loadedEntityCanonicalFallback: Boolean,
    ) : this(
        targetResolver = { entityId ->
            resolveBukkitItem(entityId)?.persistentDataContainer
        },
        primaryThreadCheck = Bukkit::isPrimaryThread,
        fallbackTargetResolver = { entityId ->
            resolveBukkitItem(entityId)?.let(::itemMetaFallback)
        },
        itemResolver = ::resolveBukkitItem,
        revisionGate = revisionGate,
        transientCanonicalTargetResolver = { entityId ->
            val direct = Bukkit.getServer().getEntity(entityId) as? Item
            val canonical = direct ?: if (loadedEntityCanonicalFallback) resolveBukkitItem(entityId) else null
            canonical?.persistentDataContainer
        },
    )

    override fun load(entityId: UUID): ItemStateLoadResult = load(entityId, inspectItemStackFallback = false)

    override fun loadForRegistration(entityId: UUID): ItemStateLoadResult = load(entityId, inspectItemStackFallback = true)

    private fun load(
        entityId: UUID,
        inspectItemStackFallback: Boolean,
    ): ItemStateLoadResult =
        if (!primaryThreadCheck()) {
            ItemStateLoadResult.Failed(OFF_MAIN_THREAD)
        } else {
            resolvePrimaryTarget(entityId)?.let { loadSafely(entityId, it, inspectItemStackFallback) }
                ?: ItemStateLoadResult.MissingTarget
        }

    override fun save(
        entityId: UUID,
        state: ItemState,
    ): ItemStateWriteResult = saveRevisioned(entityId, state).withoutRevision()

    override fun saveRevisioned(
        entityId: UUID,
        state: ItemState,
    ): RevisionedItemStateWriteResult =
        if (!primaryThreadCheck()) {
            RevisionedItemStateWriteResult.Failed(OFF_MAIN_THREAD)
        } else if (state.remainingLifetimeSeconds == 0L) {
            RevisionedItemStateWriteResult.Rejected("ImmediateExpiryMustNotBePersisted")
        } else {
            resolvePrimaryTarget(entityId)?.let { saveRevisionedSafely(entityId, it, state) }
                ?: RevisionedItemStateWriteResult.MissingTarget
        }

    public fun leaseTransientTarget(item: Item): TransientItemTargetLease {
        val container = item.persistentDataContainer
        val entry =
            transientTargets[item.uniqueId]
                ?.takeIf { it.container === container }
                ?.also { it.holderCount++ }
                ?: TransientTargetEntry(item, container).also { transientTargets[item.uniqueId] = it }
        var released = false
        return TransientItemTargetLease {
            if (!released && transientTargets[item.uniqueId] === entry) {
                released = true
                entry.holderCount--
                if (entry.holderCount == 0) {
                    transientTargets.remove(item.uniqueId)
                }
            }
        }
    }

    private fun resolvePrimaryTarget(entityId: UUID): PersistentDataContainer? =
        transientTargets[entityId]?.container
            ?: targetResolver(entityId)

    private fun resolveFallbackTarget(entityId: UUID): PersistentFallbackTarget? = fallbackTargetResolver(entityId)

    @Suppress("TooGenericExceptionCaught")
    private fun loadSafely(
        entityId: UUID,
        container: PersistentDataContainer,
        inspectItemStackFallback: Boolean,
    ): ItemStateLoadResult =
        try {
            loadWithFallback(entityId, container, inspectItemStackFallback)
        } catch (error: RuntimeException) {
            ItemStateLoadResult.Failed(error.javaClass.simpleName)
        }

    @Suppress("TooGenericExceptionCaught")
    private fun saveRevisionedSafely(
        entityId: UUID,
        container: PersistentDataContainer,
        state: ItemState,
    ): RevisionedItemStateWriteResult =
        try {
            val canonical = transientCanonicalTargetResolver(entityId)?.takeIf { it !== container }
            val loadedTargets = listOfNotNull(container, canonical).map { BukkitItemStateCodec.load(it, epochMillis()) }
            loadedTargets.firstOrNull { it.nextRevisionOrReject() == null }?.let {
                return it.toRevisionRejection()
            }
            val pdcRevision = loadedTargets.maxOf { requireNotNull(it.nextRevisionOrReject()) }
            val allocation = allocateRevision(entityId, pdcRevision)
            if (allocation is RevisionAllocation.Blocked) return allocation.result
            allocation as RevisionAllocation.Ready
            when (val prepared = prepareRevision(allocation.item, state, allocation.revision)) {
                is ItemStateDurabilityOutcome.Accepted -> Unit
                is ItemStateDurabilityOutcome.Rejected ->
                    return RevisionedItemStateWriteResult.Rejected("Journal:${prepared.reason}")
                is ItemStateDurabilityOutcome.Failed ->
                    return RevisionedItemStateWriteResult.Failed("Journal:${prepared.errorType}")
            }
            when (val write = saveWithFallback(entityId, container, canonical, state, allocation.revision)) {
                ItemStateWriteResult.Applied -> RevisionedItemStateWriteResult.Applied(allocation.revision)
                ItemStateWriteResult.MissingTarget -> RevisionedItemStateWriteResult.MissingTarget
                is ItemStateWriteResult.Rejected -> RevisionedItemStateWriteResult.Rejected(write.reason)
                is ItemStateWriteResult.Failed -> RevisionedItemStateWriteResult.Failed(write.errorType)
            }
        } catch (error: RuntimeException) {
            RevisionedItemStateWriteResult.Failed(error.javaClass.simpleName)
        }

    @Suppress("ReturnCount")
    private fun allocateRevision(
        entityId: UUID,
        pdcRevision: Long,
    ): RevisionAllocation {
        val gate = revisionGate ?: return RevisionAllocation.Ready(item = null, revision = pdcRevision)
        val item =
            transientTargets[entityId]?.item ?: itemResolver(entityId)
                ?: return RevisionAllocation.Blocked(
                    RevisionedItemStateWriteResult.Failed("Journal:MissingItemSnapshot"),
                )
        val latest = gate.latestRevision(item) ?: return RevisionAllocation.Ready(item, pdcRevision)
        return if (latest == Long.MAX_VALUE) {
            RevisionAllocation.Blocked(
                RevisionedItemStateWriteResult.Rejected("Journal:RevisionExhausted"),
            )
        } else {
            RevisionAllocation.Ready(item, maxOf(pdcRevision, latest + 1))
        }
    }

    @Suppress("ReturnCount")
    private fun prepareRevision(
        item: Item?,
        state: ItemState,
        revision: Long,
    ): ItemStateDurabilityOutcome {
        val gate = revisionGate ?: return ItemStateDurabilityOutcome.Accepted(coalesced = false)
        return gate.prepare(requireNotNull(item), state, revision)
    }

    private fun loadWithFallback(
        entityId: UUID,
        container: PersistentDataContainer,
        inspectItemStackFallback: Boolean,
    ): ItemStateLoadResult {
        val loaded = BukkitItemStateCodec.load(container, epochMillis())
        return when {
            loaded == ItemStateLoadResult.Absent ->
                resolveFallbackTarget(entityId)
                    ?.let { BukkitItemStateCodec.load(it.container, epochMillis()) }
                    ?.markLoadedStateForNormalization()
                    ?: loaded
            loaded is ItemStateLoadResult.Loaded && inspectItemStackFallback ->
                markForFallbackCleanup(entityId, container, loaded)
            else -> loaded
        }
    }

    private fun markForFallbackCleanup(
        entityId: UUID,
        primary: PersistentDataContainer,
        loaded: ItemStateLoadResult.Loaded,
    ): ItemStateLoadResult.Loaded {
        val fallback = resolveFallbackTarget(entityId)
        return if (
            fallback != null &&
            fallback.container !== primary &&
            BukkitItemStateCodec.containsState(fallback.container)
        ) {
            loaded.copy(requiresSchemaUpgrade = true)
        } else {
            loaded
        }
    }

    private fun saveWithFallback(
        entityId: UUID,
        container: PersistentDataContainer,
        canonicalTarget: PersistentDataContainer?,
        state: ItemState,
        revision: Long,
    ): ItemStateWriteResult {
        val fallback = resolveFallbackTarget(entityId)
        val fallbackContainedState = fallback?.let { BukkitItemStateCodec.containsState(it.container) } == true
        val primary = BukkitItemStateCodec.save(container, state, revision, epochMillis())
        val synchronized =
            if (primary == ItemStateWriteResult.Applied) {
                val canonical = canonicalTarget
                if (canonical != null && canonical !== container) {
                    BukkitItemStateCodec.save(canonical, state, revision, epochMillis())
                } else {
                    ItemStateWriteResult.Applied
                }
            } else {
                primary
            }

        return if (synchronized == ItemStateWriteResult.Applied && fallback != null && fallbackContainedState) {
            val cleared = clearFallback(fallback)
            val refreshedPrimary = targetResolver(entityId) ?: resolvePrimaryTarget(entityId)
            if (cleared == ItemStateWriteResult.Applied && refreshedPrimary == null) {
                ItemStateWriteResult.MissingTarget
            } else if (
                cleared == ItemStateWriteResult.Applied &&
                !BukkitItemStateCodec.containsState(requireNotNull(refreshedPrimary))
            ) {
                BukkitItemStateCodec.save(refreshedPrimary, state, revision, epochMillis())
            } else {
                cleared
            }
        } else {
            synchronized
        }
    }

    public fun clearItemStackFallback(item: Item): ItemStateWriteResult =
        if (!primaryThreadCheck()) {
            ItemStateWriteResult.Failed(OFF_MAIN_THREAD)
        } else {
            itemMetaFallback(item)?.let(::clearFallback) ?: ItemStateWriteResult.Applied
        }

    @Suppress("TooGenericExceptionCaught")
    public fun restoreRevisioned(
        item: Item,
        state: ItemState,
        revision: Long,
    ): RevisionedItemStateWriteResult =
        if (!primaryThreadCheck()) {
            RevisionedItemStateWriteResult.Failed(OFF_MAIN_THREAD)
        } else {
            try {
                val container = item.persistentDataContainer
                val canonical = targetResolver(item.uniqueId)?.takeIf { it !== container }
                when (val write = saveWithFallback(item.uniqueId, container, canonical, state, revision)) {
                    ItemStateWriteResult.Applied -> RevisionedItemStateWriteResult.Applied(revision)
                    ItemStateWriteResult.MissingTarget -> RevisionedItemStateWriteResult.MissingTarget
                    is ItemStateWriteResult.Rejected -> RevisionedItemStateWriteResult.Rejected(write.reason)
                    is ItemStateWriteResult.Failed -> RevisionedItemStateWriteResult.Failed(write.errorType)
                }
            } catch (error: RuntimeException) {
                RevisionedItemStateWriteResult.Failed(error.javaClass.simpleName)
            }
        }

    @Suppress("TooGenericExceptionCaught")
    public fun clearItemState(item: Item): ItemStateWriteResult =
        if (!primaryThreadCheck()) {
            ItemStateWriteResult.Failed(OFF_MAIN_THREAD)
        } else {
            try {
                when (val discarded = revisionGate?.discard(item)) {
                    null,
                    is ItemStateDurabilityOutcome.Accepted,
                    -> Unit
                    is ItemStateDurabilityOutcome.Rejected ->
                        return ItemStateWriteResult.Rejected("Journal:${discarded.reason}")
                    is ItemStateDurabilityOutcome.Failed ->
                        return ItemStateWriteResult.Failed("Journal:${discarded.errorType}")
                }
                val primary = item.persistentDataContainer
                BukkitItemStateCodec.clear(primary)
                targetResolver(item.uniqueId)?.takeIf { canonical -> canonical !== primary }?.let(BukkitItemStateCodec::clear)
                resolveFallbackTarget(item.uniqueId)?.let(::clearFallback) ?: ItemStateWriteResult.Applied
            } catch (error: RuntimeException) {
                ItemStateWriteResult.Failed(error.javaClass.simpleName)
            }
        }

    @Suppress("TooGenericExceptionCaught")
    internal fun clearFallback(fallback: PersistentFallbackTarget): ItemStateWriteResult =
        try {
            if (BukkitItemStateCodec.clear(fallback.container)) {
                fallback.commit()
            }
            ItemStateWriteResult.Applied
        } catch (error: RuntimeException) {
            ItemStateWriteResult.Failed(error.javaClass.simpleName)
        }

    private fun RevisionedItemStateWriteResult.withoutRevision(): ItemStateWriteResult =
        when (this) {
            is RevisionedItemStateWriteResult.Applied -> ItemStateWriteResult.Applied
            RevisionedItemStateWriteResult.MissingTarget -> ItemStateWriteResult.MissingTarget
            is RevisionedItemStateWriteResult.Rejected -> ItemStateWriteResult.Rejected(reason)
            is RevisionedItemStateWriteResult.Failed -> ItemStateWriteResult.Failed(errorType)
        }

    private fun ItemStateLoadResult.nextRevisionOrReject(): Long? =
        when (this) {
            is ItemStateLoadResult.Loaded -> revision.takeIf { it < Long.MAX_VALUE }?.plus(1)
            ItemStateLoadResult.Absent,
            is ItemStateLoadResult.Legacy,
            -> 1
            else -> null
        }

    private fun ItemStateLoadResult.toRevisionRejection(): RevisionedItemStateWriteResult =
        when (this) {
            is ItemStateLoadResult.Loaded -> RevisionedItemStateWriteResult.Rejected("RevisionExhausted")
            is ItemStateLoadResult.Invalid -> RevisionedItemStateWriteResult.Rejected("InvalidExistingState")
            is ItemStateLoadResult.UnsupportedSchema -> RevisionedItemStateWriteResult.Rejected("UnsupportedSchema:$actualVersion")
            is ItemStateLoadResult.Failed -> RevisionedItemStateWriteResult.Failed(errorType)
            ItemStateLoadResult.MissingTarget -> RevisionedItemStateWriteResult.MissingTarget
            ItemStateLoadResult.Absent,
            is ItemStateLoadResult.Legacy,
            -> error("revision is available for absent and legacy states")
        }

    private companion object {
        private const val OFF_MAIN_THREAD = "OffMainThread"
    }

    private data class TransientTargetEntry(
        val item: Item,
        val container: PersistentDataContainer,
        var holderCount: Int = 1,
    )

    private sealed interface RevisionAllocation {
        data class Ready(
            val item: Item?,
            val revision: Long,
        ) : RevisionAllocation

        data class Blocked(
            val result: RevisionedItemStateWriteResult,
        ) : RevisionAllocation
    }
}

public fun interface ItemStateRevisionDurabilityGate {
    /** Returns the newest committed durable revision for this exact Item identity, if one exists. */
    public fun latestRevision(item: Item): Long? = null

    public fun prepare(
        item: Item,
        state: ItemState,
        revision: Long,
    ): ItemStateDurabilityOutcome

    public fun discard(item: Item): ItemStateDurabilityOutcome = ItemStateDurabilityOutcome.Accepted(coalesced = true)
}

private fun ItemStateLoadResult.markLoadedStateForNormalization(): ItemStateLoadResult =
    if (this is ItemStateLoadResult.Loaded) copy(requiresSchemaUpgrade = true) else this

internal data class PersistentFallbackTarget(
    val container: PersistentDataContainer,
    val commit: () -> Unit,
)

private fun itemMetaFallback(item: Item): PersistentFallbackTarget? {
    val stack = item.itemStack
    val meta = stack.itemMeta ?: return null
    return PersistentFallbackTarget(meta.persistentDataContainer) {
        stack.itemMeta = meta
        item.setItemStack(stack)
    }
}

@Suppress("TooManyFunctions")
private object BukkitItemStateCodec {
    fun containsState(container: PersistentDataContainer): Boolean = ALL_ITEM_STATE_KEYS.any(container::hasAnyType)

    fun clear(container: PersistentDataContainer): Boolean {
        val containedState = containsState(container)
        ALL_ITEM_STATE_KEYS.forEach(container::remove)
        return containedState
    }

    @Suppress("CyclomaticComplexMethod")
    fun load(
        container: PersistentDataContainer,
        nowEpochMillis: Long,
    ): ItemStateLoadResult {
        val schemaPresent = container.hasAnyType(STATE_SCHEMA_VERSION_KEY)
        return when {
            !schemaPresent && NEW_PAYLOAD_KEYS.any { container.hasAnyType(it) } ->
                ItemStateLoadResult.Invalid(
                    listOf("new item state payload exists without state-schema-version"),
                )
            !schemaPresent -> loadLegacy(container)
            !container.has(STATE_SCHEMA_VERSION_KEY, PersistentDataType.INTEGER) ->
                ItemStateLoadResult.Invalid(
                    listOf("$STATE_SCHEMA_VERSION_KEY: expected INTEGER"),
                )
            else -> {
                val schemaVersion =
                    requireNotNull(container.get(STATE_SCHEMA_VERSION_KEY, PersistentDataType.INTEGER))
                when (schemaVersion) {
                    CURRENT_SCHEMA_VERSION -> loadSchemaEight(container)
                    SCHEMA_VERSION_SEVEN -> loadSchemaSeven(container)
                    SCHEMA_VERSION_SIX -> loadSchemaSix(container)
                    SCHEMA_VERSION_FIVE -> loadSchemaFive(container)
                    SCHEMA_VERSION_FOUR -> loadSchemaFour(container)
                    SCHEMA_VERSION_THREE -> loadSchemaThree(container)
                    SCHEMA_VERSION_TWO -> loadSchemaTwo(container)
                    SCHEMA_VERSION_ONE -> loadSchemaOne(container, nowEpochMillis)
                    else -> ItemStateLoadResult.UnsupportedSchema(schemaVersion)
                }
            }
        }
    }

    fun save(
        container: PersistentDataContainer,
        state: ItemState,
        revision: Long,
        nowEpochMillis: Long,
    ): ItemStateWriteResult =
        when (val existing = load(container, nowEpochMillis)) {
            is ItemStateLoadResult.Invalid -> ItemStateWriteResult.Rejected("InvalidExistingState")
            is ItemStateLoadResult.UnsupportedSchema ->
                ItemStateWriteResult.Rejected("UnsupportedSchema:${existing.actualVersion}")
            is ItemStateLoadResult.Loaded
            if existing.revision > revision -> ItemStateWriteResult.Rejected("StaleRevision")
            is ItemStateLoadResult.Loaded
            if existing.revision == revision && existing.state != state ->
                ItemStateWriteResult.Rejected("EqualRevisionConflict")
            is ItemStateLoadResult.Loaded
            if existing.revision == revision -> ItemStateWriteResult.Applied
            else -> {
                // Schema 最後寫入；若中途失敗，reader 會把殘留 payload 判定為 invalid，不能誤當完整資料。
                container.remove(STATE_SCHEMA_VERSION_KEY)
                PREVIOUS_PAYLOAD_KEYS.forEach(container::remove)
                require(revision > 0) { "item state revision must be positive" }
                container.setOrRemove(
                    STATE_ORIGINAL_LIFETIME_SECONDS_KEY,
                    state.originalLifetimeSeconds,
                )
                container.set(
                    STATE_ELAPSED_LIFETIME_SECONDS_KEY,
                    PersistentDataType.LONG,
                    state.elapsedLifetimeSeconds,
                )
                container.setOrRemove(STATE_OWNER_UUID_KEY, state.ownership?.ownerUuid?.toString())
                container.setOrRemove(
                    STATE_ELIGIBLE_OWNER_UUIDS_KEY,
                    state.ownership?.eligibleOwnerUuids?.joinToString(","),
                )
                container.setOrRemove(
                    STATE_PROTECTION_SECONDS_KEY,
                    state.ownership?.protectionSecondsRemaining,
                )
                container.setOrRemove(STATE_VIRTUAL_AMOUNT_KEY, state.virtualAmount?.value)
                container.set(STATE_REVISION_KEY, PersistentDataType.LONG, revision)
                container.set(STATE_SCHEMA_VERSION_KEY, PersistentDataType.INTEGER, CURRENT_SCHEMA_VERSION)
                ItemStateWriteResult.Applied
            }
        }

    private fun loadSchemaThree(container: PersistentDataContainer): ItemStateLoadResult {
        val errors = mutableListOf<String>()
        val ageSeconds = container.readRequiredLong(STATE_AGE_SECONDS_KEY, errors)
        val lifetimeSeconds = container.readOptionalLong(STATE_LIFETIME_SECONDS_KEY, errors)
        val ownerUuid = container.readOptionalUuid(STATE_OWNER_UUID_KEY, errors)
        val eligibleOwnerUuids = container.readOptionalUuidList(STATE_ELIGIBLE_OWNER_UUIDS_KEY, errors)
        val protectionSeconds = container.readOptionalLong(STATE_PROTECTION_SECONDS_KEY, errors)

        validateTimeValue(STATE_AGE_SECONDS_KEY, ageSeconds, allowZero = true, errors)
        validateTimeValue(STATE_LIFETIME_SECONDS_KEY, lifetimeSeconds, allowZero = false, errors)
        validateTimeValue(STATE_PROTECTION_SECONDS_KEY, protectionSeconds, allowZero = false, errors)
        val ownerPresent = container.hasAnyType(STATE_OWNER_UUID_KEY)
        val eligibleOwnersPresent = container.hasAnyType(STATE_ELIGIBLE_OWNER_UUIDS_KEY)
        val protectionPresent = container.hasAnyType(STATE_PROTECTION_SECONDS_KEY)
        if (setOf(ownerPresent, eligibleOwnersPresent, protectionPresent).size != 1) {
            errors += "state owner, eligible owners and protection must either all be present or all be absent"
        }
        if (ownerUuid != null && eligibleOwnerUuids != null && ownerUuid !in eligibleOwnerUuids) {
            errors += "state primary owner must be included in eligible owners"
        }
        if (errors.isNotEmpty()) return ItemStateLoadResult.Invalid(errors.toList())

        val ownership =
            if (ownerUuid != null && eligibleOwnerUuids != null && protectionSeconds != null) {
                ItemOwnership(ownerUuid, protectionSeconds, eligibleOwnerUuids)
            } else {
                null
            }
        return ItemStateLoadResult.Loaded(
            ItemState(
                ownership = ownership,
                originalLifetimeSeconds = lifetimeSeconds,
                elapsedLifetimeSeconds = requireNotNull(ageSeconds),
            ),
            requiresSchemaUpgrade = true,
            elapsedSecondsForLifetimeMigration = if (lifetimeSeconds == null) requireNotNull(ageSeconds) else 0,
        )
    }

    private fun loadSchemaFive(container: PersistentDataContainer): ItemStateLoadResult =
        loadPreviousPayload(container, requireVirtualAmount = true)

    private fun loadSchemaFour(container: PersistentDataContainer): ItemStateLoadResult =
        loadPreviousPayload(container, requireVirtualAmount = false)

    @Suppress("CyclomaticComplexMethod")
    private fun loadPreviousPayload(
        container: PersistentDataContainer,
        requireVirtualAmount: Boolean,
    ): ItemStateLoadResult {
        val errors = mutableListOf<String>()
        val ageSeconds = container.readRequiredLong(STATE_AGE_SECONDS_KEY, errors)
        val lifetimeSeconds = container.readOptionalLong(STATE_LIFETIME_SECONDS_KEY, errors)
        val ownerUuid = container.readOptionalUuid(STATE_OWNER_UUID_KEY, errors)
        val eligibleOwnerUuids = container.readOptionalUuidList(STATE_ELIGIBLE_OWNER_UUIDS_KEY, errors)
        val protectionSeconds = container.readOptionalLong(STATE_PROTECTION_SECONDS_KEY, errors)
        val virtualAmount =
            if (requireVirtualAmount) {
                container.readRequiredLong(STATE_VIRTUAL_AMOUNT_KEY, errors)
            } else {
                null
            }
        if (ageSeconds != null && ageSeconds < 0) errors += "$STATE_AGE_SECONDS_KEY: must not be negative"
        if (lifetimeSeconds != null && lifetimeSeconds != -1L && lifetimeSeconds <= 0L) {
            errors += "$STATE_LIFETIME_SECONDS_KEY: must be -1 or positive"
        }
        validateTimeValue(STATE_PROTECTION_SECONDS_KEY, protectionSeconds, allowZero = false, errors)
        if (virtualAmount != null && virtualAmount <= 0) {
            errors += "$STATE_VIRTUAL_AMOUNT_KEY: must be positive"
        }
        val presence =
            setOf(
                container.hasAnyType(STATE_OWNER_UUID_KEY),
                container.hasAnyType(STATE_ELIGIBLE_OWNER_UUIDS_KEY),
                container.hasAnyType(STATE_PROTECTION_SECONDS_KEY),
            )
        if (presence.size != 1) {
            errors += "state owner, eligible owners and protection must either all be present or all be absent"
        }
        if (ownerUuid != null && eligibleOwnerUuids != null && ownerUuid !in eligibleOwnerUuids) {
            errors += "state primary owner must be included in eligible owners"
        }
        return if (errors.isNotEmpty()) {
            ItemStateLoadResult.Invalid(errors)
        } else {
            ItemStateLoadResult.Loaded(
                ItemState(
                    ownership =
                        if (ownerUuid != null && eligibleOwnerUuids != null && protectionSeconds != null) {
                            ItemOwnership(ownerUuid, protectionSeconds, eligibleOwnerUuids)
                        } else {
                            null
                        },
                    originalLifetimeSeconds = lifetimeSeconds,
                    elapsedLifetimeSeconds = requireNotNull(ageSeconds),
                    virtualAmount = virtualAmount?.let(VirtualItemAmount::of),
                ),
                requiresSchemaUpgrade = true,
                elapsedSecondsForLifetimeMigration = if (lifetimeSeconds == null) requireNotNull(ageSeconds) else 0,
            )
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun loadSchemaSix(container: PersistentDataContainer): ItemStateLoadResult {
        val errors = mutableListOf<String>()
        val remainingLifetime = container.readOptionalLong(STATE_REMAINING_LIFETIME_SECONDS_KEY, errors)
        val ownerUuid = container.readOptionalUuid(STATE_OWNER_UUID_KEY, errors)
        val eligibleOwnerUuids = container.readOptionalUuidList(STATE_ELIGIBLE_OWNER_UUIDS_KEY, errors)
        val protectionSeconds = container.readOptionalLong(STATE_PROTECTION_SECONDS_KEY, errors)
        val virtualAmount = container.readOptionalLong(STATE_VIRTUAL_AMOUNT_KEY, errors)
        if (remainingLifetime != null && remainingLifetime != -1L && remainingLifetime <= 0L) {
            errors += "$STATE_REMAINING_LIFETIME_SECONDS_KEY: must be -1 or positive"
        }
        validateTimeValue(STATE_PROTECTION_SECONDS_KEY, protectionSeconds, allowZero = false, errors)
        if (virtualAmount != null && virtualAmount <= 0) {
            errors += "$STATE_VIRTUAL_AMOUNT_KEY: must be positive"
        }
        val presence =
            setOf(
                container.hasAnyType(STATE_OWNER_UUID_KEY),
                container.hasAnyType(STATE_ELIGIBLE_OWNER_UUIDS_KEY),
                container.hasAnyType(STATE_PROTECTION_SECONDS_KEY),
            )
        if (presence.size != 1) {
            errors += "state owner, eligible owners and protection must either all be present or all be absent"
        }
        if (ownerUuid != null && eligibleOwnerUuids != null && ownerUuid !in eligibleOwnerUuids) {
            errors += "state primary owner must be included in eligible owners"
        }
        return if (errors.isNotEmpty()) {
            ItemStateLoadResult.Invalid(errors)
        } else {
            ItemStateLoadResult.Loaded(
                ItemState(
                    ownership =
                        if (ownerUuid != null && eligibleOwnerUuids != null && protectionSeconds != null) {
                            ItemOwnership(ownerUuid, protectionSeconds, eligibleOwnerUuids)
                        } else {
                            null
                        },
                    originalLifetimeSeconds = remainingLifetime,
                    elapsedLifetimeSeconds = 0,
                    virtualAmount = virtualAmount?.let(VirtualItemAmount::of),
                ),
                requiresSchemaUpgrade = true,
                remainingSecondsForLifetimeMigration = remainingLifetime,
            )
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun loadSchemaEight(container: PersistentDataContainer): ItemStateLoadResult =
        loadCurrentPayload(container, requiresSchemaUpgrade = false, requireRevision = true)

    private fun loadSchemaSeven(container: PersistentDataContainer): ItemStateLoadResult =
        loadCurrentPayload(container, requiresSchemaUpgrade = true, requireRevision = false)

    @Suppress("CyclomaticComplexMethod")
    private fun loadCurrentPayload(
        container: PersistentDataContainer,
        requiresSchemaUpgrade: Boolean,
        requireRevision: Boolean,
    ): ItemStateLoadResult {
        val errors = mutableListOf<String>()
        val revision = if (requireRevision) container.readRequiredLong(STATE_REVISION_KEY, errors) else 0L
        val originalLifetime = container.readOptionalLong(STATE_ORIGINAL_LIFETIME_SECONDS_KEY, errors)
        val elapsedLifetime = container.readRequiredLong(STATE_ELAPSED_LIFETIME_SECONDS_KEY, errors)
        val ownerUuid = container.readOptionalUuid(STATE_OWNER_UUID_KEY, errors)
        val eligibleOwnerUuids = container.readOptionalUuidList(STATE_ELIGIBLE_OWNER_UUIDS_KEY, errors)
        val protectionSeconds = container.readOptionalLong(STATE_PROTECTION_SECONDS_KEY, errors)
        val virtualAmount = container.readOptionalLong(STATE_VIRTUAL_AMOUNT_KEY, errors)
        if (originalLifetime != null && originalLifetime != -1L && originalLifetime <= 0L) {
            errors += "$STATE_ORIGINAL_LIFETIME_SECONDS_KEY: must be -1 or positive"
        }
        if (elapsedLifetime != null && elapsedLifetime < 0L) {
            errors += "$STATE_ELAPSED_LIFETIME_SECONDS_KEY: must not be negative"
        }
        if (isExpiredState(originalLifetime, elapsedLifetime)) {
            errors += "$STATE_ELAPSED_LIFETIME_SECONDS_KEY: expired state must not be persisted"
        }
        validateTimeValue(STATE_PROTECTION_SECONDS_KEY, protectionSeconds, allowZero = false, errors)
        if (virtualAmount != null && virtualAmount <= 0) {
            errors += "$STATE_VIRTUAL_AMOUNT_KEY: must be positive"
        }
        if (revision != null && requireRevision && revision <= 0) {
            errors += "$STATE_REVISION_KEY: must be positive"
        }
        val presence =
            setOf(
                container.hasAnyType(STATE_OWNER_UUID_KEY),
                container.hasAnyType(STATE_ELIGIBLE_OWNER_UUIDS_KEY),
                container.hasAnyType(STATE_PROTECTION_SECONDS_KEY),
            )
        if (presence.size != 1) {
            errors += "state owner, eligible owners and protection must either all be present or all be absent"
        }
        if (ownerUuid != null && eligibleOwnerUuids != null && ownerUuid !in eligibleOwnerUuids) {
            errors += "state primary owner must be included in eligible owners"
        }
        return if (errors.isNotEmpty()) {
            ItemStateLoadResult.Invalid(errors)
        } else {
            ItemStateLoadResult.Loaded(
                ItemState(
                    ownership =
                        if (ownerUuid != null && eligibleOwnerUuids != null && protectionSeconds != null) {
                            ItemOwnership(ownerUuid, protectionSeconds, eligibleOwnerUuids)
                        } else {
                            null
                        },
                    originalLifetimeSeconds = originalLifetime,
                    elapsedLifetimeSeconds = requireNotNull(elapsedLifetime),
                    virtualAmount = virtualAmount?.let(VirtualItemAmount::of),
                ),
                requiresSchemaUpgrade = requiresSchemaUpgrade,
                revision = requireNotNull(revision),
            )
        }
    }

    private fun isExpiredState(
        originalLifetime: Long?,
        elapsedLifetime: Long?,
    ): Boolean =
        originalLifetime != null &&
            originalLifetime > 0 &&
            elapsedLifetime != null &&
            elapsedLifetime >= originalLifetime

    private fun loadSchemaTwo(container: PersistentDataContainer): ItemStateLoadResult {
        val errors = mutableListOf<String>()
        val ageSeconds = container.readRequiredLong(STATE_AGE_SECONDS_KEY, errors)
        val lifetimeSeconds = container.readOptionalLong(STATE_LIFETIME_SECONDS_KEY, errors)
        val ownerUuid = container.readOptionalUuid(STATE_OWNER_UUID_KEY, errors)
        val protectionSeconds = container.readOptionalLong(STATE_PROTECTION_SECONDS_KEY, errors)
        validateTimeValue(STATE_AGE_SECONDS_KEY, ageSeconds, allowZero = true, errors)
        validateTimeValue(STATE_LIFETIME_SECONDS_KEY, lifetimeSeconds, allowZero = false, errors)
        validateTimeValue(STATE_PROTECTION_SECONDS_KEY, protectionSeconds, allowZero = false, errors)
        if (container.hasAnyType(STATE_OWNER_UUID_KEY) != container.hasAnyType(STATE_PROTECTION_SECONDS_KEY)) {
            errors += "state owner and protection must either both be present or both be absent"
        }
        if (errors.isNotEmpty()) return ItemStateLoadResult.Invalid(errors)
        return ItemStateLoadResult.Loaded(
            ItemState(
                ownership =
                    if (ownerUuid != null && protectionSeconds != null) ItemOwnership(ownerUuid, protectionSeconds) else null,
                originalLifetimeSeconds = lifetimeSeconds,
                elapsedLifetimeSeconds = requireNotNull(ageSeconds),
            ),
            requiresSchemaUpgrade = true,
            elapsedSecondsForLifetimeMigration = if (lifetimeSeconds == null) requireNotNull(ageSeconds) else 0,
        )
    }

    private fun loadSchemaOne(
        container: PersistentDataContainer,
        nowEpochMillis: Long,
    ): ItemStateLoadResult {
        val errors = mutableListOf<String>()
        val ageTicks = container.readRequiredLong(STATE_AGE_TICKS_KEY, errors)
        val lifetimeTicks = container.readOptionalLong(STATE_LIFETIME_TICKS_KEY, errors)
        val ownerUuid = container.readOptionalUuid(STATE_OWNER_UUID_KEY, errors)
        val protectionUntil = container.readOptionalLong(STATE_PROTECTION_UNTIL_KEY, errors)
        validateTimeValue(STATE_AGE_TICKS_KEY, ageTicks, allowZero = true, errors)
        validateTimeValue(STATE_LIFETIME_TICKS_KEY, lifetimeTicks, allowZero = false, errors)
        if (protectionUntil != null && protectionUntil <= 0) {
            errors += "$STATE_PROTECTION_UNTIL_KEY: must be positive"
        }
        val ownerPresent = container.hasAnyType(STATE_OWNER_UUID_KEY)
        val protectionPresent = container.hasAnyType(STATE_PROTECTION_UNTIL_KEY)
        if (ownerPresent != protectionPresent) errors += "state owner and protection expiry must either both be present or both be absent"
        if (errors.isNotEmpty()) return ItemStateLoadResult.Invalid(errors)

        val remainingMillis = protectionUntil?.let { expiry -> if (expiry <= nowEpochMillis) 0 else expiry - nowEpochMillis } ?: 0
        val remainingSeconds = if (remainingMillis <= 0) 0 else (remainingMillis + 999) / 1_000
        val ownership = if (ownerUuid != null && remainingSeconds > 0) ItemOwnership(ownerUuid, remainingSeconds) else null
        val originalLifetime = lifetimeTicks?.let { (it + TICKS_PER_SECOND - 1) / TICKS_PER_SECOND }
        val elapsedLifetime = requireNotNull(ageTicks) / TICKS_PER_SECOND
        return ItemStateLoadResult.Loaded(
            ItemState(
                ownership = ownership,
                originalLifetimeSeconds = originalLifetime,
                elapsedLifetimeSeconds = elapsedLifetime,
            ),
            requiresSchemaUpgrade = true,
            elapsedSecondsForLifetimeMigration =
                if (lifetimeTicks == null) elapsedLifetime else 0,
        )
    }

    private fun loadLegacy(container: PersistentDataContainer): ItemStateLoadResult {
        val legacyKeys = listOf(LEGACY_AGE_KEY, LEGACY_AMOUNT_KEY, LEGACY_OWNER_KEY, LEGACY_OWNER_TIME_KEY)
        return if (legacyKeys.none { container.hasAnyType(it) }) {
            ItemStateLoadResult.Absent
        } else {
            val errors = mutableListOf<String>()
            val age = container.readOptionalLong(LEGACY_AGE_KEY, errors)
            val amount = container.readOptionalLong(LEGACY_AMOUNT_KEY, errors)
            val ownerUuid = container.readOptionalUuid(LEGACY_OWNER_KEY, errors)
            val ownerTime = container.readOptionalLong(LEGACY_OWNER_TIME_KEY, errors)

            validateTimeValue(LEGACY_AGE_KEY, age, allowZero = true, errors)
            validateLegacyAmount(amount, errors)
            validateTimeValue(LEGACY_OWNER_TIME_KEY, ownerTime, allowZero = true, errors)
            val ownerPresent = container.hasAnyType(LEGACY_OWNER_KEY)
            val ownerTimePresent = container.hasAnyType(LEGACY_OWNER_TIME_KEY)
            if (ownerPresent != ownerTimePresent) {
                errors += "legacy owner and ownertime must either both be present or both be absent"
            }
            if (errors.isNotEmpty()) {
                ItemStateLoadResult.Invalid(errors.toList())
            } else {
                ItemStateLoadResult.Legacy(
                    LegacyItemState(
                        age = age,
                        amount = amount,
                        ownerUuid = ownerUuid,
                        ownerTime = ownerTime,
                    ),
                )
            }
        }
    }

    private const val CURRENT_SCHEMA_VERSION = 8
    private const val SCHEMA_VERSION_SEVEN = 7
    private const val SCHEMA_VERSION_SIX = 6
    private const val SCHEMA_VERSION_FIVE = 5
    private const val SCHEMA_VERSION_FOUR = 4
    private const val SCHEMA_VERSION_THREE = 3
    private const val SCHEMA_VERSION_TWO = 2
    private const val SCHEMA_VERSION_ONE = 1
    private const val TICKS_PER_SECOND = 20L

    @Suppress("DEPRECATION")
    private val STATE_SCHEMA_VERSION_KEY = NamespacedKey("itemdropv2", "state-schema-version")

    @Suppress("DEPRECATION")
    private val STATE_REVISION_KEY = NamespacedKey("itemdropv2", "state-revision")

    @Suppress("DEPRECATION")
    private val STATE_OWNER_UUID_KEY = NamespacedKey("itemdropv2", "state-owner-uuid")

    @Suppress("DEPRECATION")
    private val STATE_ELIGIBLE_OWNER_UUIDS_KEY = NamespacedKey("itemdropv2", "state-eligible-owner-uuids")

    @Suppress("DEPRECATION")
    private val STATE_PROTECTION_UNTIL_KEY =
        NamespacedKey("itemdropv2", "state-protection-until-epoch-millis")

    @Suppress("DEPRECATION")
    private val STATE_PROTECTION_SECONDS_KEY = NamespacedKey("itemdropv2", "state-protection-seconds-remaining")

    @Suppress("DEPRECATION")
    private val STATE_AGE_TICKS_KEY = NamespacedKey("itemdropv2", "state-age-ticks")

    @Suppress("DEPRECATION")
    private val STATE_LIFETIME_TICKS_KEY = NamespacedKey("itemdropv2", "state-lifetime-ticks")

    @Suppress("DEPRECATION")
    private val STATE_AGE_SECONDS_KEY = NamespacedKey("itemdropv2", "state-age-seconds")

    @Suppress("DEPRECATION")
    private val STATE_LIFETIME_SECONDS_KEY = NamespacedKey("itemdropv2", "state-lifetime-seconds")

    @Suppress("DEPRECATION")
    private val STATE_REMAINING_LIFETIME_SECONDS_KEY =
        NamespacedKey("itemdropv2", "state-remaining-lifetime-seconds")

    @Suppress("DEPRECATION")
    private val STATE_ORIGINAL_LIFETIME_SECONDS_KEY =
        NamespacedKey("itemdropv2", "state-original-lifetime-seconds")

    @Suppress("DEPRECATION")
    private val STATE_ELAPSED_LIFETIME_SECONDS_KEY =
        NamespacedKey("itemdropv2", "state-elapsed-lifetime-seconds")

    @Suppress("DEPRECATION")
    private val STATE_VIRTUAL_AMOUNT_KEY = NamespacedKey("itemdropv2", "state-virtual-amount")

    private val PREVIOUS_PAYLOAD_KEYS =
        listOf(
            STATE_PROTECTION_UNTIL_KEY,
            STATE_AGE_TICKS_KEY,
            STATE_LIFETIME_TICKS_KEY,
            STATE_AGE_SECONDS_KEY,
            STATE_LIFETIME_SECONDS_KEY,
            STATE_REMAINING_LIFETIME_SECONDS_KEY,
        )

    private val NEW_PAYLOAD_KEYS =
        listOf(
            STATE_OWNER_UUID_KEY,
            STATE_ELIGIBLE_OWNER_UUIDS_KEY,
            STATE_PROTECTION_SECONDS_KEY,
            STATE_AGE_SECONDS_KEY,
            STATE_LIFETIME_SECONDS_KEY,
            STATE_REMAINING_LIFETIME_SECONDS_KEY,
            STATE_ORIGINAL_LIFETIME_SECONDS_KEY,
            STATE_ELAPSED_LIFETIME_SECONDS_KEY,
            STATE_VIRTUAL_AMOUNT_KEY,
            STATE_REVISION_KEY,
            STATE_PROTECTION_UNTIL_KEY,
            STATE_AGE_TICKS_KEY,
            STATE_LIFETIME_TICKS_KEY,
        )

    @Suppress("DEPRECATION")
    private val LEGACY_AGE_KEY = NamespacedKey("itemdropv2", "age")

    @Suppress("DEPRECATION")
    private val LEGACY_AMOUNT_KEY = NamespacedKey("itemdropv2", "amount")

    @Suppress("DEPRECATION")
    private val LEGACY_OWNER_KEY = NamespacedKey("itemdropv2", "owner")

    @Suppress("DEPRECATION")
    private val LEGACY_OWNER_TIME_KEY = NamespacedKey("itemdropv2", "ownertime")

    private val ALL_ITEM_STATE_KEYS =
        listOf(STATE_SCHEMA_VERSION_KEY) +
            NEW_PAYLOAD_KEYS +
            listOf(LEGACY_AGE_KEY, LEGACY_AMOUNT_KEY, LEGACY_OWNER_KEY, LEGACY_OWNER_TIME_KEY)
}

private fun validateTimeValue(
    key: NamespacedKey,
    value: Long?,
    allowZero: Boolean,
    errors: MutableList<String>,
) {
    if (value == null) return
    when {
        value < 0 -> errors += "$key: must not be negative"
        !allowZero && value == 0L -> errors += "$key: must be positive"
        value > Int.MAX_VALUE.toLong() -> errors += "$key: exceeds supported time range"
    }
}

private fun validateLegacyAmount(
    value: Long?,
    errors: MutableList<String>,
) {
    if (value == null) return
    when {
        value <= 0 -> errors += "itemdropv2:amount: must be positive"
        value > Int.MAX_VALUE.toLong() -> errors += "itemdropv2:amount: exceeds supported amount range"
    }
}

private fun PersistentDataContainer.readRequiredLong(
    key: NamespacedKey,
    errors: MutableList<String>,
): Long? =
    if (!hasAnyType(key)) {
        errors += "$key: required LONG is missing"
        null
    } else {
        readOptionalLong(key, errors)
    }

private fun PersistentDataContainer.readOptionalLong(
    key: NamespacedKey,
    errors: MutableList<String>,
): Long? =
    when {
        !hasAnyType(key) -> null
        !has(key, PersistentDataType.LONG) -> {
            errors += "$key: expected LONG"
            null
        }
        else -> get(key, PersistentDataType.LONG)
    }

private fun PersistentDataContainer.readOptionalUuid(
    key: NamespacedKey,
    errors: MutableList<String>,
): UUID? =
    when {
        !hasAnyType(key) -> null
        !has(key, PersistentDataType.STRING) -> {
            errors += "$key: expected STRING"
            null
        }
        else ->
            try {
                UUID.fromString(get(key, PersistentDataType.STRING))
            } catch (_: IllegalArgumentException) {
                errors += "$key: expected UUID string"
                null
            }
    }

private fun PersistentDataContainer.readOptionalUuidList(
    key: NamespacedKey,
    errors: MutableList<String>,
): List<UUID>? =
    when {
        !hasAnyType(key) -> null
        !has(key, PersistentDataType.STRING) -> {
            errors += "$key: expected STRING"
            null
        }
        else -> {
            val raw = get(key, PersistentDataType.STRING).orEmpty()
            val values = raw.split(',')
            val parsed =
                values.mapIndexedNotNull { index, value ->
                    try {
                        UUID.fromString(value).takeIf { it.toString() == value.lowercase() }
                            ?: run {
                                errors += "$key[$index]: expected canonical UUID string"
                                null
                            }
                    } catch (_: IllegalArgumentException) {
                        errors += "$key[$index]: expected UUID string"
                        null
                    }
                }
            when {
                raw.isBlank() -> errors += "$key: must not be empty"
                values.size > ItemOwnership.MAX_ELIGIBLE_OWNERS -> errors += "$key: exceeds supported owner count"
                parsed.distinct().size != parsed.size -> errors += "$key: must not contain duplicate UUIDs"
            }
            parsed.takeIf { errors.none { error -> error.startsWith(key.toString()) } }
        }
    }

private fun PersistentDataContainer.hasAnyType(key: NamespacedKey): Boolean =
    has(key, PersistentDataType.BYTE) ||
        has(key, PersistentDataType.SHORT) ||
        has(key, PersistentDataType.INTEGER) ||
        has(key, PersistentDataType.LONG) ||
        has(key, PersistentDataType.FLOAT) ||
        has(key, PersistentDataType.DOUBLE) ||
        has(key, PersistentDataType.STRING) ||
        has(key, PersistentDataType.BYTE_ARRAY) ||
        has(key, PersistentDataType.INTEGER_ARRAY) ||
        has(key, PersistentDataType.LONG_ARRAY) ||
        has(key, PersistentDataType.TAG_CONTAINER)

private fun PersistentDataContainer.setOrRemove(
    key: NamespacedKey,
    value: Long?,
) {
    if (value == null) remove(key) else set(key, PersistentDataType.LONG, value)
}

private fun PersistentDataContainer.setOrRemove(
    key: NamespacedKey,
    value: String?,
) {
    if (value == null) remove(key) else set(key, PersistentDataType.STRING, value)
}
