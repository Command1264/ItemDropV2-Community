package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.CreativeNoCapacityPickupMode
import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.EntityDamageAttributionStrategy
import com.github.command1264.itemdropv2.core.EntityOwnershipSettings
import com.github.command1264.itemdropv2.core.ItemDisplayPlaceholderSettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemMergeSettings
import com.github.command1264.itemdropv2.core.ItemOwnershipDisplaySettings
import com.github.command1264.itemdropv2.core.ItemOwnershipSettings
import com.github.command1264.itemdropv2.core.ItemPickupSettings
import com.github.command1264.itemdropv2.core.ItemProcessingSettings
import com.github.command1264.itemdropv2.core.MergeLifetimeStrategy
import com.github.command1264.itemdropv2.core.MergeOwnershipStrategy
import com.github.command1264.itemdropv2.core.MinecraftLanguageCode
import com.github.command1264.itemdropv2.core.OwnerDisplayTemplate
import com.github.command1264.itemdropv2.core.OwnerDisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.PickupWarningMessageType
import com.github.command1264.itemdropv2.core.PluginMessageLanguage
import com.github.command1264.itemdropv2.core.VirtualCarrierAmountMode
import com.github.command1264.itemdropv2.core.VirtualItemAmount
import com.github.command1264.itemdropv2.core.VirtualItemStackingSettings
import com.github.command1264.itemdropv2.core.VirtualMergeSchedulerSettings
import org.bukkit.configuration.ConfigurationSection

