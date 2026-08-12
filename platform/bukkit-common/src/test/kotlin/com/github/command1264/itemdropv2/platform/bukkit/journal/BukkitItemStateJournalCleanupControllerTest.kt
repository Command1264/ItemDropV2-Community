package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.lang.reflect.Proxy
import java.nio.file.Path

class BukkitItemStateJournalCleanupControllerTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `deletes only after two loaded-chunk absence confirmations`() {
        val record = sampleJournalUpsert(3)
        val runtime =
            assertInstanceOf(
                ItemStateJournalRuntimeOpenResult.Opened::class.java,
                ItemStateJournalRuntime.open(directory, setOf(record.identity.worldUuid)),
            ).runtime
        assertEquals(ItemStateDurabilityOutcome.Accepted(false), runtime.stage(record))
        val world = world(record)
        val controller = BukkitItemStateJournalCleanupController(runtime)
        controller.observe(item(record, world))

        assertEquals(0, controller.process(server(world)).removed)
        assertEquals(1, controller.process(server(world)).removed)
        runtime.shutdown()

        val reopened =
            assertInstanceOf(
                ItemStateJournalRuntimeOpenResult.Opened::class.java,
                ItemStateJournalRuntime.open(directory, setOf(record.identity.worldUuid)),
            ).runtime
        assertEquals(null, reopened[record.identity])
        reopened.shutdown()
    }

    private fun item(
        record: com.github.command1264.itemdropv2.core.ItemStateJournalRecord,
        world: World,
    ): Item =
        proxy(Item::class.java) { method ->
            when (method.name) {
                "getUniqueId" -> record.identity.entityUuid
                "getWorld" -> world
                "getLocation" -> Location(world, record.chunk.x * 16.0, 64.0, record.chunk.z * 16.0)
                else -> primitiveDefault(method.returnType)
            }
        }

    private fun world(record: com.github.command1264.itemdropv2.core.ItemStateJournalRecord): World {
        lateinit var world: World
        val chunk =
            proxy(Chunk::class.java) { method ->
                when (method.name) {
                    "getEntities" -> emptyArray<Entity>()
                    "getWorld" -> world
                    else -> primitiveDefault(method.returnType)
                }
            }
        world =
            proxy(World::class.java) { method ->
                when (method.name) {
                    "getUID" -> record.identity.worldUuid
                    "getLoadedChunks" -> arrayOf(chunk)
                    "isChunkLoaded" -> true
                    else -> primitiveDefault(method.returnType)
                }
            }
        return world
    }

    private fun server(world: World): Server =
        proxy(Server::class.java) { method ->
            when (method.name) {
                "getWorlds" -> listOf(world)
                "getWorld" -> world
                else -> primitiveDefault(method.returnType)
            }
        }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(
        type: Class<T>,
        answer: (java.lang.reflect.Method) -> Any?,
    ): T = Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> answer(method) } as T

    private fun primitiveDefault(type: Class<*>): Any? =
        when (type) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> '\u0000'
            else -> null
        }
}
