package com.github.command1264.itemdropv2.platform.view.bukkit

import com.github.command1264.itemdropv2.core.BackendProvider
import com.github.command1264.itemdropv2.core.PresentationBackend
import com.github.command1264.itemdropv2.core.ServerFingerprint
import com.github.command1264.itemdropv2.core.ServerPlatform

public class BukkitPresentationBackendProvider : BackendProvider {
    override val id: String = ID
    override val artifactClassifier: String = "universal"

    override fun incompatibilities(fingerprint: ServerFingerprint): List<String> =
        when {
            fingerprint.platform == ServerPlatform.UNKNOWN ->
                listOf("The universal backend requires a Spigot or Paper server.")
            !isSupportedMinecraftVersion(fingerprint.minecraftVersion) ->
                listOf("The universal backend requires Minecraft 1.14 or newer.")
            else -> emptyList()
        }

    override fun createBackend(fingerprint: ServerFingerprint): PresentationBackend = BukkitPresentationBackend()

    private fun isSupportedMinecraftVersion(version: String): Boolean {
        val components = version.split('.')
        val major = components.firstOrNull()?.toIntOrNull()
        val minor = components.getOrNull(1)?.toIntOrNull()
        return when {
            major == null -> false
            major > LEGACY_MAJOR_VERSION -> true
            minor == null -> false
            else -> major == LEGACY_MAJOR_VERSION && minor >= MINIMUM_LEGACY_MINOR_VERSION
        }
    }

    public companion object {
        public const val ID: String = "bukkit-entity-name"
        private const val LEGACY_MAJOR_VERSION = 1
        private const val MINIMUM_LEGACY_MINOR_VERSION = 14
    }
}
