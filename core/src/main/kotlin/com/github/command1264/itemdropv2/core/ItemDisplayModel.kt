package com.github.command1264.itemdropv2.core

import java.util.Locale
import java.util.UUID

public data class ItemDisplaySettings(
    public val enabled: Boolean,
    public val blockedWorlds: Set<String>,
    public val singleItemTemplate: DisplayTemplate,
    public val multipleItemTemplate: DisplayTemplate,
    public val minecraftLanguage: MinecraftLanguageCode = MinecraftLanguageCode.EN_US,
    public val messageLanguage: PluginMessageLanguage = PluginMessageLanguage.ZH_TW,
    public val ownership: ItemOwnershipSettings = ItemOwnershipSettings(),
    public val processing: ItemProcessingSettings = ItemProcessingSettings(),
    public val lifetime: ItemLifetimeSettings = ItemLifetimeSettings(),
    public val merge: ItemMergeSettings = ItemMergeSettings(),
    public val virtualStacking: VirtualItemStackingSettings = VirtualItemStackingSettings(),
    public val rarityDisplayEnabled: Boolean = true,
    public val placeholders: ItemDisplayPlaceholderSettings = ItemDisplayPlaceholderSettings(),
    /** Null means the packaged edition does not expose or parse this Pro-only setting. */
    public val paperClientSideTranslationEnabled: Boolean? = null,
) {
    init {
        require(blockedWorlds.all { it.isNotBlank() }) { "blocked world names must not be blank" }
        require(blockedWorlds.none { world -> world.any(Char::isISOControl) }) {
            "blocked world names must not contain control characters"
        }
    }
}

public data class ItemProcessingSettings(
    public val maximumItemsPerTick: Int = DEFAULT_MAXIMUM_ITEMS_PER_TICK,
) {
    init {
        require(maximumItemsPerTick > 0) { "maximum items per tick must be positive" }
    }

    public companion object {
        public const val DEFAULT_MAXIMUM_ITEMS_PER_TICK: Int = 256
    }
}

public data class ItemDisplayPlaceholderSettings(
    public val noOwner: String = DEFAULT_NO_OWNER,
    public val lifetimePermanent: String = DEFAULT_LIFETIME_PERMANENT,
    public val lifetimeUnknown: String = DEFAULT_LIFETIME_UNKNOWN,
) {
    init {
        validateLabel(noOwner, "no-owner", allowBlank = true)
        validateLabel(lifetimePermanent, "lifetime-permanent")
        validateLabel(lifetimeUnknown, "lifetime-unknown")
    }

    private fun validateLabel(
        value: String,
        name: String,
        allowBlank: Boolean = false,
    ) {
        require(allowBlank || value.isNotBlank()) { "$name placeholder label must not be blank" }
        require(value.length <= MAX_LABEL_LENGTH) { "$name placeholder label must not exceed $MAX_LABEL_LENGTH characters" }
        require(value.none(Char::isISOControl)) { "$name placeholder label must not contain control characters" }
    }

    public companion object {
        public const val DEFAULT_NO_OWNER: String = "無"
        public const val DEFAULT_LIFETIME_PERMANENT: String = "永久"
        public const val DEFAULT_LIFETIME_UNKNOWN: String = "未知"
        public const val MAX_LABEL_LENGTH: Int = 64
    }
}

public data class ItemLifetimeSettings(
    public val defaultLifetimeSeconds: Long = DEFAULT_LIFETIME_SECONDS,
    public val materialLifetimeSeconds: Map<String, Long> = emptyMap(),
) {
    init {
        require(defaultLifetimeSeconds >= NEVER_EXPIRES) {
            "default item lifetime must be -1, 0, or a positive number of seconds"
        }
        require(materialLifetimeSeconds.keys.all { it.matches(MATERIAL_NAME_PATTERN) }) {
            "material lifetime keys must be uppercase Minecraft Material names"
        }
        require(materialLifetimeSeconds.values.all { it >= NEVER_EXPIRES }) {
            "material item lifetimes must be -1, 0, or a positive number of seconds"
        }
    }

    public fun resolve(materialName: String?): Long =
        materialName
            ?.uppercase()
            ?.let(materialLifetimeSeconds::get)
            ?: defaultLifetimeSeconds

    public companion object {
        public const val DEFAULT_LIFETIME_SECONDS: Long = 300
        public const val NEVER_EXPIRES: Long = -1
        private val MATERIAL_NAME_PATTERN = Regex("[A-Z0-9_]+")
    }
}

