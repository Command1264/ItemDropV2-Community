package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.VirtualStackingRuntimeModeResolver
import org.bukkit.Bukkit
import org.bukkit.entity.Item

/** Maintains existing Pro virtual carriers without creating new virtual state. */
public class BukkitLegacyVirtualItemCarrierNormalizer internal constructor(
    private val stateRepository: ItemStateRepository,
    private val settingsRepository: ItemDisplaySettingsRepository,
    private val primaryThreadCheck: () -> Boolean,
) : VirtualItemCarrierNormalization {
    public constructor(
        stateRepository: ItemStateRepository,
        settingsRepository: ItemDisplaySettingsRepository,
    ) : this(stateRepository, settingsRepository, Bukkit::isPrimaryThread)

    @Suppress("TooGenericExceptionCaught")
    override fun normalize(item: Item): VirtualItemCarrierNormalizationOutcome {
        if (!primaryThreadCheck()) {
            return VirtualItemCarrierNormalizationOutcome.Failed("AsyncBukkitAccess")
        }
        return try {
            normalizeOnMainThread(item)
        } catch (error: RuntimeException) {
            VirtualItemCarrierNormalizationOutcome.Failed(error.javaClass.simpleName)
        }
    }

    private fun normalizeOnMainThread(item: Item): VirtualItemCarrierNormalizationOutcome =
        when (val loaded = stateRepository.load(item.uniqueId)) {
            is ItemStateLoadResult.Loaded -> {
                val virtualAmount =
                    loaded.state.virtualAmount
                        ?: return VirtualItemCarrierNormalizationOutcome.Unmanaged
                item.applyVirtualCarrierAmount(
                    virtualAmount,
                    settingsRepository.settings().virtualStacking,
                    LEGACY_MODE_RESOLVER,
                )
                VirtualItemCarrierNormalizationOutcome.Normalized(virtualAmount, migrated = false)
            }
            ItemStateLoadResult.Absent -> VirtualItemCarrierNormalizationOutcome.Unmanaged
            is ItemStateLoadResult.Legacy ->
                VirtualItemCarrierNormalizationOutcome.Rejected("LegacyStateRequiresMigration")
            is ItemStateLoadResult.Invalid -> VirtualItemCarrierNormalizationOutcome.Rejected("InvalidState")
            is ItemStateLoadResult.UnsupportedSchema ->
                VirtualItemCarrierNormalizationOutcome.Rejected("UnsupportedSchema:${loaded.actualVersion}")
            ItemStateLoadResult.MissingTarget -> VirtualItemCarrierNormalizationOutcome.Rejected("StateNotReady")
            is ItemStateLoadResult.Failed -> VirtualItemCarrierNormalizationOutcome.Failed(loaded.errorType)
        }

    private companion object {
        private val LEGACY_MODE_RESOLVER = VirtualStackingRuntimeModeResolver(creationCapabilityAvailable = false)
    }
}
