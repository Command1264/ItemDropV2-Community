package com.github.command1264.itemdropv2.core

public class DisplayTemplate private constructor(
    private val raw: String,
) {
    public val usesProtectionRemaining: Boolean = raw.contains(PROTECTION_REMAINING_PLACEHOLDER)
    public val usesLifetimeRemaining: Boolean = raw.contains(LIFETIME_REMAINING_PLACEHOLDER)
    public val usesLifetimeElapsed: Boolean = raw.contains(LIFETIME_ELAPSED_PLACEHOLDER)

    public fun render(
        itemDisplayName: String,
        amount: Long,
        placeholders: ItemDisplayPlaceholderValues = ItemDisplayPlaceholderValues.EMPTY,
    ): String =
        raw
            .replace(ITEM_DISPLAY_NAME_PLACEHOLDER, itemDisplayName)
            .replace(AMOUNT_PLACEHOLDER, amount.toString())
            .replace(OWNER_PLACEHOLDER, placeholders.owner)
            .replace(OWNER_COUNT_PLACEHOLDER, placeholders.ownerCount.toString())
            .replace(OTHER_OWNER_COUNT_PLACEHOLDER, placeholders.otherOwnerCount.toString())
            .replace(PROTECTION_REMAINING_PLACEHOLDER, placeholders.protectionRemaining)
            .replace(LIFETIME_ELAPSED_PLACEHOLDER, placeholders.lifetimeElapsed)
            .replace(LIFETIME_REMAINING_PLACEHOLDER, placeholders.lifetimeRemaining)

    public companion object {
        public const val MAX_LENGTH: Int = 256
        public const val ITEM_DISPLAY_NAME_PLACEHOLDER: String = "%item_display_name%"
        public const val AMOUNT_PLACEHOLDER: String = "%amount%"
        public const val OWNER_PLACEHOLDER: String = "%owner%"
        public const val OWNER_COUNT_PLACEHOLDER: String = "%owner_count%"
        public const val OTHER_OWNER_COUNT_PLACEHOLDER: String = "%other_owner_count%"
        public const val PROTECTION_REMAINING_PLACEHOLDER: String = "%protection_remaining%"
        public const val LIFETIME_ELAPSED_PLACEHOLDER: String = "%lifetime_elapsed%"
        public const val LIFETIME_REMAINING_PLACEHOLDER: String = "%lifetime_remaining%"

        private val placeholderPattern = Regex("%[A-Za-z0-9_]+%")
        private val supportedPlaceholders =
            setOf(
                ITEM_DISPLAY_NAME_PLACEHOLDER,
                AMOUNT_PLACEHOLDER,
                OWNER_PLACEHOLDER,
                OWNER_COUNT_PLACEHOLDER,
                OTHER_OWNER_COUNT_PLACEHOLDER,
                PROTECTION_REMAINING_PLACEHOLDER,
                LIFETIME_ELAPSED_PLACEHOLDER,
                LIFETIME_REMAINING_PLACEHOLDER,
            )

        public fun parse(raw: String): DisplayTemplateParseResult {
            val errors = mutableListOf<String>()
            if (raw.length > MAX_LENGTH) {
                errors += "template must not exceed $MAX_LENGTH characters"
            }
            if (raw.any(Char::isISOControl)) {
                errors += "template must not contain control characters"
            }
            placeholderPattern
                .findAll(raw)
                .map { it.value }
                .filterNot { placeholder ->
                    placeholder in supportedPlaceholders || placeholder.isExternalPlaceholder()
                }.distinct()
                .forEach { errors += "unsupported placeholder: $it" }
            if (!raw.contains(ITEM_DISPLAY_NAME_PLACEHOLDER)) {
                errors += "template must contain $ITEM_DISPLAY_NAME_PLACEHOLDER"
            }
            return if (errors.isEmpty()) {
                DisplayTemplateParseResult.Valid(DisplayTemplate(raw))
            } else {
                DisplayTemplateParseResult.Invalid(errors.toList())
            }
        }

        private fun String.isExternalPlaceholder(): Boolean {
            val content = removeSurrounding("%")
            val separator = content.indexOf('_')
            return separator > 0 && separator < content.lastIndex
        }
    }
}