public data class ItemMergeSettings(
    public val lifetimeStrategy: MergeLifetimeStrategy = MergeLifetimeStrategy.AVERAGE,
    public val ownershipStrategy: MergeOwnershipStrategy = MergeOwnershipStrategy.AVERAGE,
)

public data class VirtualItemStackingSettings(
    public val enabled: Boolean = false,
    public val maximumAmountPerEntity: VirtualItemAmount = DEFAULT_MAXIMUM_AMOUNT,
    public val mergeScheduler: VirtualMergeSchedulerSettings = VirtualMergeSchedulerSettings(),
    public val maximumNativeStacksPerEntity: Long? = null,
    public val unstackableItemsEnabled: Boolean = false,
    public val carrierAmountMode: VirtualCarrierAmountMode = VirtualCarrierAmountMode.PROPORTIONAL,
) {
    init {
        require(maximumNativeStacksPerEntity == null || maximumNativeStacksPerEntity > 0) {
            "maximum native stacks per entity must be positive"
        }
    }

    public fun maximumAmountFor(nativeStackSize: Int): VirtualItemAmount {
        require(nativeStackSize > 0) { "native stack size must be positive" }
        val nativeStacks = maximumNativeStacksPerEntity ?: return maximumAmountPerEntity
        val maximum =
            if (nativeStacks > Long.MAX_VALUE / nativeStackSize) {
                Long.MAX_VALUE
            } else {
                nativeStacks * nativeStackSize
            }
        return VirtualItemAmount.of(maximum)
    }

    public fun manages(nativeStackSize: Int): Boolean {
        require(nativeStackSize > 0) { "native stack size must be positive" }
        return enabled && (nativeStackSize > 1 || unstackableItemsEnabled)
    }

    public fun carrierAmountFor(
        virtualAmount: VirtualItemAmount,
        nativeStackSize: Int,
    ): Int {
        val maximum = maximumAmountFor(nativeStackSize)
        require(virtualAmount.value <= maximum.value) {
            "virtual amount exceeds the configured maximum"
        }
        return carrierAmountMode.carrierAmount(
            virtualAmount = virtualAmount.value,
            maximumAmount = maximum.value,
            nativeStackSize = nativeStackSize,
        )
    }

    public companion object {
        public val DEFAULT_MAXIMUM_AMOUNT: VirtualItemAmount = VirtualItemAmount.of(8_192)
        public const val DEFAULT_MAXIMUM_NATIVE_STACKS: Long = 128
    }
}

public enum class VirtualCarrierAmountMode(
    public val configValue: String,
) {
    PROPORTIONAL("proportional"),
    COUNT_CAPPED("count-capped"),
    MINIMAL("minimal"),
    ;

    internal fun carrierAmount(
        virtualAmount: Long,
        maximumAmount: Long,
        nativeStackSize: Int,
    ): Int =
        when (this) {
            PROPORTIONAL -> proportionalCarrierAmount(virtualAmount, maximumAmount)
            COUNT_CAPPED -> minOf(virtualAmount, MAXIMUM_VISUAL_COUNT.toLong()).toInt()
            MINIMAL ->
                if (virtualAmount == maximumAmount && maximumAmount >= nativeStackSize) {
                    nativeStackSize
                } else {
                    1
                }
        }

    public companion object {
        public const val MAXIMUM_VISUAL_COUNT: Int = 64

        public fun parse(value: String): VirtualCarrierAmountMode? = entries.firstOrNull { mode -> mode.configValue == value }

        private fun proportionalCarrierAmount(
            virtualAmount: Long,
            maximumAmount: Long,
        ): Int {
            val wholeBucket = maximumAmount / MAXIMUM_VISUAL_COUNT
            val bucketRemainder = maximumAmount % MAXIMUM_VISUAL_COUNT
            for (candidate in 1 until MAXIMUM_VISUAL_COUNT) {
                val threshold =
                    wholeBucket * candidate +
                        (bucketRemainder * candidate) / MAXIMUM_VISUAL_COUNT
                if (virtualAmount <= threshold) {
                    return minOf(candidate.toLong(), virtualAmount).toInt()
                }
            }
            return minOf(MAXIMUM_VISUAL_COUNT.toLong(), virtualAmount).toInt()
        }
    }
}

