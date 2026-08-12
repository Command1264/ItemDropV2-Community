package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.block.Block
import org.bukkit.entity.Item
import java.util.UUID

internal class ChorusPlantOwnershipTracker(
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val tracker: ChorusPlantCollapseTracker = ChorusPlantCollapseTracker(),
    private val scanner: ChorusPlantTopologyScanner = ChorusPlantTopologyScanner(),
) {
    fun record(
        brokenBlock: Block,
        ownerUuid: UUID,
    ) {
        when (val scan = scanner.scan(brokenBlock)) {
            ChorusTopologyScanResult.Empty -> Unit
            is ChorusTopologyScanResult.Truncated ->
                warningSink.warn(
                    "chorus plant ownership topology exceeded ${scan.maximumBlocks} loaded blocks",
                )
            is ChorusTopologyScanResult.Complete -> recordCompleteTopology(brokenBlock, ownerUuid, scan.positions)
        }
    }

    fun claim(item: Item): UUID? {
        val location = item.location
        return tracker
            .claim(
                ChorusFruitItemSpawn(
                    entityId = item.uniqueId,
                    worldId = item.world.uid,
                    position = ChorusBlockPosition(location.blockX, location.blockY, location.blockZ),
                ),
            )?.ownerUuid
    }

    fun wasClaimed(entityId: UUID): Boolean = tracker.wasClaimed(entityId)

    fun clear() {
        tracker.clear()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun recordCompleteTopology(
        brokenBlock: Block,
        ownerUuid: UUID,
        positions: Set<ChorusBlockPosition>,
    ) {
        val contextId =
            tracker.record(
                ChorusPlantCollapseContext(
                    worldId = brokenBlock.world.uid,
                    positions = positions,
                    ownerUuid = ownerUuid,
                ),
            )
        try {
            delayedTaskExecutor.execute(
                plantTopologyContextLifetimeTicks(positions.size, MINIMUM_CONTEXT_LIFETIME_TICKS),
            ) {
                tracker.expire(contextId)
            }
        } catch (error: RuntimeException) {
            tracker.expire(contextId)
            warningSink.warn(
                "chorus plant ownership context scheduling failed (${error.javaClass.simpleName})",
            )
        }
    }

    private companion object {
        private const val MINIMUM_CONTEXT_LIFETIME_TICKS = 40L
    }
}
