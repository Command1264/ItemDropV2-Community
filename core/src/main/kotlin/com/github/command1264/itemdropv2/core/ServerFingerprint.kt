package com.github.command1264.itemdropv2.core

@InternalItemDropApi
public data class ServerFingerprint(
    public val platform: ServerPlatform,
    public val minecraftVersion: String,
    public val implementationVersion: String,
) {
    init {
        require(minecraftVersion.isNotBlank()) { "minecraftVersion must not be blank" }
        require(implementationVersion.isNotBlank()) { "implementationVersion must not be blank" }
    }
}

@InternalItemDropApi
public enum class ServerPlatform {
    SPIGOT,
    PAPER,
    UNKNOWN,
}
