package com.github.command1264.itemdropv2.capability.virtualstacking.community

import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapabilityReloadResult
import com.github.command1264.itemdropv2.core.VirtualItemStackingSettings
import com.github.command1264.itemdropv2.platform.bukkit.VirtualItemCarrierNormalization
import com.github.command1264.itemdropv2.platform.bukkit.VirtualItemCarrierNormalizationOutcome
import com.github.command1264.itemdropv2.platform.bukkit.VirtualItemMergeOperations
import com.github.command1264.itemdropv2.platform.bukkit.VirtualItemMergeTransactionOutcome
import org.bukkit.entity.Item
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class CommunityVirtualStackingCapabilityTest {
    @Test
    fun `community provider never advertises creation`() {
        val provider = CommunityVirtualStackingCapabilityProvider()

        assertEquals("community-legacy-drain", provider.id)
        assertFalse(provider.creationCapabilityAvailable)
        assertNull(provider.configurationFragmentResource)
    }

    @Test
    fun `community lifecycle remains inert when virtual stacking is enabled`() {
        val capability = CommunityVirtualStackingCapability(FakeNormalization, FakeMergeOperations)

        capability.activate()
        val reloaded = capability.reload(VirtualItemStackingSettings(enabled = true))
        capability.close()

        assertSame(VirtualStackingCapabilityReloadResult.Applied, reloaded)
        assertSame(FakeNormalization, capability.carrierNormalization)
        assertSame(FakeMergeOperations, capability.mergeOperations)
        assertNull(capability.mergeDispatchContext)
        assertDoesNotThrow {
            capability.activate()
            capability.close()
            capability.close()
        }
    }

    private data object FakeNormalization : VirtualItemCarrierNormalization {
        override fun normalize(item: Item): VirtualItemCarrierNormalizationOutcome = VirtualItemCarrierNormalizationOutcome.Unmanaged
    }

    private data object FakeMergeOperations : VirtualItemMergeOperations {
        override fun isVirtualPair(
            source: Item,
            target: Item,
        ): Boolean = false

        override fun canAttempt(
            source: Item,
            target: Item,
        ): Boolean = false

        override fun merge(
            source: Item,
            target: Item,
        ): VirtualItemMergeTransactionOutcome = VirtualItemMergeTransactionOutcome.NotVirtual
    }
}
