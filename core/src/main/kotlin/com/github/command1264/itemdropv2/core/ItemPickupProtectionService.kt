package com.github.command1264.itemdropv2.core

import java.util.UUID

public sealed interface PickupActor {
    public data class Player(
        public val playerUuid: UUID,
        public val hasPickupPermission: Boolean,
        public val hasOtherPickupPermission: Boolean,
    ) : PickupActor

    public data object NonPlayerEntity : PickupActor

    public data object Inventory : PickupActor
}

public data class ItemPickupRequest(
    public val itemEntityId: UUID,
    public val worldName: String,
    public val actor: PickupActor,
) {
    init {
        require(worldName.isNotBlank()) { "worldName must not be blank" }
        require(worldName.none(Char::isISOControl)) { "worldName must not contain control characters" }
    }
}

public enum class PickupDeniedReason {
    NO_PICKUP_PERMISSION,
    OTHER_OWNER,
}

public sealed interface ItemPickupOutcome {
    public data object Allowed : ItemPickupOutcome

    public data class Denied(
        public val reason: PickupDeniedReason,
        public val ownerUuid: UUID? = null,
        public val protectionSecondsRemaining: Long? = null,
        public val eligibleOwnerUuids: List<UUID> = ownerUuid?.let(::listOf).orEmpty(),
    ) : ItemPickupOutcome

    public data class Failed(
        public val errorType: String,
    ) : ItemPickupOutcome
}

public class ItemPickupProtectionService(
    private val repository: ItemStateRepository,
    private val settingsRepository: ItemDisplaySettingsRepository,
) {
    public fun evaluate(request: ItemPickupRequest): ItemPickupOutcome {
        val settings = settingsRepository.settings()
        if (request.worldName in settings.blockedWorlds) return ItemPickupOutcome.Allowed

        return when (val loaded = repository.load(request.itemEntityId)) {
            ItemStateLoadResult.Absent -> ItemPickupOutcome.Allowed
            is ItemStateLoadResult.Loaded -> evaluateState(loaded.state, request.actor, settings.ownership.pickup)
            is ItemStateLoadResult.Legacy -> ItemPickupOutcome.Failed("LegacyStateRequiresMigration")
            is ItemStateLoadResult.Invalid -> ItemPickupOutcome.Failed("InvalidState")
            is ItemStateLoadResult.UnsupportedSchema -> ItemPickupOutcome.Failed("UnsupportedSchema:${loaded.actualVersion}")
            ItemStateLoadResult.MissingTarget -> ItemPickupOutcome.Failed("MissingTarget")
            is ItemStateLoadResult.Failed -> ItemPickupOutcome.Failed(loaded.errorType)
        }
    }

    private fun evaluateState(
        state: ItemState,
        actor: PickupActor,
        settings: ItemPickupSettings,
    ): ItemPickupOutcome {
        val ownership = state.ownership
        return when (actor) {
            is PickupActor.Player -> evaluatePlayer(actor, ownership)
            PickupActor.NonPlayerEntity -> ownership?.denied() ?: ItemPickupOutcome.Allowed
            PickupActor.Inventory ->
                if (ownership == null || settings.allowHopperPickup) {
                    ItemPickupOutcome.Allowed
                } else {
                    ownership.denied()
                }
        }
    }

    private fun evaluatePlayer(
        player: PickupActor.Player,
        ownership: ItemOwnership?,
    ): ItemPickupOutcome =
        when {
            !player.hasPickupPermission -> ItemPickupOutcome.Denied(PickupDeniedReason.NO_PICKUP_PERMISSION)
            ownership == null -> ItemPickupOutcome.Allowed
            ownership.canPickup(player.playerUuid) -> ItemPickupOutcome.Allowed
            player.hasOtherPickupPermission -> ItemPickupOutcome.Allowed
            else -> ownership.denied()
        }

    private fun ItemOwnership.denied(): ItemPickupOutcome.Denied =
        ItemPickupOutcome.Denied(
            reason = PickupDeniedReason.OTHER_OWNER,
            ownerUuid = ownerUuid,
            protectionSecondsRemaining = protectionSecondsRemaining,
            eligibleOwnerUuids = eligibleOwnerUuids,
        )
}
