package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.type.Scaffolding
import java.util.ArrayDeque
import java.util.UUID

internal class FallingBlockSourceScanner(
    private val maxScaffoldingPositions: Int = 8_192,
    private val scaffoldingStabilityAnalyzer: ScaffoldingStabilityAnalyzer = ScaffoldingStabilityAnalyzer(),
    private val speleothemSourceScanner: SpeleothemSourceScanner = SpeleothemSourceScanner(),
) {
    init {
        require(maxScaffoldingPositions > 0) { "maximum scaffolding position count must be positive" }
    }

    fun afterBreak(
        brokenBlock: Block,
        ownerUuid: UUID,
    ): List<FallingBlockSource> {
        val gravity = scanGravityColumn(brokenBlock.getRelative(0, 1, 0), ownerUuid)
        val scaffolding = scanScaffoldingAfterBreak(brokenBlock, ownerUuid)
        val speleothem = speleothemSourceScanner.afterBreak(brokenBlock, ownerUuid)
        return gravity + scaffolding + speleothem
    }

    fun afterPlace(
        placedBlock: Block,
        ownerUuid: UUID,
    ): List<FallingBlockSource> =
        if (placedBlock.type.hasGravity() || placedBlock.type == Material.SCAFFOLDING) {
            listOf(placedBlock.toSource(ownerUuid))
        } else {
            emptyList()
        }

    private fun scanGravityColumn(
        bottom: Block,
        ownerUuid: UUID,
    ): List<FallingBlockSource> {
        val sources = mutableListOf<FallingBlockSource>()
        var current = bottom
        while (current.y < current.world.maxHeight && current.type.hasGravity()) {
            sources += current.toSource(ownerUuid)
            if (current.y >= current.world.maxHeight - 1) break
            current = current.getRelative(0, 1, 0)
        }
        return sources
    }

    private fun scanScaffoldingAfterBreak(
        brokenBlock: Block,
        ownerUuid: UUID,
    ): List<FallingBlockSource> {
        val starts =
            scaffoldingStarts(brokenBlock)
                .filter { block -> block.type == Material.SCAFFOLDING }
                .toList()
        val brokenPosition = brokenBlock.toScaffoldingPosition()
        val scaffolding =
            starts
                .takeIf(List<Block>::isNotEmpty)
                ?.let { candidates -> collectScaffoldingTopology(brokenBlock.world, brokenPosition, candidates) }
                .orEmpty()
        val directSource =
            if (brokenBlock.type == Material.SCAFFOLDING) {
                listOf(brokenBlock.toSource(ownerUuid))
            } else {
                emptyList()
            }
        return directSource + unstableScaffoldingSources(scaffolding, brokenPosition, ownerUuid)
    }

    private fun collectScaffoldingTopology(
        world: World,
        brokenPosition: ScaffoldingPosition,
        starts: List<Block>,
    ): Map<ScaffoldingPosition, Block>? {
        val visited = mutableSetOf<ScaffoldingPosition>()
        val queue = ArrayDeque(starts)
        val scaffolding = linkedMapOf<ScaffoldingPosition, Block>()
        while (queue.isNotEmpty()) {
            val block = queue.removeFirst()
            val position = block.toScaffoldingPosition()
            val shouldCollect =
                visited.add(position) &&
                    block.type == Material.SCAFFOLDING &&
                    position != brokenPosition
            if (shouldCollect) {
                if (scaffolding.size >= maxScaffoldingPositions) return null
                scaffolding[position] = block
                enqueueLoadedScaffoldingNeighbours(block, world, visited, queue)
            }
        }
        return scaffolding
    }

    private fun enqueueLoadedScaffoldingNeighbours(
        block: Block,
        world: World,
        visited: Set<ScaffoldingPosition>,
        queue: ArrayDeque<Block>,
    ) {
        SCAFFOLDING_NEIGHBOURS.forEach { offset ->
            val x = block.x + offset.x
            val y = block.y + offset.y
            val z = block.z + offset.z
            val isLoaded =
                y >= BukkitWorldMinimumHeightAccess.minimumHeight(world) &&
                    y < world.maxHeight &&
                    world.isChunkLoaded(x shr CHUNK_COORDINATE_SHIFT, z shr CHUNK_COORDINATE_SHIFT)
            val neighbourPosition = ScaffoldingPosition(x, y, z)
            if (isLoaded && neighbourPosition !in visited) {
                queue.add(world.getBlockAt(x, y, z))
            }
        }
    }

    private fun unstableScaffoldingSources(
        scaffolding: Map<ScaffoldingPosition, Block>,
        brokenPosition: ScaffoldingPosition,
        ownerUuid: UUID,
    ): List<FallingBlockSource> {
        val grounded =
            scaffolding
                .filterValues { block -> block.isGroundedScaffolding(brokenPosition) }
                .keys
        val maximumDistance =
            scaffolding.values
                .asSequence()
                .mapNotNull { block -> (block.blockData as? Scaffolding)?.maximumDistance }
                .firstOrNull() ?: DEFAULT_MAXIMUM_SCAFFOLDING_DISTANCE
        return scaffoldingStabilityAnalyzer
            .unstablePositions(scaffolding.keys, grounded, maximumDistance)
            .mapNotNull(scaffolding::get)
            .map { block -> block.toSource(ownerUuid) }
    }

    private fun scaffoldingStarts(brokenBlock: Block): Sequence<Block> =
        if (brokenBlock.type == Material.SCAFFOLDING) {
            SCAFFOLDING_NEIGHBOURS
                .asSequence()
                .map { offset -> brokenBlock.getRelative(offset.x, offset.y, offset.z) }
        } else {
            sequenceOf(brokenBlock.getRelative(0, 1, 0))
        }

    private fun Block.isGroundedScaffolding(brokenPosition: ScaffoldingPosition): Boolean {
        val data = blockData as? Scaffolding ?: return false
        val below = getRelative(0, -1, 0)
        return data.distance == 0 &&
            below.toScaffoldingPosition() != brokenPosition &&
            below.type != Material.SCAFFOLDING &&
            below.type.isSolid
    }

    private fun Block.toScaffoldingPosition(): ScaffoldingPosition = ScaffoldingPosition(x, y, z)

    private fun Block.toSource(ownerUuid: UUID): FallingBlockSource =
        FallingBlockSource(
            worldId = world.uid,
            x = x,
            y = y,
            z = z,
            materialName = type.name,
            ownerUuid = ownerUuid,
        )

    private data class Offset(
        val x: Int,
        val y: Int,
        val z: Int,
    )

    private companion object {
        private const val CHUNK_COORDINATE_SHIFT = 4
        private const val DEFAULT_MAXIMUM_SCAFFOLDING_DISTANCE = 7
        private val SCAFFOLDING_NEIGHBOURS =
            listOf(
                Offset(0, 1, 0),
                Offset(0, -1, 0),
                Offset(1, 0, 0),
                Offset(-1, 0, 0),
                Offset(0, 0, 1),
                Offset(0, 0, -1),
            )
    }
}
