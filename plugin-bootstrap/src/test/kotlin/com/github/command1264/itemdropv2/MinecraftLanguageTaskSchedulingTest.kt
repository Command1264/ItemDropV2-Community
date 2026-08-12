package com.github.command1264.itemdropv2

import org.bukkit.plugin.IllegalPluginAccessException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MinecraftLanguageTaskSchedulingTest {
    @Test
    fun `disabled plugin does not submit language callback`() {
        var submitted = false

        scheduleMinecraftLanguageTask(pluginEnabled = { false }) {
            submitted = true
        }

        assertFalse(submitted)
    }

    @Test
    fun `disable race suppresses only bukkit disabled scheduling rejection`() {
        var enabled = true

        scheduleMinecraftLanguageTask(pluginEnabled = { enabled }) {
            enabled = false
            throw IllegalPluginAccessException("plugin disabled during scheduling")
        }

        assertFalse(enabled)
    }

    @Test
    fun `enabled plugin preserves unexpected bukkit scheduling rejection`() {
        assertThrows(IllegalPluginAccessException::class.java) {
            scheduleMinecraftLanguageTask(pluginEnabled = { true }) {
                throw IllegalPluginAccessException("unexpected rejection")
            }
        }
    }
}
