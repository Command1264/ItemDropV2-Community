package com.github.command1264.itemdropv2

internal class PluginIdentityGuard {
    fun evaluate(
        expectedName: String,
        loadedPluginNames: List<String>,
    ): PluginIdentityResult {
        val matches = loadedPluginNames.count { name -> name.equals(expectedName, ignoreCase = true) }
        return if (matches == 1) PluginIdentityResult.Accepted else PluginIdentityResult.Rejected(matches)
    }
}

internal sealed interface PluginIdentityResult {
    data object Accepted : PluginIdentityResult

    data class Rejected(
        val matchingPlugins: Int,
    ) : PluginIdentityResult
}