public data class VirtualMergeSchedulerSettings(
    public val comparisonsPerTick: Int = DEFAULT_COMPARISONS_PER_TICK,
    public val eventsPerTick: Int = DEFAULT_EVENTS_PER_TICK,
    public val retryBackoffSeconds: Long = DEFAULT_RETRY_BACKOFF_SECONDS,
) {
    init {
        require(comparisonsPerTick in 1..MAX_COMPARISONS_PER_TICK) {
            "merge comparisons per tick must be between 1 and $MAX_COMPARISONS_PER_TICK"
        }
        require(eventsPerTick in 1..comparisonsPerTick) {
            "merge events per tick must be between 1 and comparisons per tick"
        }
        require(retryBackoffSeconds in 1..MAX_RETRY_BACKOFF_SECONDS) {
            "merge retry backoff must be between 1 and $MAX_RETRY_BACKOFF_SECONDS seconds"
        }
    }

    public companion object {
        public const val DEFAULT_COMPARISONS_PER_TICK: Int = 256
        public const val DEFAULT_EVENTS_PER_TICK: Int = 64
        public const val DEFAULT_RETRY_BACKOFF_SECONDS: Long = 5
        public const val MAX_COMPARISONS_PER_TICK: Int = 65_536
        public const val MAX_RETRY_BACKOFF_SECONDS: Long = 3_600
    }
}

public enum class MergeLifetimeStrategy(
    public val configValue: String,
) {
    AVERAGE("average"),
    MAXIMUM("maximum"),
    MINIMUM("minimum"),
    ;

    public companion object {
        public fun parse(value: String): MergeLifetimeStrategy? =
            entries.firstOrNull { strategy -> strategy.configValue == value.lowercase() }
    }
}

public enum class MergeOwnershipStrategy(
    public val configValue: String,
) {
    AVERAGE("average"),
    MAXIMUM("maximum"),
    MINIMUM("minimum"),
    RESET("reset"),
    ;

    public companion object {
        public fun parse(value: String): MergeOwnershipStrategy? =
            entries.firstOrNull { strategy -> strategy.configValue == value.lowercase(Locale.ROOT) }
    }
}

public data class ItemOwnershipSettings(
    public val enabled: Boolean = true,
    public val protectionSeconds: Long = DEFAULT_PROTECTION_SECONDS,
    public val display: ItemOwnershipDisplaySettings = ItemOwnershipDisplaySettings(),
    public val pickup: ItemPickupSettings = ItemPickupSettings(),
    public val entity: EntityOwnershipSettings = EntityOwnershipSettings(),
) {
    init {
        require(protectionSeconds in 0..MAX_PROTECTION_SECONDS) {
            "ownership protection seconds must be between 0 and $MAX_PROTECTION_SECONDS"
        }
    }

    public companion object {
        public const val DEFAULT_PROTECTION_SECONDS: Long = 30
        public const val MAX_PROTECTION_SECONDS: Long = 604_800
    }
}

public data class EntityOwnershipSettings(
    public val strategy: EntityDamageAttributionStrategy = EntityDamageAttributionStrategy.HIGHEST_DAMAGE,
    public val minimumDamagePercentOfMaxHealth: Double = DEFAULT_MINIMUM_DAMAGE_PERCENT,
    public val combatTimeoutSeconds: Long = DEFAULT_COMBAT_TIMEOUT_SECONDS,
) {
    init {
        require(minimumDamagePercentOfMaxHealth.isFinite() && minimumDamagePercentOfMaxHealth in 0.0..MAX_PERCENT) {
            "minimum entity damage percent must be between 0 and 100"
        }
        require(combatTimeoutSeconds in 1..MAX_COMBAT_TIMEOUT_SECONDS) {
            "entity combat timeout must be between 1 and $MAX_COMBAT_TIMEOUT_SECONDS seconds"
        }
    }

    public companion object {
        public const val DEFAULT_MINIMUM_DAMAGE_PERCENT: Double = 50.0
        public const val DEFAULT_COMBAT_TIMEOUT_SECONDS: Long = 300
        public const val MAX_COMBAT_TIMEOUT_SECONDS: Long = 86_400
        private const val MAX_PERCENT: Double = 100.0
    }
}

