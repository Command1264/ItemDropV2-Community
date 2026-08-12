package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.data.type.Scaffolding
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

class FallingBlockSourceScannerTest {
    @Test
    fun `scans every contiguous gravity block above a broken support`() {
        val blocks =
            mapOf(
                Position(10, 65, 20) to Material.SAND,
                Position(10, 66, 20) to Material.GRAVEL,
                Position(10, 67, 20) to Material.STONE,
            )
        val scanner = FallingBlockSourceScanner()

        val sources = scanner.afterBreak(block(Position(10, 64, 20), blocks), OWNER_ID)

        assertEquals(
            listOf(
                Position(10, 65, 20) to Material.SAND,
                Position(10, 66, 20) to Material.GRAVEL,
            ),
            sources.map { Position(it.x, it.y, it.z) to Material.valueOf(it.materialName) },
        )
    }

    @Test
    fun `keeps direct scaffolding drops separate while assigning a shared network to the final support remover`() {
        val leftSupport = Position(10, 64, 20)
        val rightSupport = Position(12, 64, 20)
        val scaffolding =
            setOf(
                leftSupport,
                Position(10, 65, 20),
                Position(11, 65, 20),
                Position(12, 65, 20),
                rightSupport,
            )
        val blocks =
            scaffolding.associateWith { Material.SCAFFOLDING } +
                mapOf(
                    Position(10, 63, 20) to Material.STONE,
                    Position(12, 63, 20) to Material.STONE,
                )
        val scanner = FallingBlockSourceScanner()

        val firstSources = scanner.afterBreak(block(leftSupport, blocks), OWNER_ID)
        val afterFirstBreak = blocks + (leftSupport to Material.AIR)
        val secondSources = scanner.afterBreak(block(rightSupport, afterFirstBreak), SECOND_OWNER_ID)

        assertEquals(setOf(leftSupport), firstSources.map { Position(it.x, it.y, it.z) }.toSet())
        assertTrue(firstSources.all { it.ownerUuid == OWNER_ID })
        assertEquals(
            setOf(
                rightSupport,
                Position(10, 65, 20),
                Position(11, 65, 20),
                Position(12, 65, 20),
            ),
            secondSources.map { Position(it.x, it.y, it.z) }.toSet(),
        )
        assertTrue(secondSources.all { it.ownerUuid == SECOND_OWNER_ID })
    }

    @Test
    fun `tracks only the region destabilized by a removed support`() {
        val support = Position(10, 63, 20)
        val scaffolding =
            buildSet {
                add(Position(10, 64, 20))
                add(Position(10, 65, 20))
                (11..17).forEach { x -> add(Position(x, 65, 20)) }
            }
        val blocks = scaffolding.associateWith { Material.SCAFFOLDING } + (support to Material.STONE)

        val sources = FallingBlockSourceScanner().afterBreak(block(support, blocks), OWNER_ID)

        assertEquals(scaffolding, sources.map { Position(it.x, it.y, it.z) }.toSet())
        assertTrue(sources.all { it.materialName == Material.SCAFFOLDING.name })
    }

    @Test
    fun `ignores scaffolding beside an unrelated broken block`() {
        val broken = Position(10, 64, 20)
        val beside = Position(11, 64, 20)
        val blocks =
            mapOf(
                broken to Material.STONE,
                beside to Material.SCAFFOLDING,
            )

        val sources = FallingBlockSourceScanner().afterBreak(block(broken, blocks), OWNER_ID)

        assertTrue(sources.isEmpty())
    }

    @Test
    fun `fails closed instead of attributing a partial scaffolding topology`() {
        val support = Position(10, 63, 20)
        val scaffolding =
            setOf(
                Position(10, 64, 20),
                Position(10, 65, 20),
                Position(11, 65, 20),
            )
        val blocks = scaffolding.associateWith { Material.SCAFFOLDING } + (support to Material.STONE)

        val sources =
            FallingBlockSourceScanner(maxScaffoldingPositions = 2)
                .afterBreak(block(support, blocks), OWNER_ID)

        assertTrue(sources.isEmpty())
    }

    @Test
    fun `records only gravity or scaffolding player placements`() {
        val scanner = FallingBlockSourceScanner()

        assertEquals(1, scanner.afterPlace(block(Position(10, 64, 20), material = Material.SAND), OWNER_ID).size)
        assertEquals(1, scanner.afterPlace(block(Position(11, 64, 20), material = Material.SCAFFOLDING), OWNER_ID).size)
        assertTrue(scanner.afterPlace(block(Position(12, 64, 20), material = Material.STONE), OWNER_ID).isEmpty())
    }

    @Suppress("CyclomaticComplexMethod")
    private fun block(
        position: Position,
        blocks: Map<Position, Material> = emptyMap(),
        material: Material = blocks[position] ?: Material.AIR,
    ): Block {
        lateinit var world: World
        world =
            proxy { method, args ->
                when (method.name) {
                    "getUID" -> WORLD_ID
                    "getName" -> "world"
                    "getMaxHeight" -> 384
                    "isChunkLoaded" -> true
                    "getBlockAt" ->
                        block(
                            Position(args!![0] as Int, args[1] as Int, args[2] as Int),
                            blocks,
                        )
                    else -> defaultValue(method.returnType)
                }
            }
        return proxy { method, args ->
            when (method.name) {
                "getType" -> material
                "getX" -> position.x
                "getY" -> position.y
                "getZ" -> position.z
                "getWorld" -> world
                "getBlockData" ->
                    if (material == Material.SCAFFOLDING) {
                        scaffoldingData()
                    } else {
                        null
                    }
                "getRelative" ->
                    block(
                        Position(
                            position.x + (args!![0] as Int),
                            position.y + (args[1] as Int),
                            position.z + (args[2] as Int),
                        ),
                        blocks,
                    )
                else -> defaultValue(method.returnType)
            }
        }
    }

    private inline fun <reified T> proxy(crossinline answer: (Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method, args)
        } as T

    private fun scaffoldingData(): Scaffolding =
        proxy { method, _ ->
            when (method.name) {
                "getDistance" -> 0
                "getMaximumDistance" -> 7
                "getMaterial" -> Material.SCAFFOLDING
                else -> defaultValue(method.returnType)
            }
        }

    private data class Position(
        val x: Int,
        val y: Int,
        val z: Int,
    )

    private companion object {
        private val WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020")
        private val SECOND_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000030")

        private fun defaultValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Byte.TYPE -> 0.toByte()
                java.lang.Short.TYPE -> 0.toShort()
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Float.TYPE -> 0F
                java.lang.Double.TYPE -> 0.0
                java.lang.Character.TYPE -> '\u0000'
                else -> null
            }
    }
}
