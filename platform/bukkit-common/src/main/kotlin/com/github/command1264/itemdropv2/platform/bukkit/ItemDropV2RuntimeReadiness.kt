package com.github.command1264.itemdropv2.platform.bukkit

/**
 * First-party lifecycle contract used by dependent integration fixtures.
 *
 * Bukkit's plugin dependency ordering only proves that [org.bukkit.plugin.Plugin.onEnable] returned.
 * Journal-backed endpoints can still be recovering state asynchronously at that point.
 */
public interface ItemDropV2RuntimeReadiness {
    public fun isReady(): Boolean
}
