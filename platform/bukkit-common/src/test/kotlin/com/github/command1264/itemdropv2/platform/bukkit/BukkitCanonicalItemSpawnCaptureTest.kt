package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.World
import org.bukkit.entity.Item
import org.bukkit.event.entity.ItemSpawnEvent
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitCanonicalItemSpawnCaptureTest {
    @Test
    fun `prepares the synchronous event item before the spawn operation returns`() {
        val entityId = UUID.fromString("00000000-0000-0000-0000-000000000040")
        val returnedWrapper = item(entityId)
        val canonicalEventItem = item(entityId)
        val capture = BukkitCanonicalItemSpawnCapture(primaryThreadCheck = { true })
        var preparedBeforeReturn = false

        val result =
            capture.capture(
                operation = {
                    capture.onItemSpawn(ItemSpawnEvent(canonicalEventItem))
                    assertTrue(preparedBeforeReturn)
                    returnedWrapper
                },
                beforePublication = { item ->
                    assertSame(canonicalEventItem, item)
                    preparedBeforeReturn = true
                },
            )

        assertSame(canonicalEventItem, result)
    }

    @Test
    fun `returns the synchronous event item instead of the legacy api wrapper`() {
        val entityId = UUID.fromString("00000000-0000-0000-0000-000000000041")
        val returnedWrapper = item(entityId)
        val canonicalEventItem = item(entityId)
        val capture = BukkitCanonicalItemSpawnCapture(primaryThreadCheck = { true })

        val result =
            capture.capture {
                capture.onItemSpawn(ItemSpawnEvent(canonicalEventItem))
                returnedWrapper
            }

        assertSame(canonicalEventItem, result)
    }

    @Test
    fun `resolves the published world item when events are unavailable during disable`() {
        val entityId = UUID.fromString("00000000-0000-0000-0000-000000000042")
        lateinit var canonicalWorldItem: Item
        val world =
            Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { _, method, _ ->
                when (method.name) {
                    "getEntities" -> listOf(canonicalWorldItem)
                    else -> defaultValue(method.returnType)
                }
            } as World
        val returnedWrapper = item(entityId, world)
        canonicalWorldItem = item(entityId, world)
        val capture = BukkitCanonicalItemSpawnCapture(primaryThreadCheck = { true })

        val result = capture.capture { returnedWrapper }

        assertSame(canonicalWorldItem, result)
    }

    @Test
    fun `removes the returned entity when no canonical target is published`() {
        val world =
            Proxy.newProxyInstance(World::class.java.classLoader, arrayOf(World::class.java)) { _, method, _ ->
                when (method.name) {
                    "getEntities" -> emptyList<Item>()
                    else -> defaultValue(method.returnType)
                }
            } as World
        var removed = false
        val returnedWrapper =
            item(
                UUID.fromString("00000000-0000-0000-0000-000000000043"),
                world,
                onRemove = { removed = true },
            )
        val capture = BukkitCanonicalItemSpawnCapture(primaryThreadCheck = { true })

        assertThrows(IllegalStateException::class.java) { capture.capture { returnedWrapper } }

        assertTrue(removed)
    }

    private fun item(
        entityId: UUID,
        world: World? = null,
        onRemove: () -> Unit = {},
    ): Item =
        Proxy.newProxyInstance(Item::class.java.classLoader, arrayOf(Item::class.java)) { _, method, _ ->
            when (method.name) {
                "getUniqueId" -> entityId
                "getWorld" -> world
                "remove" -> onRemove()
                else -> defaultValue(method.returnType)
            }
        } as Item

    private companion object {
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
