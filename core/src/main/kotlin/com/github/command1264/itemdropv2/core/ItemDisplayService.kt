package com.github.command1264.itemdropv2.core

public fun interface ItemDisplaySettingsRepository {
    public fun settings(): ItemDisplaySettings
}

public fun interface ItemDisplayTextExpander {
    public fun expand(
        playerId: java.util.UUID?,
        text: String,
    ): String
}

public fun interface ItemPresentationView {
    public fun present(presentation: ItemPresentation): PresentationResult

    public fun clear(entityId: java.util.UUID): PresentationResult = PresentationResult.MissingTarget
}

public class ItemDisplayService(
    private val settingsRepository: ItemDisplaySettingsRepository,
    private val view: ItemPresentationView,
    private val textExpander: ItemDisplayTextExpander = ItemDisplayTextExpander { _, text -> text },
) {
    public fun display(request: ItemDisplayRequest): ItemDisplayOutcome = display(request, view)

    @InternalItemDropApi
    public fun display(
        request: ItemDisplayRequest,
        presentationView: ItemPresentationView,
    ): ItemDisplayOutcome {
        val settings = settingsRepository.settings()
        return when {
            !settings.enabled -> clear(request, ItemDisplayIgnoredReason.DISABLED, presentationView)
            request.worldName in settings.blockedWorlds ->
                clear(request, ItemDisplayIgnoredReason.BLOCKED_WORLD, presentationView)
            else -> displayEnabled(request, settings, presentationView)
        }
    }

    private fun clear(
        request: ItemDisplayRequest,
        reason: ItemDisplayIgnoredReason,
        presentationView: ItemPresentationView,
    ): ItemDisplayOutcome = ItemDisplayOutcome.Cleared(reason, presentationView.clear(request.entityId))

    private fun displayEnabled(
        request: ItemDisplayRequest,
        settings: ItemDisplaySettings,
        presentationView: ItemPresentationView,
    ): ItemDisplayOutcome {
        val template =
            if (request.amount == 1L) {
                settings.singleItemTemplate
            } else {
                settings.multipleItemTemplate
            }
        val markedItemName = markedItemName(request, settings)
        val itemText =
            template.render(
                markedItemName ?: displayedItemName(request, settings),
                request.amount,
                placeholderValues(request, settings),
            )
        val rendered = renderOwnerPrefix(request, settings) + itemText
        val expandedMarked = textExpander.expand(request.placeholderPlayerId, rendered)
        val expanded =
            if (markedItemName == null) {
                expandedMarked
            } else {
                expandedMarked.replace(ItemClientTranslation.MARKER, request.itemName)
            }
        val validationError = validateExpanded(expanded, expandedMarked, markedItemName != null)
        if (validationError != null) {
            return ItemDisplayOutcome.Rejected(validationError)
        }
        val clientTranslation =
            request.translationKey?.let { translationKey ->
                ItemClientTranslation(translationKey, expandedMarked)
            }
        val presentation =
            ItemPresentation(
                request.entityId,
                expanded,
                visible = true,
                clientTranslation = clientTranslation,
            )
        return ItemDisplayOutcome.Presented(presentationView.present(presentation))
    }

    private fun displayedItemName(
        request: ItemDisplayRequest,
        settings: ItemDisplaySettings,
    ): String =
        if (settings.rarityDisplayEnabled && !request.customNameHasColor) {
            request.rarity.legacyColorCode + request.itemName
        } else {
            request.itemName
        }

    private fun markedItemName(
        request: ItemDisplayRequest,
        settings: ItemDisplaySettings,
    ): String? =
        request.translationKey?.let {
            if (settings.rarityDisplayEnabled) {
                request.rarity.legacyColorCode + ItemClientTranslation.MARKER
            } else {
                ItemClientTranslation.MARKER
            }
        }

    private fun renderOwnerPrefix(
        request: ItemDisplayRequest,
        settings: ItemDisplaySettings,
    ): String =
        request.ownerName
            ?.let { ownerName ->
                val ownerTemplate =
                    if (request.additionalOwnerCount == 0) {
                        settings.ownership.display.singleOwnerPrefixTemplate
                    } else {
                        settings.ownership.display.multipleOwnersPrefixTemplate
                    }
                ownerTemplate.render(
                    ownerName,
                    request.additionalOwnerCount,
                    request.protectionSecondsRemaining,
                )
            }.orEmpty()

    private fun validateExpanded(
        expanded: String,
        expandedMarked: String,
        translationExpected: Boolean,
    ): String? =
        when {
            expanded.isBlank() -> "rendered presentation must not be blank"
            expanded.length > DisplayTemplate.MAX_LENGTH ->
                "expanded presentation exceeds ${DisplayTemplate.MAX_LENGTH} characters"
            expanded.any(Char::isISOControl) -> "expanded presentation must not contain control characters"
            translationExpected && !expandedMarked.contains(ItemClientTranslation.MARKER) ->
                "external placeholder expansion removed the item translation marker"
            else -> null
        }

    private fun placeholderValues(
        request: ItemDisplayRequest,
        settings: ItemDisplaySettings,
    ): ItemDisplayPlaceholderValues =
        ItemDisplayPlaceholderValues(
            owner = request.ownerName ?: settings.placeholders.noOwner,
            ownerCount = if (request.ownerName == null) 0 else request.additionalOwnerCount + 1,
            otherOwnerCount = request.additionalOwnerCount,
            protectionRemaining = DurationDisplayFormatter.format(request.protectionSecondsRemaining),
            lifetimeElapsed =
                request.lifetimeSecondsElapsed?.let(DurationDisplayFormatter::format)
                    ?: settings.placeholders.lifetimeUnknown,
            lifetimeRemaining =
                when (request.lifetimeSecondsRemaining) {
                    null -> settings.placeholders.lifetimeUnknown
                    ItemLifetimeSettings.NEVER_EXPIRES -> settings.placeholders.lifetimePermanent
                    else -> DurationDisplayFormatter.format(request.lifetimeSecondsRemaining)
                },
        )
}
