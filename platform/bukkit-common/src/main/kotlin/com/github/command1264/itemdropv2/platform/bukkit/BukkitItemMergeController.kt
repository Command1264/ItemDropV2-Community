package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemDisplayRequest
import com.github.command1264.itemdropv2.core.ItemDisplayService
import com.github.command1264.itemdropv2.core.ItemNameService
import com.github.command1264.itemdropv2.core.MinecraftItemRarity
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ItemMergeEvent
import java.util.UUID

internal fun interface ItemDisplayRequestResolver {
    fun resolve(entityId: UUID): ItemDisplayRequest?
}

public class BukkitItemMergeController internal constructor(
    service: ItemDisplayService,
    taskExecutor: MainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val requestResolver: ItemDisplayRequestResolver,
) : Listener {
    public constructor(
        service: ItemDisplayService,
        taskExecutor: MainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemNameService: ItemNameService = fallbackItemNameService,
        translationKeyResolver: BukkitItemTranslationKeyResolver = BukkitItemTranslationKeyResolver(),
        displayStateResolver: ItemDisplayStateResolver = ItemDisplayStateResolver { ItemDisplayStateSnapshot() },
        rarityResolver: BukkitItemRarityResolver = BukkitItemRarityResolver { MinecraftItemRarity.COMMON },
    ) : this(
        service,
        taskExecutor,
        warningSink,
        ItemDisplayRequestResolver { entityId ->
            resolveBukkitItem(entityId)?.let { item ->
                createItemDisplayRequest(
                    item,
                    warningSink,
                    itemNameService,
                    translationKeyResolver,
                    displayStateResolver,
                    rarityResolver,
                )
            }
        },
    )

    private val coordinator = ScheduledItemDisplayCoordinator(service, taskExecutor, warningSink)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onItemMerge(event: ItemMergeEvent) {
        if (event.isAsynchronous) {
            warningSink.warn("ignored asynchronous ItemMergeEvent")
            return
        }
        refreshAfterMerge(event.target.uniqueId)
    }

    internal fun refreshAfterMerge(targetEntityId: UUID) {
        coordinator.submitDeferred { requestResolver.resolve(targetEntityId) }
    }
}