public data class ItemDisplayPlaceholderValues(
    public val owner: String,
    public val ownerCount: Int,
    public val otherOwnerCount: Int,
    public val protectionRemaining: String,
    public val lifetimeElapsed: String = "",
    public val lifetimeRemaining: String,
) {
    init {
        require(ownerCount >= 0) { "owner count must not be negative" }
        require(otherOwnerCount >= 0) { "other owner count must not be negative" }
        require(otherOwnerCount == (ownerCount - 1).coerceAtLeast(0)) {
            "other owner count must match the owner count"
        }
    }

    public companion object {
        public val EMPTY: ItemDisplayPlaceholderValues =
            ItemDisplayPlaceholderValues(
                owner = "",
                ownerCount = 0,
                otherOwnerCount = 0,
                protectionRemaining = "0",
                lifetimeElapsed = "",
                lifetimeRemaining = "",
            )
    }
}

public sealed interface DisplayTemplateParseResult {
    public data class Valid(
        public val template: DisplayTemplate,
    ) : DisplayTemplateParseResult

    public data class Invalid(
        public val errors: List<String>,
    ) : DisplayTemplateParseResult
}

public class OwnerDisplayTemplate private constructor(
    private val raw: String,
) {
    public val supportsAdditionalOwnerCount: Boolean = raw.contains(ADDITIONAL_OWNER_COUNT_PLACEHOLDER)
    public val usesProtectionRemaining: Boolean = raw.contains(PROTECTION_REMAINING_PLACEHOLDER)

    public fun render(
        playerName: String,
        additionalOwnerCount: Int = 0,
        protectionSecondsRemaining: Long = 0,
    ): String =
        raw
            .replace(PLAYER_NAME_PLACEHOLDER, playerName)
            .replace(ADDITIONAL_OWNER_COUNT_PLACEHOLDER, additionalOwnerCount.toString())
            .replace(
                PROTECTION_REMAINING_PLACEHOLDER,
                DurationDisplayFormatter.format(protectionSecondsRemaining),
            )

    public companion object {
        public const val PLAYER_NAME_PLACEHOLDER: String = "%player_name%"
        public const val ADDITIONAL_OWNER_COUNT_PLACEHOLDER: String = "%additional_owner_count%"
        public const val PROTECTION_REMAINING_PLACEHOLDER: String = DisplayTemplate.PROTECTION_REMAINING_PLACEHOLDER
        private val placeholderPattern = Regex("%[A-Za-z0-9_]+%")

        public fun parse(raw: String): OwnerDisplayTemplateParseResult {
            val errors = mutableListOf<String>()
            if (raw.length > DisplayTemplate.MAX_LENGTH) {
                errors += "template must not exceed ${DisplayTemplate.MAX_LENGTH} characters"
            }
            if (raw.any(Char::isISOControl)) {
                errors += "template must not contain control characters"
            }
            placeholderPattern
                .findAll(raw)
                .map { it.value }
                .filterNot { placeholder ->
                    placeholder == PLAYER_NAME_PLACEHOLDER ||
                        placeholder == ADDITIONAL_OWNER_COUNT_PLACEHOLDER ||
                        placeholder == PROTECTION_REMAINING_PLACEHOLDER ||
                        placeholder.isExternalPlaceholder()
                }.distinct()
                .forEach { errors += "unsupported placeholder: $it" }
            if (!raw.contains(PLAYER_NAME_PLACEHOLDER)) {
                errors += "template must contain $PLAYER_NAME_PLACEHOLDER"
            }
            return if (errors.isEmpty()) {
                OwnerDisplayTemplateParseResult.Valid(OwnerDisplayTemplate(raw))
            } else {
                OwnerDisplayTemplateParseResult.Invalid(errors.toList())
            }
        }

        private fun String.isExternalPlaceholder(): Boolean {
            val content = removeSurrounding("%")
            val separator = content.indexOf('_')
            return separator > 0 && separator < content.lastIndex
        }
    }
}

public sealed interface OwnerDisplayTemplateParseResult {
    public data class Valid(
        public val template: OwnerDisplayTemplate,
    ) : OwnerDisplayTemplateParseResult

    public data class Invalid(
        public val errors: List<String>,
    ) : OwnerDisplayTemplateParseResult
}
