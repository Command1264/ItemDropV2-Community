package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.World
import java.lang.reflect.Method

internal object BukkitWorldMinimumHeightAccess {
    private val capabilityByWorldClass =
        RuntimeClassCapabilityCache<WorldMinimumHeightCapability>(::resolveCapability)

    fun minimumHeight(world: World): Int =
        when (val capability = capabilityByWorldClass.get(world.javaClass)) {
            is WorldMinimumHeightCapability.Supported ->
                runCatching { capability.method.invoke(world) as Int }.getOrDefault(LEGACY_MINIMUM_HEIGHT)
            WorldMinimumHeightCapability.Unsupported -> LEGACY_MINIMUM_HEIGHT
        }

    private fun resolveCapability(type: Class<*>): WorldMinimumHeightCapability =
        try {
            WorldMinimumHeightCapability.Supported(type.getMethod(MINIMUM_HEIGHT_METHOD))
        } catch (_: NoSuchMethodException) {
            WorldMinimumHeightCapability.Unsupported
        } catch (_: SecurityException) {
            WorldMinimumHeightCapability.Unsupported
        } catch (_: LinkageError) {
            WorldMinimumHeightCapability.Unsupported
        }

    private const val MINIMUM_HEIGHT_METHOD = "getMinHeight"
    private const val LEGACY_MINIMUM_HEIGHT = 0
}

private sealed interface WorldMinimumHeightCapability {
    data class Supported(
        val method: Method,
    ) : WorldMinimumHeightCapability

    data object Unsupported : WorldMinimumHeightCapability
}
