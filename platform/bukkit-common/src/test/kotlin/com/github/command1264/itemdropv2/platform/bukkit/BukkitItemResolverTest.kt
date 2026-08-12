package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.Chunk
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Item
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitItemResolverTest {
    @Test
    fun `loaded chunk fallback finds item when old server direct index and world entities miss it`() {
        val expected = item(ITEM_ID)
        val chunk = proxy<Chunk> { method -> if (method.name == "getEntities") arrayOf(expected) else defaultValue(method.returnType) }
        val world =
            proxy<World> { method ->
                when (method.name) {
                    "getLoadedChunks" -> arrayOf(chunk)
                    "getEntities" -> emptyList<org.bukkit.entity.Entity>()
                    else -> defaultValue(method.returnType)
                }
            }
        val server =
            proxy<Server> { method ->
                when (method.name) {
                    "getEntity" -> null
                    "getWorlds" -> listOf(world)
                    else -> defaultValue(method.returnType)
                }
            }

        assertSame(expected, resolveServerItem(server, ITEM_ID))
    }

    private fun item(id: UUID): Item = proxy { method -> if (method.name == "getUniqueId") id else defaultValue(method.returnType) }

    private inline fun <reified T> proxy(crossinline answer: (Method) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> answer(method) } as T

    private companion object {
        private val ITEM_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

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
