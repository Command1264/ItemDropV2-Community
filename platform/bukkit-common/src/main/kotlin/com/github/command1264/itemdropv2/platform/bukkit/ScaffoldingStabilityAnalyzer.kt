package com.github.command1264.itemdropv2.platform.bukkit

import java.util.PriorityQueue

internal data class ScaffoldingPosition(
    val x: Int,
    val y: Int,
    val z: Int,
)

internal class ScaffoldingStabilityAnalyzer {
    fun unstablePositions(
        positions: Set<ScaffoldingPosition>,
        groundedPositions: Set<ScaffoldingPosition>,
        maximumDistance: Int,
    ): Set<ScaffoldingPosition> {
        require(maximumDistance > 0) { "maximum scaffolding distance must be positive" }
        if (positions.isEmpty()) return emptySet()

        val distances = mutableMapOf<ScaffoldingPosition, Int>()
        val queue = PriorityQueue<ScaffoldingDistance>(compareBy(ScaffoldingDistance::distance))
        groundedPositions
            .asSequence()
            .filter(positions::contains)
            .forEach { position ->
                distances[position] = 0
                queue += ScaffoldingDistance(position, 0)
            }

        while (queue.isNotEmpty()) {
            val current = queue.remove()
            if (distances[current.position] != current.distance) continue
            relax(
                position = current.position.above(),
                candidateDistance = current.distance,
                positions = positions,
                maximumDistance = maximumDistance,
                distances = distances,
                queue = queue,
            )
            current.position.horizontalNeighbours().forEach { neighbour ->
                relax(
                    position = neighbour,
                    candidateDistance = current.distance + 1,
                    positions = positions,
                    maximumDistance = maximumDistance,
                    distances = distances,
                    queue = queue,
                )
            }
        }

        return positions - distances.keys
    }

    private fun relax(
        position: ScaffoldingPosition,
        candidateDistance: Int,
        positions: Set<ScaffoldingPosition>,
        maximumDistance: Int,
        distances: MutableMap<ScaffoldingPosition, Int>,
        queue: PriorityQueue<ScaffoldingDistance>,
    ) {
        if (position !in positions || candidateDistance >= maximumDistance) return
        val currentDistance = distances[position]
        if (currentDistance != null && currentDistance <= candidateDistance) return
        distances[position] = candidateDistance
        queue += ScaffoldingDistance(position, candidateDistance)
    }

    private fun ScaffoldingPosition.above(): ScaffoldingPosition = copy(y = y + 1)

    private fun ScaffoldingPosition.horizontalNeighbours(): List<ScaffoldingPosition> =
        listOf(
            copy(x = x + 1),
            copy(x = x - 1),
            copy(z = z + 1),
            copy(z = z - 1),
        )

    private data class ScaffoldingDistance(
        val position: ScaffoldingPosition,
        val distance: Int,
    )
}
