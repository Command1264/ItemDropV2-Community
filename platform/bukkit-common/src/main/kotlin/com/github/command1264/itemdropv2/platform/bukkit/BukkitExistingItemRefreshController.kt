package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemDisplayRequest
import com.github.command1264.itemdropv2.core.ItemDisplayService
import com.github.command1264.itemdropv2.core.ItemNameService
import com.github.command1264.itemdropv2.core.MinecraftItemRarity
import org.bukkit.Server
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.ChunkLoadEvent
import java.util.UUID

internal data class LoadedChunkReference(
    val worldName: String,
    val x: Int,
    val z: Int,
) {
    init {
        require(worldName.isNotBlank()) { "loaded chunk world name must not be blank" }
        require(worldName.none(Char::isISOControl)) { "loaded chunk world name must not contain control characters" }
    }
}

internal fun interface LoadedChunkItemResolver {
    fun resolve(chunk: LoadedChunkReference): List<UUID>
}

internal class ExistingItemRefreshCoordinator(
    service: ItemDisplayService,
    private val taskExecutor: MainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val chunkItemResolver: LoadedChunkItemResolver,
    private val requestResolver: ItemDisplayRequestResolver,
    private val maxChunksPerTick: Int = DEFAULT_CHUNKS_PER_TICK,
    private val maxItemsPerTick: Int = DEFAULT_ITEMS_PER_TICK,
) : AutoCloseable {
    private val processor = ItemDisplayProcessor(service, warningSink)
    private val pendingChunks = linkedSetOf<LoadedChunkReference>()
    private val pendingItemIds = linkedSetOf<UUID>()
    private var active = true
    private var taskScheduled = false

    init {
        require(maxChunksPerTick > 0) { "maxChunksPerTick must be positive" }
        require(maxItemsPerTick > 0) { "maxItemsPerTick must be positive" }
    }

    fun requestChunks(chunks: Iterable<LoadedChunkReference>) {
        if (!active) return
        pendingChunks.addAll(chunks)
        scheduleIfRequired()
    }

    private fun runBatch() {
        taskScheduled = false
        if (!active) return

        repeat(maxChunksPerTick) {
            val chunk = pendingChunks.removeFirstOrNull() ?: return@repeat
            resolveChunk(chunk)
        }
        repeat(maxItemsPerTick) {
            val entityId = pendingItemIds.removeFirstOrNull() ?: return@repeat
            refreshItem(entityId)
        }
        scheduleIfRequired()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun resolveChunk(chunk: LoadedChunkReference) {
        try {
            pendingItemIds.addAll(chunkItemResolver.resolve(chunk))
        } catch (error: RuntimeException) {
            warningSink.warn("existing item chunk refresh failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun refreshItem(entityId: UUID) {
        val request =
            try {
                requestResolver.resolve(entityId)
            } catch (error: RuntimeException) {
                warningSink.warn("existing item refresh failed (${error.javaClass.simpleName})")
                null
            }
        request?.let(processor::display)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleIfRequired() {
        if (!active || taskScheduled || !hasPendingWork()) return
        taskScheduled = true
        try {
            taskExecutor.execute(::runBatch)
        } catch (error: RuntimeException) {
            taskScheduled = false
            warningSink.warn("existing item refresh scheduling failed (${error.javaClass.simpleName})")
        }
    }

    private fun hasPendingWork(): Boolean = pendingChunks.isNotEmpty() || pendingItemIds.isNotEmpty()

    override fun close() {
        active = false
        pendingChunks.clear()
        pendingItemIds.clear()
    }

    private companion object {
        private const val DEFAULT_CHUNKS_PER_TICK = 8
        private const val DEFAULT_ITEMS_PER_TICK = 100
    }
}

public class BukkitExistingItemRefreshController(
    private val server: Server,
    service: ItemDisplayService,
    taskExecutor: MainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    itemNameService: ItemNameService = fallbackItemNameService,
    translationKeyResolver: BukkitItemTranslationKeyResolver = BukkitItemTranslationKeyResolver(),
    displayStateResolver: ItemDisplayStateResolver = ItemDisplayStateResolver { ItemDisplayStateSnapshot() },
    rarityResolver: BukkitItemRarityResolver = BukkitItemRarityResolver { MinecraftItemRarity.COMMON },
) : Listener,
    AutoCloseable {
    private val coordinator =
        ExistingItemRefreshCoordinator(
            service = service,
            taskExecutor = taskExecutor,
            warningSink = warningSink,
            chunkItemResolver =
                LoadedChunkItemResolver { chunk ->
                    val world = server.getWorld(chunk.worldName)
                    if (world == null || !world.isChunkLoaded(chunk.x, chunk.z)) {
                        emptyList()
                    } else {
                        world
                            .getChunkAt(chunk.x, chunk.z)
                            .entities
                            .asSequence()
                            .filterIsInstance<Item>()
                            .map(Item::getUniqueId)
                            .toList()
                    }
                },
            requestResolver =
                ItemDisplayRequestResolver { entityId ->
                    resolveServerItem(server, entityId)?.let { item ->
                        createItemDisplayRequest(
                            item,
                            warningSink,
                            itemNameService,
                            translationKeyResolver,
                            displayStateResolver,
                            rarityResolver,
                        )
                    }
                },
        )

    @EventHandler
    public fun onChunkLoad(event: ChunkLoadEvent) {
        if (event.isAsynchronous) {
            warningSink.warn("ignored asynchronous ChunkLoadEvent")
            return
        }
        coordinator.requestChunks(listOf(event.chunk.toReference()))
    }

    @Suppress("TooGenericExceptionCaught")
    public fun refreshLoadedChunks() {
        try {
            coordinator.requestChunks(
                server.worlds.flatMap { world -> world.loadedChunks.map { chunk -> chunk.toReference() } },
            )
        } catch (error: RuntimeException) {
            warningSink.warn("loaded chunk discovery failed (${error.javaClass.simpleName})")
        }
    }

    override fun close() {
        coordinator.close()
    }
}

private fun org.bukkit.Chunk.toReference(): LoadedChunkReference = LoadedChunkReference(world.name, x, z)

private fun <T> MutableSet<T>.removeFirstOrNull(): T? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    return iterator.next().also { iterator.remove() }
}