@Suppress("LargeClass", "TooManyFunctions", "LongMethod")
public class BukkitDisplaySettingsLoader(
    private val paperClientSideTranslationSettingSupported: Boolean,
    private val virtualStackingSettingSupported: Boolean = true,
) {
    public constructor() : this(false)

    internal fun rejectsAutomaticRepair(path: String): Boolean =
        paperClientSideTranslationSettingSupported && path == PAPER_CLIENT_SIDE_TRANSLATION_PATH

    public fun load(configuration: ConfigurationSection): BukkitDisplaySettingsLoadResult {
        val errors = mutableListOf<String>()
        validateSchemaVersion(configuration, errors)
        val enabled = readBoolean(configuration, ENABLED_PATH, errors)
        val blockedWorlds = readBlockedWorlds(configuration, errors)
        val minecraftLanguage = readMinecraftLanguage(configuration, errors)
        val messageLanguage = readMessageLanguage(configuration, errors)
        val singleTemplate = readTemplate(configuration, SINGLE_TEMPLATE_PATH, errors)
        val multipleTemplate = readTemplate(configuration, MULTIPLE_TEMPLATE_PATH, errors)
        val rarityDisplayEnabled = readOptionalBoolean(configuration, RARITY_DISPLAY_ENABLED_PATH, true, errors)
        val placeholderSettings = readPlaceholderSettings(configuration, errors)
        val ownership = readOwnershipSettings(configuration, errors)
        val processing = readItemProcessingSettings(configuration, errors)
        val mergeLifetimeStrategy = readMergeLifetimeStrategy(configuration, errors)
        val mergeOwnershipStrategy = readMergeOwnershipStrategy(configuration, errors)
        val virtualStacking =
            if (virtualStackingSettingSupported) {
                readVirtualStackingSettings(configuration, errors)
            } else {
                VirtualItemStackingSettings(enabled = false)
            }
        val paperClientSideTranslationEnabled =
            if (paperClientSideTranslationSettingSupported) {
                readOptionalBoolean(configuration, PAPER_CLIENT_SIDE_TRANSLATION_PATH, true, errors)
            } else {
                null
            }

        val allValuesPresent =
            listOf(
                enabled,
                blockedWorlds,
                singleTemplate,
                multipleTemplate,
                ownership,
                processing,
                mergeLifetimeStrategy,
                mergeOwnershipStrategy,
                virtualStacking,
                rarityDisplayEnabled,
                placeholderSettings,
            ).all { it != null }
        return if (errors.isEmpty() && allValuesPresent) {
            BukkitDisplaySettingsLoadResult.Loaded(
                ItemDisplaySettings(
                    enabled = requireNotNull(enabled),
                    blockedWorlds = requireNotNull(blockedWorlds),
                    singleItemTemplate = requireNotNull(singleTemplate),
                    multipleItemTemplate = requireNotNull(multipleTemplate),
                    minecraftLanguage = requireNotNull(minecraftLanguage),
                    messageLanguage = requireNotNull(messageLanguage),
                    ownership = requireNotNull(ownership),
                    processing = requireNotNull(processing),
                    merge =
                        ItemMergeSettings(
                            lifetimeStrategy = requireNotNull(mergeLifetimeStrategy),
                            ownershipStrategy = requireNotNull(mergeOwnershipStrategy),
                        ),
                    virtualStacking = requireNotNull(virtualStacking),
                    rarityDisplayEnabled = requireNotNull(rarityDisplayEnabled),
                    placeholders = requireNotNull(placeholderSettings),
                    paperClientSideTranslationEnabled = paperClientSideTranslationEnabled,
                ),
            )
        } else {
            BukkitDisplaySettingsLoadResult.Invalid(errors.toList())
        }
    }

    private fun readItemProcessingSettings(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): ItemProcessingSettings? {
        val maximumItemsPerTick =
            when {
                !configuration.contains(PROCESSING_MAXIMUM_ITEMS_PER_TICK_PATH) ->
                    ItemProcessingSettings.DEFAULT_MAXIMUM_ITEMS_PER_TICK
                !configuration.isInt(PROCESSING_MAXIMUM_ITEMS_PER_TICK_PATH) -> {
                    errors += "$PROCESSING_MAXIMUM_ITEMS_PER_TICK_PATH: expected a positive integer"
                    null
                }
                configuration.getInt(PROCESSING_MAXIMUM_ITEMS_PER_TICK_PATH) <= 0 -> {
                    errors += "$PROCESSING_MAXIMUM_ITEMS_PER_TICK_PATH: expected a positive integer"
                    null
                }
                else -> configuration.getInt(PROCESSING_MAXIMUM_ITEMS_PER_TICK_PATH)
            }
        return maximumItemsPerTick?.let(::ItemProcessingSettings)
    }

    private fun readPlaceholderSettings(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): ItemDisplayPlaceholderSettings? {
        val noOwner =
            readPlaceholderLabel(
                configuration,
                PLACEHOLDER_NO_OWNER_PATH,
                ItemDisplayPlaceholderSettings.DEFAULT_NO_OWNER,
                allowBlank = true,
                errors,
            )
        val lifetimePermanent =
            readPlaceholderLabel(
                configuration,
                PLACEHOLDER_LIFETIME_PERMANENT_PATH,
                ItemDisplayPlaceholderSettings.DEFAULT_LIFETIME_PERMANENT,
                allowBlank = false,
                errors,
            )
        val lifetimeUnknown =
            readPlaceholderLabel(
                configuration,
                PLACEHOLDER_LIFETIME_UNKNOWN_PATH,
                ItemDisplayPlaceholderSettings.DEFAULT_LIFETIME_UNKNOWN,
                allowBlank = false,
                errors,
            )
        if (listOf(noOwner, lifetimePermanent, lifetimeUnknown).any { it == null }) {
            return null
        }
        return ItemDisplayPlaceholderSettings(
            noOwner = requireNotNull(noOwner),
            lifetimePermanent = requireNotNull(lifetimePermanent),
            lifetimeUnknown = requireNotNull(lifetimeUnknown),
        )
    }

    private fun readPlaceholderLabel(
        configuration: ConfigurationSection,
        path: String,
        default: String,
        allowBlank: Boolean,
        errors: MutableList<String>,
    ): String? =
        when {
            !configuration.contains(path) -> default
            !configuration.isString(path) -> {
                errors += "$path: expected string"
                null
            }
            else -> {
                val value = configuration.getString(path).orEmpty()
                when {
                    !allowBlank && value.isBlank() -> errors += "$path: must not be blank"
                    value.length > ItemDisplayPlaceholderSettings.MAX_LABEL_LENGTH ->
                        errors += "$path: must not exceed ${ItemDisplayPlaceholderSettings.MAX_LABEL_LENGTH} characters"
                    value.any(Char::isISOControl) -> errors += "$path: must not contain control characters"
                }
                value.takeIf { errors.none { error -> error.startsWith("$path:") } }
            }
        }

    private fun readOwnershipSettings(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): ItemOwnershipSettings? {
        val enabled = readOptionalBoolean(configuration, OWNERSHIP_ENABLED_PATH, true, errors)
        val protectionSeconds = readProtectionSeconds(configuration, errors)
        val rotationSeconds = readOwnerRotationSeconds(configuration, errors)
        val singlePrefix = readOwnerTemplate(configuration, SINGLE_OWNER_PREFIX_PATH, DEFAULT_SINGLE_OWNER_PREFIX, errors)
        val multiplePrefix =
            readOwnerTemplate(configuration, MULTIPLE_OWNERS_PREFIX_PATH, DEFAULT_MULTIPLE_OWNERS_PREFIX, errors)
                ?.also { template ->
                    if (!template.supportsAdditionalOwnerCount) {
                        errors +=
                            "$MULTIPLE_OWNERS_PREFIX_PATH: template must contain " +
                            OwnerDisplayTemplate.ADDITIONAL_OWNER_COUNT_PLACEHOLDER
                    }
                }
        val hopperPickup = readOptionalBoolean(configuration, ALLOW_HOPPER_PICKUP_PATH, false, errors)
        val creativeNoCapacityPickupMode = readCreativeNoCapacityPickupMode(configuration, errors)
        val warningCooldown = readPickupWarningCooldown(configuration, errors)
        val warningType = readPickupWarningMessageType(configuration, errors)
        val entityStrategy = readEntityAttributionStrategy(configuration, errors)
        val entityMinimumDamage = readEntityMinimumDamage(configuration, errors)
        val entityCombatTimeout = readEntityCombatTimeout(configuration, errors)
        if (listOf(
                enabled,
                protectionSeconds,
                rotationSeconds,
                singlePrefix,
                multiplePrefix,
                hopperPickup,
                creativeNoCapacityPickupMode,
                warningCooldown,
                warningType,
                entityStrategy,
                entityMinimumDamage,
                entityCombatTimeout,
            ).any { it == null }
        ) {
            return null
        }
        return ItemOwnershipSettings(
            enabled = requireNotNull(enabled),
            protectionSeconds = requireNotNull(protectionSeconds),
            display =
                ItemOwnershipDisplaySettings(
                    requireNotNull(rotationSeconds),
                    requireNotNull(singlePrefix),
                    requireNotNull(multiplePrefix),
                ),
            pickup = itemPickupSettings(hopperPickup, creativeNoCapacityPickupMode, warningCooldown, warningType),
            entity =
                EntityOwnershipSettings(
                    requireNotNull(entityStrategy),
                    requireNotNull(entityMinimumDamage),
                    requireNotNull(entityCombatTimeout),
                ),
        )
    }

    private fun itemPickupSettings(
        allowHopperPickup: Boolean?,
        creativeMode: CreativeNoCapacityPickupMode?,
        warningCooldown: Long?,
        warningType: PickupWarningMessageType?,
    ): ItemPickupSettings =
        ItemPickupSettings(
            allowHopperPickup = requireNotNull(allowHopperPickup),
            creativeNoCapacityPickupMode = requireNotNull(creativeMode),
            warningCooldownSeconds = requireNotNull(warningCooldown),
            warningMessageType = requireNotNull(warningType),
        )

    private fun readEntityAttributionStrategy(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): EntityDamageAttributionStrategy? =
        when {
            !configuration.contains(ENTITY_ATTRIBUTION_STRATEGY_PATH) -> EntityDamageAttributionStrategy.HIGHEST_DAMAGE
            !configuration.isString(ENTITY_ATTRIBUTION_STRATEGY_PATH) -> {
                errors += "$ENTITY_ATTRIBUTION_STRATEGY_PATH: expected string"
                null
            }
            else ->
                EntityDamageAttributionStrategy.parse(configuration.getString(ENTITY_ATTRIBUTION_STRATEGY_PATH).orEmpty()) ?: run {
                    errors += "$ENTITY_ATTRIBUTION_STRATEGY_PATH: expected one of highest-damage, first-hit, final-hit"
                    null
                }
        }

    private fun readEntityMinimumDamage(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): Double? =
        when {
            !configuration.contains(ENTITY_MINIMUM_DAMAGE_PATH) -> EntityOwnershipSettings.DEFAULT_MINIMUM_DAMAGE_PERCENT
            configuration.get(ENTITY_MINIMUM_DAMAGE_PATH) !is Number -> {
                errors += "$ENTITY_MINIMUM_DAMAGE_PATH: expected number"
                null
            }
            configuration.getDouble(ENTITY_MINIMUM_DAMAGE_PATH) !in 0.0..MAX_PERCENT -> {
                errors += "$ENTITY_MINIMUM_DAMAGE_PATH: expected value between 0 and 100"
                null
            }
            else -> configuration.getDouble(ENTITY_MINIMUM_DAMAGE_PATH)
        }

    private fun readEntityCombatTimeout(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): Long? =
        when {
            !configuration.contains(ENTITY_COMBAT_TIMEOUT_PATH) -> EntityOwnershipSettings.DEFAULT_COMBAT_TIMEOUT_SECONDS
            !configuration.isLong(ENTITY_COMBAT_TIMEOUT_PATH) && !configuration.isInt(ENTITY_COMBAT_TIMEOUT_PATH) -> {
                errors += "$ENTITY_COMBAT_TIMEOUT_PATH: expected integer"
                null
            }
            configuration.getLong(ENTITY_COMBAT_TIMEOUT_PATH) !in 1..EntityOwnershipSettings.MAX_COMBAT_TIMEOUT_SECONDS -> {
                errors +=
                    "$ENTITY_COMBAT_TIMEOUT_PATH: expected value between 1 and " +
                    EntityOwnershipSettings.MAX_COMBAT_TIMEOUT_SECONDS
                null
            }
            else -> configuration.getLong(ENTITY_COMBAT_TIMEOUT_PATH)
        }

    private fun readMergeLifetimeStrategy(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): MergeLifetimeStrategy? =
        when {
            !configuration.contains(MERGE_LIFETIME_STRATEGY_PATH) -> MergeLifetimeStrategy.AVERAGE
            !configuration.isString(MERGE_LIFETIME_STRATEGY_PATH) -> {
                errors += "$MERGE_LIFETIME_STRATEGY_PATH: expected string"
                null
            }
            else ->
                MergeLifetimeStrategy.parse(configuration.getString(MERGE_LIFETIME_STRATEGY_PATH).orEmpty()) ?: run {
                    errors += "$MERGE_LIFETIME_STRATEGY_PATH: expected one of average, maximum, minimum"
                    null
                }
        }

    private fun readMergeOwnershipStrategy(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): MergeOwnershipStrategy? =
        when {
            !configuration.contains(MERGE_OWNERSHIP_STRATEGY_PATH) -> MergeOwnershipStrategy.AVERAGE
            !configuration.isString(MERGE_OWNERSHIP_STRATEGY_PATH) -> {
                errors += "$MERGE_OWNERSHIP_STRATEGY_PATH: expected string"
                null
            }
            else ->
                MergeOwnershipStrategy.parse(configuration.getString(MERGE_OWNERSHIP_STRATEGY_PATH).orEmpty()) ?: run {
                    errors += "$MERGE_OWNERSHIP_STRATEGY_PATH: expected one of average, maximum, minimum, reset"
                    null
                }
        }

    private fun readOptionalBoolean(
        configuration: ConfigurationSection,
        path: String,
        default: Boolean,
        errors: MutableList<String>,
    ): Boolean? =
        when {
            !configuration.contains(path) -> default
            !configuration.isBoolean(path) -> {
                errors += "$path: expected boolean"
                null
            }
            else -> configuration.getBoolean(path)
        }

    private fun readVirtualStackingSettings(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): VirtualItemStackingSettings? {
        val enabled =
            readOptionalBoolean(
                configuration,
                VIRTUAL_STACKING_ENABLED_PATH,
                false,
                errors,
            )
        val legacyMaximum = readOptionalPositiveLong(configuration, VIRTUAL_STACKING_MAXIMUM_PATH, null, errors)
        val maximumNativeStacks =
            if (legacyMaximum != null) {
                null
            } else {
                readOptionalPositiveLong(
                    configuration,
                    VIRTUAL_STACKING_NATIVE_STACKS_PATH,
                    VirtualItemStackingSettings.DEFAULT_MAXIMUM_NATIVE_STACKS,
                    errors,
                )
            }
        val unstackableItemsEnabled =
            readOptionalBoolean(
                configuration,
                VIRTUAL_STACKING_UNSTACKABLE_ENABLED_PATH,
                false,
                errors,
            )
        val carrierAmountMode = readVirtualCarrierAmountMode(configuration, errors)
        val scheduler = readVirtualMergeSchedulerSettings(configuration, errors)
        val valuesPresent =
            listOf(
                enabled,
                scheduler,
                unstackableItemsEnabled,
                carrierAmountMode,
                legacyMaximum ?: maximumNativeStacks,
            ).none { it == null }
        val paths =
            listOf(
                VIRTUAL_STACKING_MAXIMUM_PATH,
                VIRTUAL_STACKING_NATIVE_STACKS_PATH,
                VIRTUAL_STACKING_UNSTACKABLE_ENABLED_PATH,
                VIRTUAL_STACKING_CARRIER_AMOUNT_MODE_PATH,
            )
        if (!valuesPresent || errors.any { error -> paths.any(error::startsWith) }) return null
        return VirtualItemStackingSettings(
            enabled = requireNotNull(enabled),
            maximumAmountPerEntity =
                legacyMaximum?.let(VirtualItemAmount::of)
                    ?: VirtualItemStackingSettings.DEFAULT_MAXIMUM_AMOUNT,
            mergeScheduler = requireNotNull(scheduler),
            maximumNativeStacksPerEntity = maximumNativeStacks,
            unstackableItemsEnabled = requireNotNull(unstackableItemsEnabled),
            carrierAmountMode = requireNotNull(carrierAmountMode),
        )
    }

    private fun readVirtualCarrierAmountMode(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): VirtualCarrierAmountMode? {
        if (!configuration.contains(VIRTUAL_STACKING_CARRIER_AMOUNT_MODE_PATH)) {
            return VirtualCarrierAmountMode.PROPORTIONAL
        }
        val value =
            configuration
                .getString(VIRTUAL_STACKING_CARRIER_AMOUNT_MODE_PATH)
                ?.takeIf { configuration.isString(VIRTUAL_STACKING_CARRIER_AMOUNT_MODE_PATH) }
        return value?.let(VirtualCarrierAmountMode::parse) ?: run {
            errors +=
                "$VIRTUAL_STACKING_CARRIER_AMOUNT_MODE_PATH: expected one of " +
                VirtualCarrierAmountMode.entries.joinToString { it.configValue }
            null
        }
    }

    private fun readOptionalPositiveLong(
        configuration: ConfigurationSection,
        path: String,
        default: Long?,
        errors: MutableList<String>,
    ): Long? =
        when {
            !configuration.contains(path) -> default
            !configuration.isLong(path) && !configuration.isInt(path) -> {
                errors += "$path: expected integer"
                null
            }
            configuration.getLong(path) <= 0L -> {
                errors += "$path: expected positive integer"
                null
            }
            else -> configuration.getLong(path)
        }

    private fun readVirtualMergeSchedulerSettings(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): VirtualMergeSchedulerSettings? {
        val comparisons =
            readBoundedInt(
                configuration,
                VIRTUAL_MERGE_COMPARISONS_PATH,
                VirtualMergeSchedulerSettings.DEFAULT_COMPARISONS_PER_TICK,
                VirtualMergeSchedulerSettings.MAX_COMPARISONS_PER_TICK,
                errors,
            )
        val events =
            readBoundedInt(
                configuration,
                VIRTUAL_MERGE_EVENTS_PATH,
                VirtualMergeSchedulerSettings.DEFAULT_EVENTS_PER_TICK,
                comparisons ?: VirtualMergeSchedulerSettings.MAX_COMPARISONS_PER_TICK,
                errors,
            )
        val retryBackoff =
            readBoundedLong(
                configuration,
                VIRTUAL_MERGE_RETRY_PATH,
                VirtualMergeSchedulerSettings.DEFAULT_RETRY_BACKOFF_SECONDS,
                VirtualMergeSchedulerSettings.MAX_RETRY_BACKOFF_SECONDS,
                errors,
            )
        return if (listOf(comparisons, events, retryBackoff).all { it != null }) {
            VirtualMergeSchedulerSettings(
                requireNotNull(comparisons),
                requireNotNull(events),
                requireNotNull(retryBackoff),
            )
        } else {
            null
        }
    }

    private fun readBoundedInt(
        configuration: ConfigurationSection,
        path: String,
        default: Int,
        maximum: Int,
        errors: MutableList<String>,
    ): Int? =
        when {
            !configuration.contains(path) -> default
            !configuration.isInt(path) -> {
                errors += "$path: expected integer"
                null
            }
            configuration.getInt(path) !in 1..maximum -> {
                errors += "$path: expected value between 1 and $maximum"
                null
            }
            else -> configuration.getInt(path)
        }

    private fun readBoundedLong(
        configuration: ConfigurationSection,
        path: String,
        default: Long,
        maximum: Long,
        errors: MutableList<String>,
    ): Long? =
        when {
            !configuration.contains(path) -> default
            !configuration.isLong(path) && !configuration.isInt(path) -> {
                errors += "$path: expected integer"
                null
            }
            configuration.getLong(path) !in 1..maximum -> {
                errors += "$path: expected value between 1 and $maximum"
                null
            }
            else -> configuration.getLong(path)
        }

    private fun readProtectionSeconds(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): Long? =
        when {
            !configuration.contains(PROTECTION_SECONDS_PATH) -> ItemOwnershipSettings.DEFAULT_PROTECTION_SECONDS
            !configuration.isLong(PROTECTION_SECONDS_PATH) && !configuration.isInt(PROTECTION_SECONDS_PATH) -> {
                errors += "$PROTECTION_SECONDS_PATH: expected integer"
                null
            }
            configuration.getLong(PROTECTION_SECONDS_PATH) !in 0..ItemOwnershipSettings.MAX_PROTECTION_SECONDS -> {
                errors +=
                    "$PROTECTION_SECONDS_PATH: expected value between 0 and " +
                    ItemOwnershipSettings.MAX_PROTECTION_SECONDS
                null
            }
            else -> configuration.getLong(PROTECTION_SECONDS_PATH)
        }

    private fun readPickupWarningCooldown(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): Long? =
        when {
            !configuration.contains(PICKUP_WARNING_COOLDOWN_PATH) -> ItemPickupSettings.DEFAULT_WARNING_COOLDOWN_SECONDS
            !configuration.isLong(PICKUP_WARNING_COOLDOWN_PATH) && !configuration.isInt(PICKUP_WARNING_COOLDOWN_PATH) -> {
                errors += "$PICKUP_WARNING_COOLDOWN_PATH: expected integer"
                null
            }
            configuration.getLong(PICKUP_WARNING_COOLDOWN_PATH) !in 0..ItemPickupSettings.MAX_WARNING_COOLDOWN_SECONDS -> {
                errors +=
                    "$PICKUP_WARNING_COOLDOWN_PATH: expected value between 0 and " +
                    ItemPickupSettings.MAX_WARNING_COOLDOWN_SECONDS
                null
            }
            else -> configuration.getLong(PICKUP_WARNING_COOLDOWN_PATH)
        }

    private fun readCreativeNoCapacityPickupMode(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): CreativeNoCapacityPickupMode? =
        when {
            !configuration.contains(CREATIVE_NO_CAPACITY_PICKUP_PATH) ->
                CreativeNoCapacityPickupMode.DESTROY
            !configuration.isString(CREATIVE_NO_CAPACITY_PICKUP_PATH) -> {
                errors += "$CREATIVE_NO_CAPACITY_PICKUP_PATH: expected string"
                null
            }
            else ->
                CreativeNoCapacityPickupMode.parse(
                    configuration.getString(CREATIVE_NO_CAPACITY_PICKUP_PATH).orEmpty(),
                ) ?: run {
                    errors += "$CREATIVE_NO_CAPACITY_PICKUP_PATH: expected one of destroy, deny"
                    null
                }
        }

    private fun readPickupWarningMessageType(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): PickupWarningMessageType? =
        when {
            !configuration.contains(PICKUP_WARNING_MESSAGE_TYPE_PATH) -> PickupWarningMessageType.ACTION_BAR
            !configuration.isString(PICKUP_WARNING_MESSAGE_TYPE_PATH) -> {
                errors += "$PICKUP_WARNING_MESSAGE_TYPE_PATH: expected string"
                null
            }
            else ->
                PickupWarningMessageType.parse(configuration.getString(PICKUP_WARNING_MESSAGE_TYPE_PATH).orEmpty()) ?: run {
                    errors += "$PICKUP_WARNING_MESSAGE_TYPE_PATH: expected one of action-bar, chat"
                    null
                }
        }

    private fun readOwnerRotationSeconds(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): Long? =
        when {
            !configuration.contains(OWNER_ROTATION_SECONDS_PATH) -> ItemOwnershipDisplaySettings.DEFAULT_ROTATION_SECONDS
            !configuration.isLong(OWNER_ROTATION_SECONDS_PATH) && !configuration.isInt(OWNER_ROTATION_SECONDS_PATH) -> {
                errors += "$OWNER_ROTATION_SECONDS_PATH: expected integer"
                null
            }
            configuration.getLong(OWNER_ROTATION_SECONDS_PATH) !in
                1..ItemOwnershipDisplaySettings.MAX_ROTATION_SECONDS -> {
                errors +=
                    "$OWNER_ROTATION_SECONDS_PATH: expected value between 1 and " +
                    ItemOwnershipDisplaySettings.MAX_ROTATION_SECONDS
                null
            }
            else -> configuration.getLong(OWNER_ROTATION_SECONDS_PATH)
        }

    private fun readOwnerTemplate(
        configuration: ConfigurationSection,
        path: String,
        default: String,
        errors: MutableList<String>,
    ): OwnerDisplayTemplate? {
        val raw =
            if (!configuration.contains(path)) {
                default
            } else if (!configuration.isString(path)) {
                errors += "$path: expected string"
                return null
            } else {
                configuration.getString(path).orEmpty()
            }
        return when (val result = OwnerDisplayTemplate.parse(raw)) {
            is OwnerDisplayTemplateParseResult.Valid -> result.template
            is OwnerDisplayTemplateParseResult.Invalid -> {
                result.errors.forEach { errors += "$path: $it" }
                null
            }
        }
    }

    private fun validateSchemaVersion(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ) {
        if (!configuration.isInt(SCHEMA_VERSION_PATH) || configuration.getInt(SCHEMA_VERSION_PATH) != SCHEMA_VERSION) {
            errors += "$SCHEMA_VERSION_PATH: expected integer $SCHEMA_VERSION"
        }
    }

    private fun readBoolean(
        configuration: ConfigurationSection,
        path: String,
        errors: MutableList<String>,
    ): Boolean? {
        if (!configuration.isBoolean(path)) {
            errors += "$path: expected boolean"
            return null
        }
        return configuration.getBoolean(path)
    }

    private fun readBlockedWorlds(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): Set<String>? {
        val values = configuration.getList(BLOCKED_WORLDS_PATH)
        if (!configuration.isList(BLOCKED_WORLDS_PATH) || values == null || values.any { it !is String }) {
            errors += "$BLOCKED_WORLDS_PATH: expected string list"
            return null
        }

        val worlds = linkedSetOf<String>()
        values.forEachIndexed { index, value ->
            val world = (value as String).trim()
            val path = "$BLOCKED_WORLDS_PATH[$index]"
            when {
                world.isBlank() -> errors += "$path: world name must not be blank"
                world.length > MAX_WORLD_NAME_LENGTH ->
                    errors += "$path: world name must not exceed $MAX_WORLD_NAME_LENGTH characters"
                world.any(Char::isISOControl) ->
                    errors += "$path: world name must not contain control characters"
                !worlds.add(world) -> errors += "$path: duplicate world '$world'"
            }
        }
        return worlds.toSet()
    }

    private fun readTemplate(
        configuration: ConfigurationSection,
        path: String,
        errors: MutableList<String>,
    ): DisplayTemplate? {
        if (!configuration.isString(path)) {
            errors += "$path: expected string"
            return null
        }
        return when (val result = DisplayTemplate.parse(configuration.getString(path).orEmpty())) {
            is DisplayTemplateParseResult.Valid -> result.template
            is DisplayTemplateParseResult.Invalid -> {
                result.errors.forEach { errors += "$path: $it" }
                null
            }
        }
    }

    private fun readMinecraftLanguage(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): MinecraftLanguageCode? =
        when {
            !configuration.contains(MINECRAFT_LANGUAGE_PATH) -> MinecraftLanguageCode.EN_US
            !configuration.isString(MINECRAFT_LANGUAGE_PATH) -> {
                errors += "$MINECRAFT_LANGUAGE_PATH: expected string"
                null
            }
            else ->
                MinecraftLanguageCode.parse(configuration.getString(MINECRAFT_LANGUAGE_PATH).orEmpty()).fold(
                    onSuccess = { it },
                    onFailure = {
                        errors += "$MINECRAFT_LANGUAGE_PATH: expected Minecraft locale such as en_us or zh_tw"
                        null
                    },
                )
        }

    private fun readMessageLanguage(
        configuration: ConfigurationSection,
        errors: MutableList<String>,
    ): PluginMessageLanguage? =
        when {
            !configuration.contains(MESSAGE_LANGUAGE_PATH) -> PluginMessageLanguage.ZH_TW
            !configuration.isString(MESSAGE_LANGUAGE_PATH) -> {
                errors += "$MESSAGE_LANGUAGE_PATH: expected string"
                null
            }
            else ->
                PluginMessageLanguage.parse(configuration.getString(MESSAGE_LANGUAGE_PATH).orEmpty()) ?: run {
                    errors += "$MESSAGE_LANGUAGE_PATH: expected locale such as zh_tw, en_us, or ja_jp"
                    null
                }
        }

    private companion object {
        private const val SCHEMA_VERSION = 1
        private const val MAX_WORLD_NAME_LENGTH = 255
        private const val MAX_PERCENT = 100.0
        private const val SCHEMA_VERSION_PATH = "schema-version"
        private const val ENABLED_PATH = "general.enabled"
        private const val MINECRAFT_LANGUAGE_PATH = "general.minecraft-language"
        private const val MESSAGE_LANGUAGE_PATH = "general.language"
        private const val BLOCKED_WORLDS_PATH = "general.blocked-worlds"
        private const val SINGLE_TEMPLATE_PATH = "items.display-name-format.single"
        private const val MULTIPLE_TEMPLATE_PATH = "items.display-name-format.multi"
        private const val PLACEHOLDER_NO_OWNER_PATH = "items.display-placeholders.no-owner"
        private const val PLACEHOLDER_LIFETIME_PERMANENT_PATH = "items.display-placeholders.lifetime-permanent"
        private const val PLACEHOLDER_LIFETIME_UNKNOWN_PATH = "items.display-placeholders.lifetime-unknown"
        private const val RARITY_DISPLAY_ENABLED_PATH = "items.rarity-display.enabled"
        private const val PROCESSING_MAXIMUM_ITEMS_PER_TICK_PATH = "items.processing.maximum-items-per-tick"
        private const val PAPER_CLIENT_SIDE_TRANSLATION_PATH = "items.display.paper-client-side-translation"
        private const val OWNERSHIP_ENABLED_PATH = "items.ownership.enabled"
        private const val PROTECTION_SECONDS_PATH = "items.ownership.protection-seconds"
        private const val ENTITY_ATTRIBUTION_STRATEGY_PATH = "items.ownership.entity.strategy"
        private const val ENTITY_MINIMUM_DAMAGE_PATH = "items.ownership.entity.minimum-damage-percent-of-max-health"
        private const val ENTITY_COMBAT_TIMEOUT_PATH = "items.ownership.entity.combat-timeout-seconds"
        private const val OWNER_ROTATION_SECONDS_PATH = "items.ownership.display.rotation-seconds"
        private const val SINGLE_OWNER_PREFIX_PATH = "items.ownership.display.single-owner-prefix"
        private const val MULTIPLE_OWNERS_PREFIX_PATH = "items.ownership.display.multiple-owners-prefix"
        private const val ALLOW_HOPPER_PICKUP_PATH = "items.ownership.allow-hopper-pickup"
        private const val CREATIVE_NO_CAPACITY_PICKUP_PATH =
            "items.ownership.creative-no-capacity-pickup"
        private const val PICKUP_WARNING_COOLDOWN_PATH = "items.ownership.pickup-warning-cooldown-seconds"
        private const val PICKUP_WARNING_MESSAGE_TYPE_PATH = "items.ownership.pickup-warning-message-type"
        private const val MERGE_LIFETIME_STRATEGY_PATH = "items.merge.lifetime-strategy"
        private const val MERGE_OWNERSHIP_STRATEGY_PATH = "items.merge.ownership-strategy"
        private const val VIRTUAL_STACKING_ENABLED_PATH = "items.virtual-stacking.enabled"
        private const val VIRTUAL_STACKING_MAXIMUM_PATH = "items.virtual-stacking.max-amount-per-entity"
        private const val VIRTUAL_STACKING_NATIVE_STACKS_PATH =
            "items.virtual-stacking.maximum-native-stacks-per-entity"
        private const val VIRTUAL_STACKING_UNSTACKABLE_ENABLED_PATH =
            "items.virtual-stacking.unstackable-items.enabled"
        private const val VIRTUAL_STACKING_CARRIER_AMOUNT_MODE_PATH =
            "items.virtual-stacking.carrier-amount-mode"
        private const val VIRTUAL_MERGE_COMPARISONS_PATH = "items.virtual-stacking.merge.comparisons-per-tick"
        private const val VIRTUAL_MERGE_EVENTS_PATH = "items.virtual-stacking.merge.events-per-tick"
        private const val VIRTUAL_MERGE_RETRY_PATH = "items.virtual-stacking.merge.retry-backoff-seconds"
        private const val DEFAULT_SINGLE_OWNER_PREFIX = "&7[&a%player_name%&7]&r "
        private const val DEFAULT_MULTIPLE_OWNERS_PREFIX =
            "&7[&a%player_name%&7]&e+%additional_owner_count%&r "
    }
}

public sealed interface BukkitDisplaySettingsLoadResult {
    public data class Loaded(
        public val settings: ItemDisplaySettings,
    ) : BukkitDisplaySettingsLoadResult

    public data class Invalid(
        public val errors: List<String>,
    ) : BukkitDisplaySettingsLoadResult
}

public class ImmutableItemDisplaySettingsRepository(
    private val value: ItemDisplaySettings,
) : ItemDisplaySettingsRepository {
    override fun settings(): ItemDisplaySettings = value
}
