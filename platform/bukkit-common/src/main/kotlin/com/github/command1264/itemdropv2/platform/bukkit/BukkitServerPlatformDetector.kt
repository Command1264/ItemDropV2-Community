package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ServerPlatform

internal class BukkitServerPlatformDetector(
    private val classProbe: (ClassLoader, String) -> Boolean,
    private val paperBrandProbe: (ClassLoader) -> String?,
) {
    fun detect(
        classLoader: ClassLoader,
        implementationVersion: String,
    ): ServerPlatform {
        if (classProbe(classLoader, SERVER_BUILD_INFO)) {
            return if (paperBrandProbe(classLoader) == PAPER_BRAND_ID) {
                ServerPlatform.PAPER
            } else {
                ServerPlatform.UNKNOWN
            }
        }

        val hasLegacyPaperMarker =
            legacyPaperMarkerClasses.any { classProbe(classLoader, it) }
        val hasSpigotMarker = classProbe(classLoader, SPIGOT_MARKER)
        return when {
            hasLegacyPaperMarker && implementationVersion.startsWith(PAPER_VERSION_PREFIX) ->
                ServerPlatform.PAPER
            hasLegacyPaperMarker -> ServerPlatform.UNKNOWN
            hasSpigotMarker && isOfficialSpigotVersion(implementationVersion) -> ServerPlatform.SPIGOT
            else -> ServerPlatform.UNKNOWN
        }
    }

    private fun isOfficialSpigotVersion(implementationVersion: String): Boolean =
        legacySpigotVersion.matches(implementationVersion) ||
            modernSpigotVersion.matches(implementationVersion)

    private companion object {
        private const val SERVER_BUILD_INFO = "io.papermc.paper.ServerBuildInfo"
        private const val PAPER_BRAND_ID = "papermc:paper"
        private const val PAPER_VERSION_PREFIX = "git-Paper-"
        private const val SPIGOT_MARKER = "org.spigotmc.SpigotConfig"
        private const val SPIGOT_BUILD_PATTERN = "[0-9]+[a-zA-Z]?"
        private const val GIT_HASH_PATTERN = "[0-9a-fA-F]{7,40}"
        private const val MINECRAFT_SUFFIX_PATTERN = "(?: \\(MC: [0-9A-Za-z._-]+\\))?"
        private val legacySpigotVersion =
            Regex("^git-Spigot-$GIT_HASH_PATTERN-$GIT_HASH_PATTERN$MINECRAFT_SUFFIX_PATTERN$")
        private val modernSpigotVersion =
            Regex("^$SPIGOT_BUILD_PATTERN-Spigot-$GIT_HASH_PATTERN-$GIT_HASH_PATTERN$MINECRAFT_SUFFIX_PATTERN$")
        private val legacyPaperMarkerClasses =
            listOf(
                "io.papermc.paper.configuration.GlobalConfiguration",
                "com.destroystokyo.paper.PaperConfig",
            )
    }
}

internal fun readPaperBrandId(classLoader: ClassLoader): String? =
    try {
        val serverBuildInfoClass =
            Class.forName(
                "io.papermc.paper.ServerBuildInfo",
                false,
                classLoader,
            )
        val buildInfo = serverBuildInfoClass.getMethod("buildInfo").invoke(null)
        val brandIdMethod = serverBuildInfoClass.getMethod("brandId")
        val brandId = brandIdMethod.invoke(buildInfo)
        val asStringMethod = brandIdMethod.returnType.getMethod("asString")
        asStringMethod.invoke(brandId) as? String
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: SecurityException) {
        null
    } catch (_: LinkageError) {
        null
    }
