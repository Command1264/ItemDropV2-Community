package com.github.command1264.itemdropv2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PluginMetricsControllerTest {
    @Test
    fun `uses the registered ItemDropV2 bStats plugin id`() {
        assertEquals(32797, BSTATS_PLUGIN_ID)
    }

    @Test
    fun `starts one metrics session and shuts it down once`() {
        var starts = 0
        var shutdowns = 0
        val warnings = mutableListOf<String>()
        val controller =
            PluginMetricsController(
                createSession = {
                    starts++
                    MetricsSession { shutdowns++ }
                },
                warningSink = { message, _ -> warnings += message },
            )

        assertTrue(controller.start())
        assertTrue(controller.start())
        controller.close()
        controller.close()

        assertEquals(1, starts)
        assertEquals(1, shutdowns)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `reports startup failure without retaining a partial session`() {
        var attempts = 0
        val warnings = mutableListOf<String>()
        val controller =
            PluginMetricsController(
                createSession = {
                    attempts++
                    throw IllegalStateException("fixture failure")
                },
                warningSink = { message, _ -> warnings += message },
            )

        assertFalse(controller.start())
        assertFalse(controller.start())
        controller.close()

        assertEquals(2, attempts)
        assertEquals(
            listOf(
                "bStats metrics startup failed (IllegalStateException).",
                "bStats metrics startup failed (IllegalStateException).",
            ),
            warnings,
        )
    }

    @Test
    fun `keeps the plugin available when metrics linkage fails`() {
        val warnings = mutableListOf<String>()
        val controller =
            PluginMetricsController(
                createSession = { throw NoClassDefFoundError("fixture failure") },
                warningSink = { message, _ -> warnings += message },
            )

        assertFalse(controller.start())

        assertEquals(
            listOf("bStats metrics startup failed (NoClassDefFoundError)."),
            warnings,
        )
    }

    @Test
    fun `reports shutdown failure and clears the active session`() {
        var starts = 0
        val warnings = mutableListOf<String>()
        val controller =
            PluginMetricsController(
                createSession = {
                    starts++
                    MetricsSession { throw IllegalStateException("fixture failure") }
                },
                warningSink = { message, _ -> warnings += message },
            )

        assertTrue(controller.start())
        controller.close()
        assertTrue(controller.start())

        assertEquals(2, starts)
        assertEquals(
            listOf("bStats metrics shutdown failed (IllegalStateException)."),
            warnings,
        )
    }
}
