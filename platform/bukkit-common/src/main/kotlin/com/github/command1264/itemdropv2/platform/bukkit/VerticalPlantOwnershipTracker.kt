package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.block.Block
import org.bukkit.entity.Item
import java.util.UUID

internal class VerticalPlantOwnershipTracker(
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val tracker: VerticalPlantCollapseTracker = VerticalPlantCollapseTracker(),
) {
    fun record(
        brokenBlock: Block,
        ownerUuid: UUID,
    ) {
        val match =
            VERTICAL_PLANT_SPECS
                .asSequence()
                .mapNotNull { spec -> spec.seedFor(brokenBlock)?.let { seed -> spec to seed } }
                .firstOrNull() ?: return
        val (spec, seed) = match
        val bottom = findColumnBottom(seed, spec.blockNames)
        val top = findColumnTop(bottom, spec.blockNames)
        val affectedRange =
            affectedVerticalPlantRange(
                bottomY = bottom.y,
                topY = top.y,
                brokenY = brokenBlock.y,
                brokenIsPlant = brokenBlock.type.name in spec.blockNames,
                supportOffsetY = spec.supportOffsetY,
            )
        val expectedItemsByY =
            affectedRange.associateWith { y ->
                val blockName = bottom.getRelative(0, y - bottom.y, 0).type.name
                spec.expectedItemNamesByBlockName.getValue(blockName)
            }
        val contextId =
            tracker.record(
                VerticalPlantCollapseContext(
                    worldId = bottom.world.uid,
                    x = bottom.x,
                    z = bottom.z,
                    ownerUuid = ownerUuid,
                    expectedItemNamesByY = expectedItemsByY,
                ),
            )
        scheduleExpiry(contextId, spec.id, expectedItemsByY.size)
    }

    fun claim(item: Item): UUID? {
        val location = item.location
        return tracker
            .claim(
                VerticalPlantItemSpawn(
                    entityId = item.uniqueId,
                    worldId = item.world.uid,
                    x = location.blockX,
                    z = location.blockZ,
                    y = location.blockY,
                    materialName = item.itemStack.type.name,
                ),
            )?.ownerUuid
    }

    fun wasClaimed(entityId: UUID): Boolean = tracker.wasClaimed(entityId)

    fun isMergeProtected(entityId: UUID): Boolean = tracker.isMergeProtected(entityId)

    fun clear() {
        tracker.clear()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleExpiry(
        contextId: Long,
        plantId: String,
        columnHeight: Int,
    ) {
        try {
            delayedTaskExecutor.execute(
                plantColumnContextLifetimeTicks(columnHeight, MINIMUM_CONTEXT_LIFETIME_TICKS),
            ) {
                tracker.expire(contextId)
            }
        } catch (error: RuntimeException) {
            tracker.expire(contextId)
            warningSink.warn("$plantId ownership context scheduling failed (${error.javaClass.simpleName})")
        }
    }

    private fun findColumnBottom(
        seed: Block,
        materialNames: Set<String>,
    ): Block {
        var bottom = seed
        val worldMinHeight = BukkitWorldMinimumHeightAccess.minimumHeight(seed.world)
        while (bottom.y > worldMinHeight) {
            val below = bottom.getRelative(0, -1, 0)
            if (below.type.name !in materialNames) return bottom
            bottom = below
        }
        return bottom
    }

    private fun findColumnTop(
        bottom: Block,
        materialNames: Set<String>,
    ): Block {
        var top = bottom
        while (top.y < bottom.world.maxHeight - 1) {
            val above = top.getRelative(0, 1, 0)
            if (above.type.name !in materialNames) return top
            top = above
        }
        return top
    }

    private companion object {
        private const val MINIMUM_CONTEXT_LIFETIME_TICKS = 40L
        private val VERTICAL_PLANT_SPECS =
            listOf(
                VerticalPlantSpec(
                    id = "kelp",
                    blockNames = setOf("KELP", "KELP_PLANT"),
                    expectedItemNamesByBlockName =
                        mapOf(
                            "KELP" to setOf("KELP"),
                            "KELP_PLANT" to setOf("KELP"),
                        ),
                    supportOffsetY = 1,
                ),
                VerticalPlantSpec.sameDrop(
                    id = "twisting vines",
                    blockNames = setOf("TWISTING_VINES", "TWISTING_VINES_PLANT"),
                    itemName = "TWISTING_VINES",
                    supportOffsetY = 1,
                ),
                VerticalPlantSpec.sameDrop(
                    id = "weeping vines",
                    blockNames = setOf("WEEPING_VINES", "WEEPING_VINES_PLANT"),
                    itemName = "WEEPING_VINES",
                    supportOffsetY = -1,
                ),
                VerticalPlantSpec.sameDrop(
                    id = "cave vines",
                    blockNames = setOf("CAVE_VINES", "CAVE_VINES_PLANT"),
                    itemName = "GLOW_BERRIES",
                    supportOffsetY = -1,
                ),
                VerticalPlantSpec.sameDrop(
                    id = "big dripleaf",
                    blockNames = setOf("BIG_DRIPLEAF", "BIG_DRIPLEAF_STEM"),
                    itemName = "BIG_DRIPLEAF",
                    supportOffsetY = 1,
                ),
            )
    }
}

private data class VerticalPlantSpec(
    val id: String,
    val blockNames: Set<String>,
    val expectedItemNamesByBlockName: Map<String, Set<String>>,
    val supportOffsetY: Int,
) {
    init {
        require(blockNames.isNotEmpty()) { "vertical plant block names must not be empty" }
        require(expectedItemNamesByBlockName.keys == blockNames) {
            "every vertical plant block must define expected item names"
        }
        require(supportOffsetY == -1 || supportOffsetY == 1) {
            "vertical plant support offset must be one block"
        }
    }

    fun seedFor(block: Block): Block? =
        when {
            block.type.name in blockNames -> block
            block.getRelative(0, supportOffsetY, 0).type.name in blockNames ->
                block.getRelative(0, supportOffsetY, 0)
            else -> null
        }

    companion object {
        fun sameDrop(
            id: String,
            blockNames: Set<String>,
            itemName: String,
            supportOffsetY: Int,
        ): VerticalPlantSpec =
            VerticalPlantSpec(
                id = id,
                blockNames = blockNames,
                expectedItemNamesByBlockName = blockNames.associateWith { setOf(itemName) },
                supportOffsetY = supportOffsetY,
            )
    }
}

internal fun affectedVerticalPlantRange(
    bottomY: Int,
    topY: Int,
    brokenY: Int,
    brokenIsPlant: Boolean,
    supportOffsetY: Int,
): IntRange {
    require(bottomY <= topY) { "vertical plant column must not be inverted" }
    require(supportOffsetY == -1 || supportOffsetY == 1) {
        "vertical plant support offset must be one block"
    }
    if (!brokenIsPlant) return bottomY..topY
    require(brokenY in bottomY..topY) { "broken vertical plant must be inside the column" }
    return if (supportOffsetY > 0) brokenY..topY else bottomY..brokenY
}
