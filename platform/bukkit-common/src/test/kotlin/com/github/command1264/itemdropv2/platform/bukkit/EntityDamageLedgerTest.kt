package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID

class EntityDamageLedgerTest {
    @Test
    fun `healing does not remove prior contribution and overkill is capped`() {
        val ledger = EntityDamageLedger()

        ledger.record(TARGET, FIRST, finalDamage = 8.0, healthBeforeDamage = 10.0, currentTick = 1, timeoutTicks = 100)
        ledger.record(TARGET, FIRST, finalDamage = 8.0, healthBeforeDamage = 10.0, currentTick = 2, timeoutTicks = 100)
        ledger.record(TARGET, SECOND, finalDamage = 100.0, healthBeforeDamage = 3.0, currentTick = 3, timeoutTicks = 100)

        val contributions = requireNotNull(ledger.consume(TARGET, currentTick = 3, timeoutTicks = 100))
        assertEquals(16.0, contributions[0].effectiveDamage)
        assertEquals(3.0, contributions[1].effectiveDamage)
    }

    @Test
    fun `expired combat is discarded`() {
        val ledger = EntityDamageLedger()
        ledger.record(TARGET, FIRST, finalDamage = 5.0, healthBeforeDamage = 20.0, currentTick = 1, timeoutTicks = 20)

        assertNull(ledger.consume(TARGET, currentTick = 22, timeoutTicks = 20))
    }

    private companion object {
        private val TARGET = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002")
    }
}
