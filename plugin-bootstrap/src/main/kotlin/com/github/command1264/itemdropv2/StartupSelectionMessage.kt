package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.core.ItemStatePersistenceMode
import com.github.command1264.itemdropv2.core.ServerFingerprint

internal object StartupSelectionMessage {
    fun format(
        fingerprint: ServerFingerprint,
        backendId: String,
        persistenceMode: ItemStatePersistenceMode,
    ): String =
        "ItemDropV2 detected server: " +
            "platform=${safe(fingerprint.platform.name)}, " +
            "minecraft=${safe(fingerprint.minecraftVersion)}, " +
            "implementation=${safe(fingerprint.implementationVersion)}; " +
            "selected scheme: ${safe(backendId)} (${schemeDescription(backendId)}); " +
            "item-state persistence: ${persistenceMode.name}."

    private fun schemeDescription(backendId: String): String =
        when (backendId) {
            "paper-client-translation" -> "Paper Adventure client translation"
            "bukkit-entity-name" -> "Bukkit-compatible server-side names"
            else -> "runtime-selected presentation"
        }

    private fun safe(value: String): String =
        value
            .asSequence()
            .map { character -> if (character.isISOControl()) ' ' else character }
            .joinToString("")
            .take(MAX_FIELD_LENGTH)

    private const val MAX_FIELD_LENGTH = 256
}
