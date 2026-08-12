package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class VirtualItemMergeServiceTest {
    @Test
    fun `native stack limit scales by material stack size and supports unstackable items explicitly`() {
        val settings =
            VirtualItemStackingSettings(
                enabled = true,
                maximumNativeStacksPerEntity = 128,
                unstackableItemsEnabled = true,
            )

        assertEquals(8_192L, settings.maximumAmountFor(nativeStackSize = 64).value)
        assertEquals(2_048L, settings.maximumAmountFor(nativeStackSize = 16).value)
        assertEquals(128L, settings.maximumAmountFor(nativeStackSize = 1).value)
        assertTrue(settings.manages(nativeStackSize = 1))
        assertEquals(
            Long.MAX_VALUE,
            settings
                .copy(maximumNativeStacksPerEntity = Long.MAX_VALUE)
                .maximumAmountFor(nativeStackSize = 64)
                .value,
        )
    }

    @Test
    fun `legacy absolute maximum remains independent of material stack size`() {
        val settings =
            VirtualItemStackingSettings(
                enabled = true,
                maximumAmountPerEntity = VirtualItemAmount.of(8_192),
            )

        assertEquals(8_192L, settings.maximumAmountFor(nativeStackSize = 64).value)
        assertEquals(8_192L, settings.maximumAmountFor(nativeStackSize = 1).value)
        assertFalse(settings.manages(nativeStackSize = 1))
    }

    @Test
    fun `virtual amount accepts positive long range only`() {
        assertEquals(1L, VirtualItemAmount.of(1).value)
        assertEquals(Long.MAX_VALUE, VirtualItemAmount.of(Long.MAX_VALUE).value)
        assertThrows<IllegalArgumentException> { VirtualItemAmount.of(0) }
        assertThrows<IllegalArgumentException> { VirtualItemAmount.of(-1) }
    }

    @Test
    fun `virtual stacking defaults disabled with 8192 maximum`() {
        val settings = VirtualItemStackingSettings()

        assertEquals(false, settings.enabled)
        assertEquals(8_192L, settings.maximumAmountPerEntity.value)
    }

    @Test
    fun `moves source amount into target without overflowing long`() {
        val outcome =
            service(maximum = Long.MAX_VALUE).merge(
                request(
                    sourceAmount = Long.MAX_VALUE,
                    targetAmount = 1,
                ),
            )

        val merged = assertInstanceOf(VirtualItemMergeOutcome.Merged::class.java, outcome)
        assertEquals(Long.MAX_VALUE - 1, merged.movedAmount.value)
        assertEquals(Long.MAX_VALUE, merged.targetState.virtualAmount?.value)
        assertEquals(1L, merged.sourceRemainderState?.virtualAmount?.value)
    }

    @Test
    fun `merges one and 8191 into configured maximum`() {
        val outcome =
            service(maximum = 8_192).merge(
                request(
                    sourceAmount = 1,
                    targetAmount = 8_191,
                ),
            )

        val merged = assertInstanceOf(VirtualItemMergeOutcome.Merged::class.java, outcome)
        assertEquals(1L, merged.movedAmount.value)
        assertEquals(8_192L, merged.targetState.virtualAmount?.value)
        assertEquals(null, merged.sourceRemainderState)
    }

    @Test
    fun `keeps source remainder when target reaches configured maximum`() {
        val outcome =
            service(maximum = 8_192).merge(
                request(
                    sourceAmount = 100,
                    targetAmount = 8_150,
                ),
            )

        val merged = assertInstanceOf(VirtualItemMergeOutcome.Merged::class.java, outcome)
        assertEquals(42L, merged.movedAmount.value)
        assertEquals(8_192L, merged.targetState.virtualAmount?.value)
        assertEquals(58L, merged.sourceRemainderState?.virtualAmount?.value)
    }

    @Test
    fun `average gives both item entities equal weight and preserves source remainder age`() {
        val outcome =
            service(maximum = 10).merge(
                request(
                    sourceAmount = 5,
                    targetAmount = 8,
                    sourceAge = 0,
                    sourceLifetime = 100,
                    targetAge = 90,
                    targetLifetime = 250,
                ),
            )

        val merged = assertInstanceOf(VirtualItemMergeOutcome.Merged::class.java, outcome)
        assertEquals(45L, merged.targetState.elapsedLifetimeSeconds)
        assertEquals(250L, merged.targetState.originalLifetimeSeconds)
        assertEquals(205L, merged.targetState.remainingLifetimeSeconds)
        assertEquals(3L, merged.sourceRemainderState?.virtualAmount?.value)
        assertEquals(0L, merged.sourceRemainderState?.elapsedLifetimeSeconds)
        assertEquals(100L, merged.sourceRemainderState?.remainingLifetimeSeconds)
    }

    @Test
    fun `average does not copy permanent source original lifetime into finite target`() {
        val outcome =
            service(maximum = Long.MAX_VALUE).merge(
                request(
                    sourceAmount = Long.MAX_VALUE - 1,
                    targetAmount = 1,
                    sourceAge = 10,
                    sourceLifetime = ItemLifetimeSettings.NEVER_EXPIRES,
                    targetAge = 20,
                    targetLifetime = 123,
                ),
            )

        val merged = assertInstanceOf(VirtualItemMergeOutcome.Merged::class.java, outcome)
        assertEquals(123L, merged.targetState.originalLifetimeSeconds)
        assertEquals(15L, merged.targetState.elapsedLifetimeSeconds)
        assertEquals(108L, merged.targetState.remainingLifetimeSeconds)
    }

    @Test
    fun `rejects mismatched ownership without changing either state`() {
        val sourceOwner = ItemOwnership(SOURCE_OWNER, 30)
        val targetOwner = ItemOwnership(TARGET_OWNER, 30)

        val outcome =
            service().merge(
                request(
                    sourceAmount = 10,
                    targetAmount = 10,
                    sourceOwnership = sourceOwner,
                    targetOwnership = targetOwner,
                ),
            )

        assertEquals(
            VirtualItemMergeOutcome.Rejected(VirtualItemMergeRejection.OWNER_MISMATCH),
            outcome,
        )
    }

    @Test
    fun `distinguishes one owned item from one unowned item`() {
        val ownership = ItemOwnership(SOURCE_OWNER, 30)

        listOf(ownership to null, null to ownership).forEach { (sourceOwnership, targetOwnership) ->
            val outcome =
                service().merge(
                    request(
                        sourceAmount = 10,
                        targetAmount = 10,
                        sourceOwnership = sourceOwnership,
                        targetOwnership = targetOwnership,
                    ),
                )

            assertEquals(
                VirtualItemMergeOutcome.Rejected(VirtualItemMergeRejection.OWNERSHIP_PRESENCE_MISMATCH),
                outcome,
            )
        }
    }

    @Test
    fun `every ownership strategy changes target while partial remainder keeps source ownership`() {
        val sourceOwnership = ItemOwnership(SOURCE_OWNER, 11)
        val targetOwnership = ItemOwnership(SOURCE_OWNER, 20)
        val expected =
            mapOf(
                MergeOwnershipStrategy.AVERAGE to 16L,
                MergeOwnershipStrategy.MAXIMUM to 20L,
                MergeOwnershipStrategy.MINIMUM to 11L,
                MergeOwnershipStrategy.RESET to 30L,
            )

        expected.forEach { (strategy, expectedProtection) ->
            val outcome =
                service(
                    maximum = 10,
                    ownershipStrategy = strategy,
                    resetProtectionSeconds = 30,
                ).merge(
                    request(
                        sourceAmount = 5,
                        targetAmount = 8,
                        sourceOwnership = sourceOwnership,
                        targetOwnership = targetOwnership,
                    ),
                )

            val merged = assertInstanceOf(VirtualItemMergeOutcome.Merged::class.java, outcome)
            assertEquals(ItemOwnership(SOURCE_OWNER, expectedProtection), merged.targetState.ownership)
            assertEquals(sourceOwnership, merged.sourceRemainderState?.ownership)
        }
    }

    @Test
    fun `reset with zero protection clears virtual target ownership`() {
        val ownership = ItemOwnership(SOURCE_OWNER, 20)
        val outcome =
            service(
                ownershipStrategy = MergeOwnershipStrategy.RESET,
                resetProtectionSeconds = 0,
            ).merge(
                request(
                    sourceAmount = 1,
                    targetAmount = 1,
                    sourceOwnership = ownership,
                    targetOwnership = ownership,
                ),
            )

        val merged = assertInstanceOf(VirtualItemMergeOutcome.Merged::class.java, outcome)
        assertEquals(null, merged.targetState.ownership)
    }

    @Test
    fun `rejects target already at configured maximum`() {
        val outcome =
            service(maximum = 8_192).merge(
                request(
                    sourceAmount = 1,
                    targetAmount = 8_192,
                ),
            )

        assertEquals(
            VirtualItemMergeOutcome.Rejected(VirtualItemMergeRejection.TARGET_AT_CAPACITY),
            outcome,
        )
    }

    @Test
    fun `rejects state amount above configured maximum`() {
        val outcome =
            service(maximum = 8_192).merge(
                request(
                    sourceAmount = 8_193,
                    targetAmount = 1,
                ),
            )

        assertEquals(
            VirtualItemMergeOutcome.Rejected(VirtualItemMergeRejection.AMOUNT_EXCEEDS_MAXIMUM),
            outcome,
        )
    }

    @Test
    fun `maximum chooses minimum elapsed and minimum chooses maximum elapsed`() {
        val request =
            request(
                sourceAmount = 10,
                targetAmount = 10,
                sourceAge = 20,
                sourceLifetime = 100,
                targetAge = 70,
                targetLifetime = 200,
            )

        val maximum =
            assertInstanceOf(
                VirtualItemMergeOutcome.Merged::class.java,
                service(strategy = MergeLifetimeStrategy.MAXIMUM).merge(request),
            )
        val minimum =
            assertInstanceOf(
                VirtualItemMergeOutcome.Merged::class.java,
                service(strategy = MergeLifetimeStrategy.MINIMUM).merge(request),
            )

        assertEquals(20L, maximum.targetState.elapsedLifetimeSeconds)
        assertEquals(180L, maximum.targetState.remainingLifetimeSeconds)
        assertEquals(70L, minimum.targetState.elapsedLifetimeSeconds)
        assertEquals(130L, minimum.targetState.remainingLifetimeSeconds)
    }

    @Test
    fun `permanent remaining lifetime follows each merge strategy`() {
        val lifetimePairs =
            listOf(
                ItemLifetimeSettings.NEVER_EXPIRES to 100L,
                100L to ItemLifetimeSettings.NEVER_EXPIRES,
            )
        MergeLifetimeStrategy.entries.forEach { strategy ->
            lifetimePairs.forEach { (sourceLifetime, targetLifetime) ->
                val outcome =
                    service(strategy = strategy).merge(
                        request(
                            sourceAmount = 1,
                            targetAmount = 1,
                            sourceAge = 10,
                            sourceLifetime = sourceLifetime,
                            targetAge = 20,
                            targetLifetime = targetLifetime,
                        ),
                    )

                val merged = assertInstanceOf(VirtualItemMergeOutcome.Merged::class.java, outcome)
                assertEquals(
                    ItemElapsedLifetimeSelector.select(strategy, 10, 20),
                    merged.targetState.elapsedLifetimeSeconds,
                )
            }
        }
    }

    private fun service(
        maximum: Long = 8_192,
        strategy: MergeLifetimeStrategy = MergeLifetimeStrategy.AVERAGE,
        ownershipStrategy: MergeOwnershipStrategy = MergeOwnershipStrategy.AVERAGE,
        resetProtectionSeconds: Long = ItemOwnershipSettings.DEFAULT_PROTECTION_SECONDS,
    ): VirtualItemMergeService =
        VirtualItemMergeService(
            maximumAmountPerEntity = VirtualItemAmount.of(maximum),
            lifetimeStrategy = strategy,
            ownershipStrategy = ownershipStrategy,
            resetProtectionSeconds = resetProtectionSeconds,
        )

    private fun request(
        sourceAmount: Long,
        targetAmount: Long,
        sourceOwnership: ItemOwnership? = null,
        targetOwnership: ItemOwnership? = null,
        sourceAge: Long = 0,
        sourceLifetime: Long? = 100,
        targetAge: Long = 0,
        targetLifetime: Long? = 100,
    ): VirtualItemMergeRequest =
        VirtualItemMergeRequest(
            sourceState =
                ItemState(
                    sourceOwnership,
                    sourceAge,
                    sourceLifetime,
                    VirtualItemAmount.of(sourceAmount),
                ),
            targetState =
                ItemState(
                    targetOwnership,
                    targetAge,
                    targetLifetime,
                    VirtualItemAmount.of(targetAmount),
                ),
        )

    private companion object {
        private val SOURCE_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000101")
        private val TARGET_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000102")
    }
}
