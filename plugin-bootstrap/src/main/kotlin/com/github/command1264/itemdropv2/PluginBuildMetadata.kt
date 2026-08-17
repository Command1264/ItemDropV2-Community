package com.github.command1264.itemdropv2

import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Paths
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest

internal data class PluginBuildMetadata(
    val shortCommit: String,
    val fullCommit: String,
    val dirty: Boolean?,
) {
    fun displayVersion(pluginVersion: String): String = "$pluginVersion (git ${shortLabel()})"

    fun shortLabel(): String =
        when {
            shortCommit == UNKNOWN_VALUE -> UNKNOWN_VALUE
            dirty == true -> "$shortCommit-dirty"
            else -> shortCommit
        }

    fun diagnosticFields(): Map<String, String> =
        mapOf(
            "plugin.git-commit" to fullCommit,
            "plugin.git-commit-short" to shortCommit,
            "plugin.git-dirty" to (dirty?.toString() ?: UNKNOWN_VALUE),
        )

    companion object {
        val UNKNOWN: PluginBuildMetadata =
            PluginBuildMetadata(
                shortCommit = UNKNOWN_VALUE,
                fullCommit = UNKNOWN_VALUE,
                dirty = null,
            )

        fun load(anchorClass: Class<*>): PluginBuildMetadata {
            val configuration =
                anchorClass.classLoader.getResourceAsStream(PLUGIN_DESCRIPTOR_PATH)?.use {
                    YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8))
                }
            val manifest = loadOwnManifest(anchorClass)
            return if (configuration != null && manifest != null) {
                from(configuration, manifest)
            } else {
                UNKNOWN
            }
        }

        fun from(
            configuration: ConfigurationSection,
            manifest: Manifest,
        ): PluginBuildMetadata {
            val shortCommit = configuration.getString(SHORT_COMMIT_PATH).orEmpty()
            val dirtyText = configuration.getString(DIRTY_PATH).orEmpty()
            val fullCommit = manifest.mainAttributes.getValue(FULL_COMMIT_ATTRIBUTE).orEmpty()
            val manifestShortCommit = manifest.mainAttributes.getValue(SHORT_COMMIT_ATTRIBUTE).orEmpty()
            val manifestDirtyText = manifest.mainAttributes.getValue(DIRTY_ATTRIBUTE).orEmpty()
            val dirty =
                when (dirtyText) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            val valid =
                shortCommit.matches(SHORT_COMMIT_PATTERN) &&
                    fullCommit.matches(FULL_COMMIT_PATTERN) &&
                    fullCommit.startsWith(shortCommit) &&
                    manifestShortCommit == shortCommit &&
                    manifestDirtyText == dirtyText &&
                    dirty != null
            return if (valid) {
                PluginBuildMetadata(shortCommit, fullCommit, dirty)
            } else {
                UNKNOWN
            }
        }

        private fun loadOwnManifest(anchorClass: Class<*>): Manifest? =
            runCatching {
                val location = anchorClass.protectionDomain?.codeSource?.location ?: return@runCatching null
                if (location.protocol != FILE_PROTOCOL) {
                    return@runCatching null
                }
                val path = Paths.get(location.toURI())
                if (!Files.isRegularFile(path)) {
                    return@runCatching null
                }
                JarFile(path.toFile()).use { it.manifest }
            }.getOrNull()

        private const val PLUGIN_DESCRIPTOR_PATH = "plugin.yml"
        private const val SHORT_COMMIT_PATH = "build.git-commit"
        private const val DIRTY_PATH = "build.git-dirty"
        private const val FULL_COMMIT_ATTRIBUTE = "Git-Commit"
        private const val SHORT_COMMIT_ATTRIBUTE = "Git-Commit-Short"
        private const val DIRTY_ATTRIBUTE = "Git-Dirty"
        private const val FILE_PROTOCOL = "file"
        private const val UNKNOWN_VALUE = "unknown"
        private val SHORT_COMMIT_PATTERN = Regex("[0-9a-f]{7}")
        private val FULL_COMMIT_PATTERN = Regex("[0-9a-f]{40}")
    }
}
