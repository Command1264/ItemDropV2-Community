package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.block.Block
import java.util.UUID

internal enum class SpeleothemDirection(
    val offsetY: Int,
) {
    UP(1),
    DOWN(-1),
}

internal class SpeleothemSourceScanner(
    private val maxPositions: Int = 8_192,
) {
    init {
        require(maxPositions > 0) { "maximum speleothem position count must be positive" }
    }

    fun afterBreak(
        brokenBlock: Block,
        ownerUuid: UUID,
    ): List<FallingBlockSource> {
        val columns =
            if (brokenBlock.isSpeleothem()) {
                listOfNotNull(brokenBlock.speleothemDirection()?.let { direction -> brokenBlock to direction })
            } else {
                supportedColumns(brokenBlock)
            }
        val sources = mutableListOf<FallingBlockSource>()
        for ((seed, direction) in columns) {
            if (!collectColumn(seed, direction, ownerUuid, sources)) return emptyList()
        }
        return sources
    }

    private fun supportedColumns(brokenBlock: Block): List<Pair<Block, SpeleothemDirection>> =
        buildList {
            val above = brokenBlock.getRelative(0, 1, 0)
            if (above.speleothemDirection() == SpeleothemDirection.UP) {
                add(above to SpeleothemDirection.UP)
            }
            val below = brokenBlock.getRelative(0, -1, 0)
            if (below.speleothemDirection() == SpeleothemDirection.DOWN) {
                add(below to SpeleothemDirection.DOWN)
            }
        }

    private fun collectColumn(
        seed: Block,
        direction: SpeleothemDirection,
        ownerUuid: UUID,
        sources: MutableList<FallingBlockSource>,
    ): Boolean {
        val materialName = seed.type.name
        var current = seed
        var overflow = false
        var withinWorld = true
        while (
            !overflow &&
            withinWorld &&
            current.matchesSpeleothemColumn(materialName, direction)
        ) {
            overflow = sources.size >= maxPositions
            if (!overflow) {
                sources += current.toSpeleothemSource(ownerUuid)
                val nextY = current.y + direction.offsetY
                withinWorld = nextY in BukkitWorldMinimumHeightAccess.minimumHeight(current.world) until current.world.maxHeight
                if (withinWorld) current = current.getRelative(0, direction.offsetY, 0)
            }
        }
        return !overflow
    }

    private fun Block.matchesSpeleothemColumn(
        materialName: String,
        direction: SpeleothemDirection,
    ): Boolean = type.name == materialName && speleothemDirection() == direction

    private fun Block.isSpeleothem(): Boolean = type.name in SPELEOTHEM_MATERIAL_NAMES

    private fun Block.speleothemDirection(): SpeleothemDirection? {
        if (!isSpeleothem()) return null
        return parseSpeleothemDirection(blockData.asString)
    }

    private fun Block.toSpeleothemSource(ownerUuid: UUID): FallingBlockSource =
        FallingBlockSource(
            worldId = world.uid,
            x = x,
            y = y,
            z = z,
            materialName = type.name,
            ownerUuid = ownerUuid,
        )

    private companion object {
        private val SPELEOTHEM_MATERIAL_NAMES =
            setOf(
                "POINTED_DRIPSTONE",
                "SULFUR_SPIKE",
            )
    }
}

internal fun parseSpeleothemDirection(blockData: String): SpeleothemDirection? {
    val properties =
        blockData
            .substringAfter('[', missingDelimiterValue = "")
            .substringBeforeLast(']', missingDelimiterValue = "")
    val direction =
        properties
            .split(',')
            .asSequence()
            .map { property -> property.split('=', limit = 2) }
            .firstOrNull { parts -> parts.size == 2 && parts[0].trim() == "vertical_direction" }
            ?.get(1)
            ?.trim()
    return when (direction) {
        "up" -> SpeleothemDirection.UP
        "down" -> SpeleothemDirection.DOWN
        else -> null
    }
}
