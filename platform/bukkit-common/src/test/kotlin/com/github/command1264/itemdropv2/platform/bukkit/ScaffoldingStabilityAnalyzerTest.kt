package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ScaffoldingStabilityAnalyzerTest {
    @Test
    fun `keeps vertical columns and six horizontal steps supported`() {
        val grounded = position(0, 64, 0)
        val positions =
            buildSet {
                add(grounded)
                add(position(0, 65, 0))
                (1..6).forEach { x -> add(position(x, 65, 0)) }
                add(position(6, 66, 0))
            }

        assertEquals(
            emptySet<ScaffoldingPosition>(),
            ScaffoldingStabilityAnalyzer().unstablePositions(
                positions,
                groundedPositions = setOf(grounded),
                maximumDistance = 7,
            ),
        )
    }

    @Test
    fun `marks distance seven and everything vertically above it unstable`() {
        val grounded = position(0, 64, 0)
        val distanceSeven = position(7, 65, 0)
        val above = position(7, 66, 0)
        val positions =
            buildSet {
                add(grounded)
                add(position(0, 65, 0))
                (1..7).forEach { x -> add(position(x, 65, 0)) }
                add(above)
            }

        assertEquals(
            setOf(distanceSeven, above),
            ScaffoldingStabilityAnalyzer().unstablePositions(
                positions,
                groundedPositions = setOf(grounded),
                maximumDistance = 7,
            ),
        )
    }

    @Test
    fun `uses an alternative grounded route after one support is removed`() {
        val leftGround = position(0, 64, 0)
        val rightGround = position(6, 64, 0)
        val positions =
            buildSet {
                add(leftGround)
                add(rightGround)
                (0..6).forEach { x -> add(position(x, 65, 0)) }
            }

        assertEquals(
            emptySet<ScaffoldingPosition>(),
            ScaffoldingStabilityAnalyzer().unstablePositions(
                positions - leftGround,
                groundedPositions = setOf(rightGround),
                maximumDistance = 7,
            ),
        )
    }

    @Test
    fun `does not propagate support downward from a scaffold above`() {
        val upperGround = position(1, 65, 0)
        val unsupportedBelow = position(0, 64, 0)
        val positions =
            setOf(
                unsupportedBelow,
                position(0, 65, 0),
                upperGround,
            )

        assertEquals(
            setOf(unsupportedBelow),
            ScaffoldingStabilityAnalyzer().unstablePositions(
                positions,
                groundedPositions = setOf(upperGround),
                maximumDistance = 7,
            ),
        )
    }

    private fun position(
        x: Int,
        y: Int,
        z: Int,
    ): ScaffoldingPosition = ScaffoldingPosition(x, y, z)
}