public data class ItemOwnershipDisplaySettings(
    public val rotationSeconds: Long = DEFAULT_ROTATION_SECONDS,
    public val singleOwnerPrefixTemplate: OwnerDisplayTemplate = DEFAULT_SINGLE_OWNER_PREFIX_TEMPLATE,
    public val multipleOwnersPrefixTemplate: OwnerDisplayTemplate = DEFAULT_MULTIPLE_OWNERS_PREFIX_TEMPLATE,
) {
    init {
        require(rotationSeconds in 1..MAX_ROTATION_SECONDS) {
            "owner display rotation seconds must be between 1 and $MAX_ROTATION_SECONDS"
        }
        require(multipleOwnersPrefixTemplate.supportsAdditionalOwnerCount) {
            "multiple owner prefix must contain ${OwnerDisplayTemplate.ADDITIONAL_OWNER_COUNT_PLACEHOLDER}"
        }
    }

    public companion object {
        public const val DEFAULT_ROTATION_SECONDS: Long = 5
        public const val MAX_ROTATION_SECONDS: Long = 3_600
        private val DEFAULT_SINGLE_OWNER_PREFIX_TEMPLATE: OwnerDisplayTemplate =
            (OwnerDisplayTemplate.parse("&7[&a%player_name%&7]&r ") as OwnerDisplayTemplateParseResult.Valid).template
        private val DEFAULT_MULTIPLE_OWNERS_PREFIX_TEMPLATE: OwnerDisplayTemplate =
            (
                OwnerDisplayTemplate.parse("&7[&a%player_name%&7]&e+%additional_owner_count%&r ") as
                    OwnerDisplayTemplateParseResult.Valid
            ).template
    }
}

public data class ItemPickupSettings(
    public val allowHopperPickup: Boolean = false,
    public val creativeNoCapacityPickupMode: CreativeNoCapacityPickupMode =
        CreativeNoCapacityPickupMode.DESTROY,
    public val warningCooldownSeconds: Long = DEFAULT_WARNING_COOLDOWN_SECONDS,
    public val warningMessageType: PickupWarningMessageType = PickupWarningMessageType.ACTION_BAR,
) {
    init {
        require(warningCooldownSeconds in 0..MAX_WARNING_COOLDOWN_SECONDS) {
            "pickup warning cooldown must be between 0 and $MAX_WARNING_COOLDOWN_SECONDS seconds"
        }
    }

    public companion object {
        public const val DEFAULT_WARNING_COOLDOWN_SECONDS: Long = 5
        public const val MAX_WARNING_COOLDOWN_SECONDS: Long = 3_600
    }
}

public enum class CreativeNoCapacityPickupMode(
    public val configValue: String,
) {
    DESTROY("destroy"),
    DENY("deny"),
    ;

    public companion object {
        public fun parse(value: String): CreativeNoCapacityPickupMode? =
            entries.firstOrNull { mode -> mode.configValue == value.lowercase() }
    }
}

public enum class PickupWarningMessageType(
    public val configValue: String,
) {
    ACTION_BAR("action-bar"),
    CHAT("chat"),
    ;

    public companion object {
        public fun parse(value: String): PickupWarningMessageType? = entries.firstOrNull { type -> type.configValue == value.lowercase() }
    }
}

@JvmInline
public value class PluginMessageLanguage private constructor(
    public val code: String,
) {
    public companion object {
        public val ZH_TW: PluginMessageLanguage = PluginMessageLanguage("zh_tw")
        public val EN_US: PluginMessageLanguage = PluginMessageLanguage("en_us")
        public val BUILT_IN: List<PluginMessageLanguage> = listOf(ZH_TW, EN_US)

        public fun parse(value: String): PluginMessageLanguage? {
            val normalized = value.trim().lowercase(Locale.ROOT)
            return normalized
                .takeIf(LOCALE_CODE::matches)
                ?.let(::PluginMessageLanguage)
        }

        private val LOCALE_CODE = Regex("[a-z0-9]{2,16}_[a-z0-9]{2,16}")
    }
}

