package com.github.command1264.itemdropv2.capability.virtualstacking

public class VirtualStackingCapabilitySelectionService {
    public fun select(providers: List<VirtualStackingCapabilityProvider>): VirtualStackingCapabilitySelectionResult =
        when (providers.size) {
            1 -> VirtualStackingCapabilitySelectionResult.Selected(providers.single())
            0 ->
                VirtualStackingCapabilitySelectionResult.Rejected(
                    "no virtual stacking capability provider found",
                )
            else ->
                VirtualStackingCapabilitySelectionResult.Rejected(
                    "multiple virtual stacking capability providers found: ${providers.size}",
                )
        }
}

public sealed interface VirtualStackingCapabilitySelectionResult {
    public data class Selected(
        public val provider: VirtualStackingCapabilityProvider,
    ) : VirtualStackingCapabilitySelectionResult

    public data class Rejected(
        public val diagnostic: String,
    ) : VirtualStackingCapabilitySelectionResult
}
