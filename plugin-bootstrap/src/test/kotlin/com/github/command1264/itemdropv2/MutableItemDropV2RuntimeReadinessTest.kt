package com.github.command1264.itemdropv2

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MutableItemDropV2RuntimeReadinessTest {
    @Test
    fun `readiness follows successful activation and disable lifecycle`() {
        val readiness = MutableItemDropV2RuntimeReadiness()

        assertFalse(readiness.isReady())
        readiness.markReady()
        assertTrue(readiness.isReady())
        readiness.markNotReady()
        assertFalse(readiness.isReady())
    }
}