public data class ItemDisplayRequest(
    public val entityId: UUID,
    public val worldName: String,
    public val itemName: String,
    public val amount: Long,
    public val ownerName: String? = null,
    public val additionalOwnerCount: Int = 0,
    public val placeholderPlayerId: UUID? = null,
    public val rarity: MinecraftItemRarity = MinecraftItemRarity.COMMON,
    public val customNameHasColor: Boolean = false,
    public val protectionSecondsRemaining: Long = 0,
    public val lifetimeSecondsRemaining: Long? = null,
    public val translationKey: String? = null,
    public val lifetimeSecondsElapsed: Long? = null,
) {
    init {
        require(worldName.isNotBlank()) { "worldName must not be blank" }
        require(worldName.none(Char::isISOControl)) { "worldName must not contain control characters" }
        require(itemName.isNotBlank()) { "itemName must not be blank" }
        require(itemName.none(Char::isISOControl)) { "itemName must not contain control characters" }
        require(amount > 0) { "amount must be positive" }
        require(ownerName == null || ownerName.isNotBlank()) { "ownerName must not be blank when present" }
        require(ownerName == null || ownerName.length <= MAX_OWNER_NAME_LENGTH) {
            "ownerName must not exceed $MAX_OWNER_NAME_LENGTH characters"
        }
        require(ownerName == null || ownerName.none(Char::isISOControl)) {
            "ownerName must not contain control characters"
        }
        require(additionalOwnerCount >= 0) { "additionalOwnerCount must not be negative" }
        require(ownerName != null || additionalOwnerCount == 0) {
            "additionalOwnerCount requires ownerName"
        }
        require(protectionSecondsRemaining >= 0) {
            "protectionSecondsRemaining must not be negative"
        }
        require(ownerName != null || protectionSecondsRemaining == 0L) {
            "protectionSecondsRemaining requires ownerName"
        }
        require(lifetimeSecondsElapsed == null || lifetimeSecondsElapsed >= 0) {
            "lifetimeSecondsElapsed must be zero, positive, or absent"
        }
        require(
            lifetimeSecondsRemaining == null ||
                lifetimeSecondsRemaining == ItemLifetimeSettings.NEVER_EXPIRES ||
                lifetimeSecondsRemaining >= 0,
        ) {
            "lifetimeSecondsRemaining must be -1, zero, positive, or absent"
        }
        require(translationKey == null || ItemClientTranslation.isValidTranslationKey(translationKey)) {
            "translationKey must be a valid vanilla item or block translation key"
        }
    }

    private companion object {
        private const val MAX_OWNER_NAME_LENGTH = 64
    }
}

public enum class MinecraftItemRarity(
    public val legacyColorCode: String,
) {
    COMMON("&f"),
    UNCOMMON("&e"),
    RARE("&b"),
    EPIC("&d"),
}

public data class ItemPresentation(
    public val entityId: UUID,
    public val text: String,
    public val visible: Boolean,
    public val clientTranslation: ItemClientTranslation? = null,
) {
    init {
        require(text.isNotBlank()) { "presentation text must not be blank" }
        require(text.length <= DisplayTemplate.MAX_LENGTH) {
            "presentation text must not exceed ${DisplayTemplate.MAX_LENGTH} characters"
        }
        require(text.none(Char::isISOControl)) { "presentation text must not contain control characters" }
    }
}

public data class ItemClientTranslation(
    public val translationKey: String,
    public val markedText: String,
) {
    init {
        require(isValidTranslationKey(translationKey)) {
            "translationKey must be a valid vanilla item or block translation key"
        }
        require(markedText.contains(MARKER)) { "markedText must contain the item translation marker" }
        require(markedText.none(Char::isISOControl)) { "markedText must not contain control characters" }
    }

    public companion object {
        public const val MARKER: String = "\uE000\uE001\uE002\uE003"
        private val TRANSLATION_KEY = Regex("(?:block|item)\\.minecraft\\.[a-z0-9_.-]{1,220}")

        public fun isValidTranslationKey(value: String): Boolean = TRANSLATION_KEY.matches(value)
    }
}

public data class ItemOwnerDisplay(
    public val playerName: String,
    public val additionalOwnerCount: Int,
    public val playerId: UUID,
) {
    init {
        require(playerName.isNotBlank()) { "playerName must not be blank" }
        require(additionalOwnerCount >= 0) { "additionalOwnerCount must not be negative" }
    }
}

public enum class ItemDisplayIgnoredReason {
    DISABLED,
    BLOCKED_WORLD,
}

public sealed interface ItemDisplayOutcome {
    public data class Ignored(
        public val reason: ItemDisplayIgnoredReason,
    ) : ItemDisplayOutcome

    public data class Presented(
        public val result: PresentationResult,
    ) : ItemDisplayOutcome

    public data class Cleared(
        public val reason: ItemDisplayIgnoredReason,
        public val result: PresentationResult,
    ) : ItemDisplayOutcome

    public data class Rejected(
        public val reason: String,
    ) : ItemDisplayOutcome
}

public sealed interface PresentationResult {
    public data object Applied : PresentationResult

    public data object MissingTarget : PresentationResult

    public data class Failed(
        public val errorType: String,
    ) : PresentationResult
}
