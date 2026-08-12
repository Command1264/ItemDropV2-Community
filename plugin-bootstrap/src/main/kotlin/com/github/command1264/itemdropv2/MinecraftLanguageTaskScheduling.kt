package com.github.command1264.itemdropv2

import org.bukkit.plugin.IllegalPluginAccessException

internal fun scheduleMinecraftLanguageTask(
    pluginEnabled: () -> Boolean,
    schedule: () -> Unit,
) {
    if (!pluginEnabled()) return

    try {
        schedule()
    } catch (error: IllegalPluginAccessException) {
        if (pluginEnabled()) throw error
    }
}
