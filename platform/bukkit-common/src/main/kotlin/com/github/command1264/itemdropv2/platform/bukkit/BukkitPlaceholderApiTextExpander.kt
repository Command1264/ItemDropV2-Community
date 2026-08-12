package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemDisplayTextExpander
import me.clip.placeholderapi.PlaceholderAPI
import org.bukkit.OfflinePlayer
import java.util.UUID

public class BukkitPlaceholderApiTextExpander(
    private val offlinePlayerResolver: (UUID) -> OfflinePlayer,
    private val placeholderExpansion: (OfflinePlayer?, String) -> String = { player, text ->
        PlaceholderAPI.setPlaceholders(player, text)
    },
    private val warningSink: DisplayWarningSink,
) : ItemDisplayTextExpander {
    @Suppress("TooGenericExceptionCaught")
    override fun expand(
        playerId: UUID?,
        text: String,
    ): String =
        try {
            val player = playerId?.let(offlinePlayerResolver)
            placeholderExpansion(player, text)
        } catch (error: RuntimeException) {
            warningSink.warn("PlaceholderAPI expansion failed (${error.javaClass.simpleName})")
            text
        } catch (error: LinkageError) {
            warningSink.warn("PlaceholderAPI linkage failed (${error.javaClass.simpleName})")
            text
        }
}
