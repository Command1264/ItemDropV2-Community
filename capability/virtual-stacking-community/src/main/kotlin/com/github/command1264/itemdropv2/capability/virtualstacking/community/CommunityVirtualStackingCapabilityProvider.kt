package com.github.command1264.itemdropv2.capability.virtualstacking.community

import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapability
import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapabilityContext
import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapabilityProvider
import com.github.command1264.itemdropv2.platform.bukkit.BukkitLegacyDrainVirtualItemMergeOperations
import com.github.command1264.itemdropv2.platform.bukkit.BukkitLegacyVirtualItemCarrierNormalizer

public class CommunityVirtualStackingCapabilityProvider : VirtualStackingCapabilityProvider {
    override val id: String = "community-legacy-drain"
    override val creationCapabilityAvailable: Boolean = false

    override fun create(context: VirtualStackingCapabilityContext): VirtualStackingCapability =
        CommunityVirtualStackingCapability(
            carrierNormalization =
                BukkitLegacyVirtualItemCarrierNormalizer(
                    context.itemStateRepository,
                    context.settingsManager,
                ),
            mergeOperations = BukkitLegacyDrainVirtualItemMergeOperations(context.itemStateRepository),
        )
}
