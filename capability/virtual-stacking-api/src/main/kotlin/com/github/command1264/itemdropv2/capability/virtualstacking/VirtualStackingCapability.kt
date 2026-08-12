package com.github.command1264.itemdropv2.capability.virtualstacking

import com.github.command1264.itemdropv2.core.VirtualItemStackingSettings
import com.github.command1264.itemdropv2.core.VirtualStackingRuntimeModeResolver
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemDisplaySettingsManager
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemStateRepository
import com.github.command1264.itemdropv2.platform.bukkit.DisplayWarningSink
import com.github.command1264.itemdropv2.platform.bukkit.ItemOwnershipRefresh
import com.github.command1264.itemdropv2.platform.bukkit.VirtualItemCarrierNormalization
import com.github.command1264.itemdropv2.platform.bukkit.VirtualItemMergeDispatchContext
import com.github.command1264.itemdropv2.platform.bukkit.VirtualItemMergeOperations
import org.bukkit.plugin.java.JavaPlugin

public interface VirtualStackingCapabilityProvider {
    public val id: String
    public val creationCapabilityAvailable: Boolean

    public fun create(context: VirtualStackingCapabilityContext): VirtualStackingCapability
}

public data class VirtualStackingCapabilityContext(
    public val plugin: JavaPlugin,
    public val settingsManager: BukkitItemDisplaySettingsManager,
    public val itemStateRepository: BukkitItemStateRepository,
    public val displayRefresh: ItemOwnershipRefresh,
    public val warningSink: DisplayWarningSink,
    public val modeResolver: VirtualStackingRuntimeModeResolver,
)

public interface VirtualStackingCapability : AutoCloseable {
    public val carrierNormalization: VirtualItemCarrierNormalization
    public val mergeOperations: VirtualItemMergeOperations
    public val mergeDispatchContext: VirtualItemMergeDispatchContext?

    public fun activate()

    public fun reload(settings: VirtualItemStackingSettings): VirtualStackingCapabilityReloadResult
}

public sealed interface VirtualStackingCapabilityReloadResult {
    public data object Applied : VirtualStackingCapabilityReloadResult

    public data class Failed(
        public val errorType: String,
    ) : VirtualStackingCapabilityReloadResult
}
