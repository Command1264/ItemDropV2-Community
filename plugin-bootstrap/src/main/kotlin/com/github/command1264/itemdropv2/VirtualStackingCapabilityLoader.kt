package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapabilityProvider
import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapabilitySelectionResult
import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapabilitySelectionService
import com.github.command1264.itemdropv2.core.VirtualItemStackingSettings
import com.github.command1264.itemdropv2.core.VirtualStackingRuntimeMode
import com.github.command1264.itemdropv2.core.VirtualStackingRuntimeModeResolver
import java.util.ServiceConfigurationError

internal class VirtualStackingCapabilityLoader(
    private val providers: () -> List<VirtualStackingCapabilityProvider>,
) {
    fun load(): VirtualStackingCapabilityLoadResult =
        try {
            when (val selected = VirtualStackingCapabilitySelectionService().select(providers())) {
                is VirtualStackingCapabilitySelectionResult.Selected ->
                    VirtualStackingCapabilityLoadResult.Loaded(selected.provider)
                is VirtualStackingCapabilitySelectionResult.Rejected ->
                    VirtualStackingCapabilityLoadResult.Rejected(selected.diagnostic)
            }
        } catch (_: ServiceConfigurationError) {
            VirtualStackingCapabilityLoadResult.Rejected(
                "provider loading failed (ServiceConfigurationError)",
            )
        } catch (error: LinkageError) {
            VirtualStackingCapabilityLoadResult.Rejected(
                "provider linkage failed (${error.javaClass.simpleName})",
            )
        }
}

internal sealed interface VirtualStackingCapabilityLoadResult {
    data class Loaded(
        val provider: VirtualStackingCapabilityProvider,
    ) : VirtualStackingCapabilityLoadResult

    data class Rejected(
        val diagnostic: String,
    ) : VirtualStackingCapabilityLoadResult
}

internal fun virtualStackingRuntimeModeName(
    settings: VirtualItemStackingSettings,
    modeResolver: VirtualStackingRuntimeModeResolver,
): String =
    when (modeResolver.creationMode(settings, nativeStackSize = 64)) {
        VirtualStackingRuntimeMode.ACTIVE -> "active"
        VirtualStackingRuntimeMode.LEGACY_DRAIN -> "legacy-drain"
    }
