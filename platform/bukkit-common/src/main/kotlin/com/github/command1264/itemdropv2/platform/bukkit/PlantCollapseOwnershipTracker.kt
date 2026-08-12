package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.Item
import java.util.UUID

internal class PlantCollapseOwnershipTracker(
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val cactusTracker: CactusCollapseTracker = CactusCollapseTracker(),
    private val sugarCaneTracker: SugarCaneCollapseTracker = SugarCaneCollapseTracker(),
    private val bambooTracker: BambooCollapseTracker = BambooCollapseTracker(),
    private val verticalPlantTracker: VerticalPlantOwnershipTracker =
        VerticalPlantOwnershipTracker(delayedTaskExecutor, warningSink),
    private val chorusTracker: ChorusPlantOwnershipTracker =
        ChorusPlantOwnershipTracker(delayedTaskExecutor, warningSink),
) {
    fun record(
        brokenBlock: Block,
        ownerUuid: UUID,
    ) {
        recordCactus(brokenBlock, ownerUuid)
        recordSugarCane(brokenBlock, ownerUuid)
        recordBamboo(brokenBlock, ownerUuid)
        verticalPlantTracker.record(brokenBlock, ownerUuid)
        chorusTracker.record(brokenBlock, ownerUuid)
    }

    fun claim(item: Item): UUID? {
        val location = item.location
        val materialName = item.itemStack.type.name
        return when (materialName) {
            "CACTUS",
            "CACTUS_FLOWER",
            ->
                cactusTracker
                    .claim(
                        CactusItemSpawn(
                            entityId = item.uniqueId,
                            worldId = item.world.uid,
                            x = location.blockX,
                            z = location.blockZ,
                            y = location.blockY,
                            materialName = materialName,
                        ),
                    )?.ownerUuid
            "SUGAR_CANE" ->
                sugarCaneTracker
                    .claim(
                        SugarCaneItemSpawn(
                            entityId = item.uniqueId,
                            worldId = item.world.uid,
                            x = location.blockX,
                            z = location.blockZ,
                            y = location.blockY,
                        ),
                    )?.ownerUuid
            "BAMBOO" ->
                bambooTracker
                    .claim(
                        BambooItemSpawn(
                            entityId = item.uniqueId,
                            worldId = item.world.uid,
                            x = location.blockX,
                            z = location.blockZ,
                            y = location.blockY,
                        ),
                    )?.ownerUuid
            "CHORUS_FRUIT",
            "CHORUS_FLOWER",
            -> chorusTracker.claim(item)
            else -> verticalPlantTracker.claim(item)
        }
    }

    fun wasClaimed(entityId: UUID): Boolean =
        cactusTracker.wasClaimed(entityId) ||
            sugarCaneTracker.wasClaimed(entityId) ||
            bambooTracker.wasClaimed(entityId) ||
            verticalPlantTracker.wasClaimed(entityId) ||
            chorusTracker.wasClaimed(entityId)

    fun isMergeProtected(entityId: UUID): Boolean =
        cactusTracker.isMergeProtected(entityId) ||
            sugarCaneTracker.isMergeProtected(entityId) ||
            bambooTracker.isMergeProtected(entityId) ||
            verticalPlantTracker.isMergeProtected(entityId)

    fun clear() {
        cactusTracker.clear()
        sugarCaneTracker.clear()
        bambooTracker.clear()
        verticalPlantTracker.clear()
        chorusTracker.clear()
    }

    private fun recordCactus(
        brokenBlock: Block,
        ownerUuid: UUID,
    ) {
        val bottom = columnBottomFor(brokenBlock, Material.CACTUS) ?: return
        val top = findColumnTop(bottom, CACTUS_STRUCTURE_NAMES)
        val expectedItemsByY =
            (bottom.y..top.y).associateWith { y ->
                when (bottom.getRelative(0, y - bottom.y, 0).type.name) {
                    "CACTUS_FLOWER" -> setOf("CACTUS_FLOWER")
                    else -> setOf("CACTUS")
                }
            }
        val contextId =
            cactusTracker.record(
                CactusCollapseContext(
                    worldId = bottom.world.uid,
                    x = bottom.x,
                    z = bottom.z,
                    minY = bottom.y,
                    maxY = top.y,
                    ownerUuid = ownerUuid,
                    expectedItemNamesByY = expectedItemsByY,
                ),
            )
        scheduleExpiry(
            delayTicks = cactusColumnContextLifetimeTicks(top.y - bottom.y + 1),
            expire = { cactusTracker.expire(contextId) },
            failureMessage = "cactus ownership context scheduling failed",
        )
    }

    private fun recordSugarCane(
        brokenBlock: Block,
        ownerUuid: UUID,
    ) {
        val bottom = columnBottomFor(brokenBlock, Material.SUGAR_CANE) ?: return
        val top = findColumnTop(bottom, setOf(Material.SUGAR_CANE.name))
        val contextId =
            sugarCaneTracker.record(
                SugarCaneCollapseContext(
                    worldId = bottom.world.uid,
                    x = bottom.x,
                    z = bottom.z,
                    minY = bottom.y,
                    maxY = top.y,
                    ownerUuid = ownerUuid,
                ),
            )
        scheduleExpiry(
            delayTicks =
                plantColumnContextLifetimeTicks(
                    top.y - bottom.y + 1,
                    COLUMN_MINIMUM_CONTEXT_LIFETIME_TICKS,
                ),
            expire = { sugarCaneTracker.expire(contextId) },
            failureMessage = "sugar cane ownership context scheduling failed",
        )
    }

    private fun recordBamboo(
        brokenBlock: Block,
        ownerUuid: UUID,
    ) {
        val bottom = columnBottomFor(brokenBlock, Material.BAMBOO) ?: return
        val top = findColumnTop(bottom, setOf(Material.BAMBOO.name))
        val contextId =
            bambooTracker.record(
                BambooCollapseContext(
                    worldId = bottom.world.uid,
                    x = bottom.x,
                    z = bottom.z,
                    minY = bottom.y,
                    maxY = top.y,
                    ownerUuid = ownerUuid,
                ),
            )
        scheduleExpiry(
            delayTicks =
                plantColumnContextLifetimeTicks(
                    top.y - bottom.y + 1,
                    COLUMN_MINIMUM_CONTEXT_LIFETIME_TICKS,
                ),
            expire = { bambooTracker.expire(contextId) },
            failureMessage = "bamboo ownership context scheduling failed",
        )
    }

    private fun columnBottomFor(
        block: Block,
        material: Material,
    ): Block? =
        when {
            block.type == material -> block
            block.getRelative(0, 1, 0).type == material -> block.getRelative(0, 1, 0)
            else -> null
        }

    private fun findColumnTop(
        bottom: Block,
        materialNames: Set<String>,
    ): Block {
        var top = bottom
        val worldMaxHeight = bottom.world.maxHeight
        while (top.y < worldMaxHeight - 1) {
            val above = top.getRelative(0, 1, 0)
            if (above.type.name !in materialNames) return top
            top = above
        }
        return top
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleExpiry(
        delayTicks: Long,
        expire: () -> Unit,
        failureMessage: String,
    ) {
        try {
            delayedTaskExecutor.execute(delayTicks, expire)
        } catch (error: RuntimeException) {
            expire()
            warningSink.warn("$failureMessage (${error.javaClass.simpleName})")
        }
    }

    private companion object {
        private const val COLUMN_MINIMUM_CONTEXT_LIFETIME_TICKS = 40L
        private val CACTUS_STRUCTURE_NAMES = setOf("CACTUS", "CACTUS_FLOWER")
    }
}

internal fun cactusColumnContextLifetimeTicks(columnHeight: Int): Long =
    plantColumnContextLifetimeTicks(columnHeight, CACTUS_MINIMUM_CONTEXT_LIFETIME_TICKS)

internal fun plantColumnContextLifetimeTicks(
    columnHeight: Int,
    minimumTicks: Long,
): Long {
    require(columnHeight > 0) { "plant column height must be positive" }
    require(minimumTicks > 0) { "minimum context lifetime must be positive" }
    return maxOf(minimumTicks, columnHeight.toLong() + COLUMN_EXPIRY_MARGIN_TICKS)
}

internal fun plantTopologyContextLifetimeTicks(
    topologySize: Int,
    minimumTicks: Long,
): Long {
    require(topologySize > 0) { "plant topology size must be positive" }
    require(minimumTicks > 0) { "minimum context lifetime must be positive" }
    return maxOf(minimumTicks, topologySize.toLong() + COLUMN_EXPIRY_MARGIN_TICKS)
}

private const val COLUMN_EXPIRY_MARGIN_TICKS = 60L
private const val CACTUS_MINIMUM_CONTEXT_LIFETIME_TICKS = 40L
