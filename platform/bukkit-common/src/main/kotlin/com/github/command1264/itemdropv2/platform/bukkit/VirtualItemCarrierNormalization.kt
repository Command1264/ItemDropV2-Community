package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.VirtualItemAmount
import org.bukkit.entity.Item

public fun interface VirtualItemCarrierNormalization {
    public fun normalize(item: Item): VirtualItemCarrierNormalizationOutcome
}

public sealed interface VirtualItemCarrierNormalizationOutcome {
    public data class Normalized(
        public val virtualAmount: VirtualItemAmount,
        public val migrated: Boolean,
    ) : VirtualItemCarrierNormalizationOutcome

    public data object Unmanaged : VirtualItemCarrierNormalizationOutcome

    public data class Rejected(
        public val reason: String,
    ) : VirtualItemCarrierNormalizationOutcome

    public data class Failed(
        public val errorType: String,
    ) : VirtualItemCarrierNormalizationOutcome
}
