package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ServerFingerprint
import com.github.command1264.itemdropv2.core.ServerPlatform
import org.bukkit.Server

public class BukkitServerFingerprintFactory(
    private val classProbe: (ClassLoader, String) -> Boolean,
    paperBrandProbe: (ClassLoader) -> String?,
) {
    public constructor() : this(::isClassAvailable, ::readPaperBrandId)

    private val platformDetector = BukkitServerPlatformDetector(classProbe, paperBrandProbe)

    public fun create(server: Server): ServerFingerprint {
        val classLoader = server.javaClass.classLoader
        val platform = platformDetector.detect(classLoader, server.version)
        return ServerFingerprint(
            platform = platform,
            minecraftVersion = extractMinecraftVersion(server.bukkitVersion),
            implementationVersion = server.version,
        )
    }

    private companion object {
        private fun isClassAvailable(
            classLoader: ClassLoader,
            className: String,
        ): Boolean =
            try {
                Class.forName(className, false, classLoader)
                true
            } catch (_: ClassNotFoundException) {
                false
            } catch (_: LinkageError) {
                false
            } catch (_: SecurityException) {
                false
            }
    }
}

internal fun extractMinecraftVersion(bukkitVersion: String): String =
    bukkitVersion
        .substringBefore(".build.")
        .substringBefore("-R")
