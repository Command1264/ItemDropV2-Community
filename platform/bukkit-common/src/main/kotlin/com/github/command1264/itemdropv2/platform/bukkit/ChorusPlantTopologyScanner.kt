package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.Material
import org.bukkit.block.Block
import java.util.ArrayDeque

internal sealed interface ChorusTopologyScanResult {
    data object Empty : ChorusTopologyScanResult

    data class Complete(
        val positions: Set<ChorusBlockPosition>,
    ) : ChorusTopologyScanResult

    data class Truncated(
        val maximumBlocks: Int,
    ) : ChorusTopologyScanResult
}

internal class ChorusPlantTopologyScanner(
    private val maxBlocks: Int = DEFAULT_MAX_BLOCKS,
) {
    init {
        require(maxBlocks > 0) { "maximum chorus topology size must be positive" }
    }

    fun scan(brokenBlock: Block): ChorusTopologyScanResult = findSeed(brokenBlock)?.let(::scanFromSeed) ?: ChorusTopologyScanResult.Empty

    private fun scanFromSeed(seed: Block): ChorusTopologyScanResult {
        val queue = ArrayDeque<Block>()
        val positions = linkedSetOf<ChorusBlockPosition>()
        queue.add(seed)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val currentPosition = current.position()
            if (currentPosition in positions || !current.type.isChorusTopologyBlock()) continue
            if (positions.size >= maxBlocks) {
                return ChorusTopologyScanResult.Truncated(maxBlocks)
            }
            positions += currentPosition

            DIRECTIONS.forEach { (xOffset, yOffset, zOffset) ->
                val neighborPosition =
                    ChorusBlockPosition(
                        x = current.x + xOffset,
                        y = current.y + yOffset,
                        z = current.z + zOffset,
                    )
                if (
                    neighborPosition !in positions &&
                    neighborPosition.y in 0 until current.world.maxHeight &&
                    current.world.isChunkLoaded(
                        Math.floorDiv(neighborPosition.x, CHUNK_WIDTH),
                        Math.floorDiv(neighborPosition.z, CHUNK_WIDTH),
                    )
                ) {
                    queue.add(current.getRelative(xOffset, yOffset, zOffset))
                }
            }
        }

        return ChorusTopologyScanResult.Complete(positions)
    }

    private fun findSeed(brokenBlock: Block): Block? =
        when {
            brokenBlock.type == Material.CHORUS_PLANT -> brokenBlock
            brokenBlock.type == Material.CHORUS_FLOWER -> null
            brokenBlock.y >= brokenBlock.world.maxHeight - 1 -> null
            else ->
                brokenBlock
                    .getRelative(0, 1, 0)
                    .takeIf { it.type == Material.CHORUS_PLANT }
        }

    private fun Block.position(): ChorusBlockPosition = ChorusBlockPosition(x, y, z)

    private fun Material.isChorusTopologyBlock(): Boolean = this == Material.CHORUS_PLANT || this == Material.CHORUS_FLOWER

    private companion object {
        private const val DEFAULT_MAX_BLOCKS = 8_192
        private const val CHUNK_WIDTH = 16
        private val DIRECTIONS =
            listOf(
                Triple(1, 0, 0),
                Triple(-1, 0, 0),
                Triple(0, 1, 0),
                Triple(0, -1, 0),
                Triple(0, 0, 1),
                Triple(0, 0, -1),
            )
    }
}
