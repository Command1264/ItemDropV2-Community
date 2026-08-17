package com.github.command1264.itemdropv2

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanonicalItemSyncPolicyTest {
    @Test
    fun `loaded entity fallback remains enabled for the affected 1 14 family`() {
        listOf("1.14", "1.14.1", "1.14.4", "1.14.4-rc1").forEach { version ->
            assertTrue(requiresLoadedEntityCanonicalFallback(version), version)
        }
    }

    @Test
    fun `modern and invalid versions do not enable the loaded entity scan`() {
        listOf("1.15", "1.16.5", "1.20.4", "1.21.11", "26.2", "unknown").forEach { version ->
            assertFalse(requiresLoadedEntityCanonicalFallback(version), version)
        }
    }
}
