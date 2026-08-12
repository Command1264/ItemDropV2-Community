package com.github.command1264.itemdropv2.capability.virtualstacking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class VirtualStackingCapabilitySelectionServiceTest {
    @Test
    fun `selects exactly one provider`() {
        val provider = FakeProvider("pro")

        val result = VirtualStackingCapabilitySelectionService().select(listOf(provider))

        assertEquals(VirtualStackingCapabilitySelectionResult.Selected(provider), result)
    }

    @Test
    fun `rejects zero providers`() {
        val result = VirtualStackingCapabilitySelectionService().select(emptyList())

        assertEquals(
            VirtualStackingCapabilitySelectionResult.Rejected(
                "no virtual stacking capability provider found",
            ),
            result,
        )
    }

    @Test
    fun `rejects multiple providers without choosing one`() {
        val result =
            VirtualStackingCapabilitySelectionService().select(
                listOf(FakeProvider("a"), FakeProvider("b")),
            )

        assertEquals(
            VirtualStackingCapabilitySelectionResult.Rejected(
                "multiple virtual stacking capability providers found: 2",
            ),
            result,
        )
    }

    private class FakeProvider(
        override val id: String,
    ) : VirtualStackingCapabilityProvider {
        override val creationCapabilityAvailable: Boolean = true

        override fun create(context: VirtualStackingCapabilityContext): VirtualStackingCapability =
            error("selection tests must not create providers")
    }
}
