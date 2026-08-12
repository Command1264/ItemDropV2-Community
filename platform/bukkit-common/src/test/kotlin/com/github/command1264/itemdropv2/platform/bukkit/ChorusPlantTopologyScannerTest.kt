package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class ChorusPlantTopologyScannerTest {
    @Test
    fun `scans every connected plant and flower branch without including adjacent gaps`() {
        val blocks =
            mapOf(
                position(10, 64, 20) to Material.CHORUS_PLANT,
                position(10, 65, 20) to Material.CHORUS_PLANT,
                position(11, 65, 20) to Material.CHORUS_PLANT,
                position(12, 65, 20) to Material.CHORUS_FLOWER,
                position(14, 65, 20) to Material.CHORUS_PLANT,
            )

        val result = ChorusPlantTopologyScanner().scan(block(position(10, 64, 20), blocks))

        assertEquals(
            setOf(
                position(10, 64, 20),
                position(10, 65, 20),
                position(11, 65, 20),
                position(12, 65, 20),
            ),
            (result as ChorusTopologyScanResult.Complete).positions,
        )
    }

    @Test
    fun `uses the chorus block above a broken support as the topology seed`() {
        val blocks =
            mapOf(
                position(10, 63, 20) to Material.END_STONE,
                position(10, 64, 20) to Material.CHORUS_PLANT,
                position(10, 65, 20) to Material.CHORUS_FLOWER,
            )

        val result = ChorusPlantTopologyScanner().scan(block(position(10, 63, 20), blocks))

        assertEquals(
            setOf(position(10, 64, 20), position(10, 65, 20)),
            (result as ChorusTopologyScanResult.Complete).positions,
        )
    }

    @Test
    fun `returns empty when a player break cannot destabilize chorus blocks`() {
        val blocks = mapOf(position(10, 64, 20) to Material.STONE)

        assertEquals(
            ChorusTopologyScanResult.Empty,
            ChorusPlantTopologyScanner().scan(block(position(10, 64, 20), blocks)),
        )
    }

    @Test
    fun `does not snapshot the whole plant when a player directly breaks a flower`() {
        val blocks =
            mapOf(
                position(10, 64, 20) to Material.CHORUS_PLANT,
                position(10, 65, 20) to Material.CHORUS_FLOWER,
            )

        assertEquals(
            ChorusTopologyScanResult.Empty,
            ChorusPlantTopologyScanner().scan(block(position(10, 65, 20), blocks)),
        )
    }

    @Test
    fun `refuses a topology that exceeds the main thread scan limit`() {
        val blocks =
            (10..13).associate { x ->
                position(x, 64, 20) to Material.CHORUS_PLANT
            }

        val result =
            ChorusPlantTopologyScanner(maxBlocks = 3)
                .scan(block(position(10, 64, 20), blocks))

        assertTrue(result is ChorusTopologyScanResult.Truncated)
    }

    @Test
    fun `does not inspect chorus blocks across an unloaded chunk boundary`() {
        val blocks =
            mapOf(
                position(15, 64, 20) to Material.CHORUS_PLANT,
                position(16, 64, 20) to Material.CHORUS_PLANT,
            )

        val result =
            ChorusPlantTopologyScanner()
                .scan(
                    block(
                        position(15, 64, 20),
                        blocks,
                        loadedChunks = setOf(0 to 1),
                    ),
                )

        assertEquals(
            setOf(position(15, 64, 20)),
            (result as ChorusTopologyScanResult.Complete).positions,
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun block(
        position: ChorusBlockPosition,
        blocks: Map<ChorusBlockPosition, Material>,
        loadedChunks: Set<Pair<Int, Int>>? = null,
    ): Block {
        lateinit var world: World
        world =
            proxy { method, args ->
                when (method.name) {
                    "getUID" -> WORLD_ID
                    "getMaxHeight" -> 320
                    "isChunkLoaded" -> {
                        val chunk = (args?.get(0) as Int) to (args[1] as Int)
                        loadedChunks == null || chunk in loadedChunks
                    }
                    else -> defaultValue(method.returnType)
                }
            }

        fun at(current: ChorusBlockPosition): Block =
            proxy { method, args ->
                when (method.name) {
                    "getType" -> blocks[current] ?: Material.AIR
                    "getX" -> current.x
                    "getY" -> current.y
                    "getZ" -> current.z
                    "getWorld" -> world
                    "getRelative" ->
                        at(
                            position(
                                current.x + (args?.get(0) as Int),
                                current.y + (args[1] as Int),
                                current.z + (args[2] as Int),
                            ),
                        )
                    else -> defaultValue(method.returnType)
                }
            }
        return at(position)
    }

    private fun position(
        x: Int,
        y: Int,
        z: Int,
    ): ChorusBlockPosition = ChorusBlockPosition(x, y, z)

    private inline fun <reified T> proxy(crossinline answer: (Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method, args)
        } as T

    private companion object {
        private val WORLD_ID = java.util.UUID.fromString("00000000-0000-0000-0000-000000000010")

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
