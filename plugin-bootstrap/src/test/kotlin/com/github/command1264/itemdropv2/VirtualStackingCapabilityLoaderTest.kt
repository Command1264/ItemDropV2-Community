package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapability
import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapabilityContext
import com.github.command1264.itemdropv2.capability.virtualstacking.VirtualStackingCapabilityProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.ServiceConfigurationError

class VirtualStackingCapabilityLoaderTest {
    @Test
    fun `loader returns selected provider`() {
        val provider = FakeProvider("pro")

        val result = VirtualStackingCapabilityLoader { listOf(provider) }.load()

        assertEquals(VirtualStackingCapabilityLoadResult.Loaded(provider), result)
    }

    @Test
    fun `loader rejects zero providers`() {
        val result = VirtualStackingCapabilityLoader { emptyList() }.load()

        assertEquals(
            VirtualStackingCapabilityLoadResult.Rejected(
                "no virtual stacking capability provider found",
            ),
            result,
        )
    }

    @Test
    fun `loader converts service configuration failure to rejection`() {
        val loader =
            VirtualStackingCapabilityLoader {
                throw ServiceConfigurationError("broken")
            }

        val result = loader.load()

        assertEquals(
            VirtualStackingCapabilityLoadResult.Rejected(
                "provider loading failed (ServiceConfigurationError)",
            ),
            result,
        )
    }

    @Test
    fun `loader converts linkage failure to rejection`() {
        val loader =
            VirtualStackingCapabilityLoader {
                throw NoClassDefFoundError("missing")
            }

        val result = loader.load()

        assertEquals(
            VirtualStackingCapabilityLoadResult.Rejected(
                "provider linkage failed (NoClassDefFoundError)",
            ),
            result,
        )
    }

    private class FakeProvider(
        override val id: String,
    ) : VirtualStackingCapabilityProvider {
        override val creationCapabilityAvailable: Boolean = true

        override fun create(context: VirtualStackingCapabilityContext): VirtualStackingCapability =
            error("loader tests must not create providers")
    }
}
