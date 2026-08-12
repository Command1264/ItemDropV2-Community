package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.Material
import org.bukkit.block.BlockState
import org.bukkit.block.Container
import org.bukkit.entity.Entity
import org.bukkit.inventory.Inventory

public fun interface InventoryMutationNotifier {
    public fun notifyMutation(inventory: Inventory): InventoryMutationNotificationResult
}

public sealed interface InventoryMutationNotificationResult {
    public data object Applied : InventoryMutationNotificationResult

    public data object NotRequired : InventoryMutationNotificationResult

    public data class Failed(
        public val errorType: String,
    ) : InventoryMutationNotificationResult
}

/**
 * 補回原版 Hopper 吸入流程在 Inventory mutation 後執行的 comparator 更新。
 *
 * Virtual pickup 必須取消原生事件並自行提交 Inventory，因此原版在事件返回後才會執行
 * 的 container changed/update 不會發生。此實作只使用自 1.14 起穩定存在的 Bukkit API：
 * Block inventory 以 fresh BlockState 更新，Entity inventory 則刷新附近的 Detector Rail。
 */
public class BukkitInventoryMutationNotifier : InventoryMutationNotifier {
    @Suppress("TooGenericExceptionCaught")
    override fun notifyMutation(inventory: Inventory): InventoryMutationNotificationResult =
        try {
            when (val holder = inventory.holder) {
                is BlockState -> notifyBlockInventory(holder)
                is Entity -> notifyEntityInventory(holder)
                else -> InventoryMutationNotificationResult.NotRequired
            }
        } catch (error: RuntimeException) {
            InventoryMutationNotificationResult.Failed(error.javaClass.simpleName)
        }

    private fun notifyBlockInventory(holder: BlockState): InventoryMutationNotificationResult {
        val block = holder.block
        val freshState = block.state
        if (freshState is Container) {
            freshState.snapshotInventory.storageContents =
                (freshState.inventory.storageContents).map { item -> item?.clone() }.toTypedArray()
        }
        val blockUpdated = freshState.update(false, true)
        return if (blockUpdated) {
            InventoryMutationNotificationResult.Applied
        } else {
            InventoryMutationNotificationResult.Failed("BlockStateUpdateRejected")
        }
    }

    private fun notifyEntityInventory(holder: Entity): InventoryMutationNotificationResult {
        val location = holder.location
        val world = location.world ?: return InventoryMutationNotificationResult.NotRequired
        var refreshed = 0
        for (xOffset in -ENTITY_RAIL_SEARCH_RADIUS..ENTITY_RAIL_SEARCH_RADIUS) {
            for (zOffset in -ENTITY_RAIL_SEARCH_RADIUS..ENTITY_RAIL_SEARCH_RADIUS) {
                for (yOffset in ENTITY_RAIL_MIN_Y_OFFSET..ENTITY_RAIL_MAX_Y_OFFSET) {
                    val block =
                        world.getBlockAt(
                            location.blockX + xOffset,
                            location.blockY + yOffset,
                            location.blockZ + zOffset,
                        )
                    if (block.type == Material.DETECTOR_RAIL && block.state.update(true, true)) {
                        refreshed++
                    }
                }
            }
        }
        return if (refreshed > 0) {
            InventoryMutationNotificationResult.Applied
        } else {
            InventoryMutationNotificationResult.NotRequired
        }
    }

    private companion object {
        private const val ENTITY_RAIL_SEARCH_RADIUS = 1
        private const val ENTITY_RAIL_MIN_Y_OFFSET = -1
        private const val ENTITY_RAIL_MAX_Y_OFFSET = 0
    }
}
