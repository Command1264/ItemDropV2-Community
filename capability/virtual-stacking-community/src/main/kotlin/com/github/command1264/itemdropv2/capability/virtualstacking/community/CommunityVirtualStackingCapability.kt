package com.github.command1264.itemdropv2.capability.virtualstacking.community

import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapability
import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapabilityReloadResult
import com.github.command1264.itemdropv2.core.VirtualItemStackingSettings
import com.github.command1264.itemdropv2.platform.bukkit.VirtualItemCarrierNormalization
import com.github.command1264.itemdropv2.platform.bukkit.VirtualItemMergeDispatchContext
import com.github.command1264.itemdropv2.platform.bukkit.VirtualItemMergeOperations

internal class CommunityVirtualStackingCapability(
    override val carrierNormalization: VirtualItemCarrierNormalization,
    override val mergeOperations: VirtualItemMergeOperations,
) : VirtualStackingCapability {
    override val mergeDispatchContext: VirtualItemMergeDispatchContext? = null

    override fun activate(): Unit = Unit

    override fun reload(settings: VirtualItemStackingSettings): VirtualStackingCapabilityReloadResult =
        VirtualStackingCapabilityReloadResult.Applied

    override fun close(): Unit = Unit
}
