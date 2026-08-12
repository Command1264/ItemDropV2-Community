package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.World
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class BukkitWorldMinimumHeightAccessTest {
    @Test
    fun `uses zero when the legacy world class has no minimum height API`() {
        val world = proxyWorld(arrayOf(World::class.java)) { null }

        assertEquals(0, BukkitWorldMinimumHeightAccess.minimumHeight(world))
    }

    @Test
    fun `reads minimum height from a modern runtime world class`() {
        val world =
            proxyWorld(arrayOf(World::class.java, ModernWorld::class.java)) { methodName ->
                if (methodName == "getMinHeight") -64 else null
            }

        assertEquals(-64, BukkitWorldMinimumHeightAccess.minimumHeight(world))
        assertEquals(-64, BukkitWorldMinimumHeightAccess.minimumHeight(world))
    }

    private fun proxyWorld(
        interfaces: Array<Class<*>>,
        answer: (String) -> Any?,
    ): World =
        Proxy.newProxyInstance(
            World::class.java.classLoader,
            interfaces,
        ) { proxy, method, arguments ->
            answer(method.name)
                ?: when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    "toString" -> "WorldProxy"
                    else -> defaultValue(method.returnType)
                }
        } as World

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

    private interface ModernWorld {
        fun getMinHeight(): Int
    }
}
