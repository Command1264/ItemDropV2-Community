package com.github.command1264.itemdropv2.core

public enum class VirtualStackingRuntimeMode {
    ACTIVE,
    LEGACY_DRAIN,
}

public class VirtualStackingRuntimeModeResolver(
    private val creationCapabilityAvailable: Boolean,
) {
    public fun creationMode(
        settings: VirtualItemStackingSettings,
        nativeStackSize: Int,
    ): VirtualStackingRuntimeMode =
        if (creationCapabilityAvailable && settings.manages(nativeStackSize)) {
            VirtualStackingRuntimeMode.ACTIVE
        } else {
            VirtualStackingRuntimeMode.LEGACY_DRAIN
        }

    public fun carrierMode(
        settings: VirtualItemStackingSettings,
        nativeStackSize: Int,
        virtualAmount: VirtualItemAmount,
    ): VirtualStackingRuntimeMode =
        if (
            creationMode(settings, nativeStackSize) == VirtualStackingRuntimeMode.ACTIVE &&
            virtualAmount.value <= settings.maximumAmountFor(nativeStackSize).value
        ) {
            VirtualStackingRuntimeMode.ACTIVE
        } else {
            VirtualStackingRuntimeMode.LEGACY_DRAIN
        }
}
