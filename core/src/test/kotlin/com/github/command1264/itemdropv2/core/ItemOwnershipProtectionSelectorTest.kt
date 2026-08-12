package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class ItemOwnershipProtectionSelectorTest {
    @Test
    fun `selects protection seconds for every strategy`() {
        val source = ownership(11)
        val target = ownership(20)

        assertEquals(ownership(16), select(MergeOwnershipStrategy.AVERAGE, source, target))
        assertEquals(ownership(20), select(MergeOwnershipStrategy.MAXIMUM, source, target))
        assertEquals(ownership(11), select(MergeOwnershipStrategy.MINIMUM, source, target))
        assertEquals(ownership(30), select(MergeOwnershipStrategy.RESET, source, target))
    }

    @Test
    fun `average is overflow safe and rounds upward`() {
        assertEquals(
            Int.MAX_VALUE.toLong(),
            requireNotNull(
                select(
                    MergeOwnershipStrategy.AVERAGE,
                    ownership(Int.MAX_VALUE.toLong()),
                    ownership(Int.MAX_VALUE.toLong()),
                ),
            ).protectionSecondsRemaining,
        )
    }

    @Test
    fun `reset with zero protection clears ownership`() {
        assertNull(
            ItemOwnershipProtectionSelector.select(
                strategy = MergeOwnershipStrategy.RESET,
                source = ownership(11),
                target = ownership(20),
                resetProtectionSeconds = 0,
            ),
        )
    }

    @Test
    fun `two unowned items remain unowned`() {
        assertNull(
            ItemOwnershipProtectionSelector.select(
                strategy = MergeOwnershipStrategy.AVERAGE,
                source = null,
                target = null,
                resetProtectionSeconds = 30,
            ),
        )
    }

    @Test
    fun `rejects mixed or different ownership groups`() {
        assertThrows(IllegalArgumentException::class.java) {
            select(MergeOwnershipStrategy.AVERAGE, ownership(10), null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            select(
                MergeOwnershipStrategy.AVERAGE,
                ownership(10),
                ItemOwnership(OWNER, 20, listOf(OWNER)),
            )
        }
    }

    private fun select(
        strategy: MergeOwnershipStrategy,
        source: ItemOwnership?,
        target: ItemOwnership?,
    ): ItemOwnership? =
        ItemOwnershipProtectionSelector.select(
            strategy = strategy,
            source = source,
            target = target,
            resetProtectionSeconds = 30,
        )

    private fun ownership(seconds: Long): ItemOwnership = ItemOwnership(OWNER, seconds, listOf(OWNER, SECOND_OWNER))

    private companion object {
        private val OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val SECOND_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000002")
    }
}
