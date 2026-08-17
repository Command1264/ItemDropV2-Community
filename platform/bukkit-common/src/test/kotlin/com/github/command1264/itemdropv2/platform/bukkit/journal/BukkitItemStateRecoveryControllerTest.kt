package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateJournalFingerprint
import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.PresentationJournalSnapshotResult
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemStateRepository
import com.github.command1264.itemdropv2.platform.bukkit.BukkitItemStateRepositoryTest.FakePersistentDataContainer
import com.github.command1264.itemdropv2.platform.bukkit.MainThreadTaskExecutor
import org.bukkit.Chunk
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.event.world.ChunkLoadEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.UUID

class BukkitItemStateRecoveryControllerTest {
    @Test
    fun `discovers a chunk that becomes loaded after activation recovery`() {
        val container = FakePersistentDataContainer()
        val item = item(container)
        val record = sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)
        val repository = repository()
        val scheduledTasks = ArrayDeque<() -> Unit>()
        val delayedChunk = chunk(entities = { arrayOf(item) })
        var loadedChunks = emptyArray<Chunk>()
        val controller =
            BukkitItemStateRecoveryController(
                repository = repository,
                runtime = FakeRuntime(record),
                entityPort = FakeEntityPort(record.fingerprint),
                taskExecutor = MainThreadTaskExecutor(scheduledTasks::addLast),
            )

        controller.scheduleLoadedChunkDiscoveryRetries(server { loadedChunks })
        loadedChunks = arrayOf(delayedChunk)
        scheduledTasks.removeFirst().invoke()
        scheduledTasks.removeFirst().invoke()

        val lease = repository.leaseTransientTarget(item)
        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))
        lease.release()
        assertEquals(record.state, loaded.state)
        assertEquals(4L, loaded.revision)
    }

    @Test
    fun `recovers an item published after the initial loaded chunk scan`() {
        val container = FakePersistentDataContainer()
        val item = item(container)
        val record = sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)
        val repository = repository()
        val scheduledTasks = ArrayDeque<() -> Unit>()
        var visibleEntities = emptyArray<Entity>()
        val chunk = chunk(entities = { visibleEntities })
        val controller =
            BukkitItemStateRecoveryController(
                repository = repository,
                runtime = FakeRuntime(record),
                entityPort = FakeEntityPort(record.fingerprint),
                taskExecutor = MainThreadTaskExecutor(scheduledTasks::addLast),
            )

        assertEquals(ItemStateRecoveryBatchResult(0, 0, emptyList()), controller.recoverLoadedItems(server(chunk)))
        visibleEntities = arrayOf(item)
        scheduledTasks.removeFirst().invoke()

        val lease = repository.leaseTransientTarget(item)
        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))
        lease.release()
        assertEquals(record.state, loaded.state)
        assertEquals(4L, loaded.revision)
    }

    @Test
    fun `refreshes a replaced chunk wrapper without extending the startup deadline`() {
        val early = item(FakePersistentDataContainer())
        val canonical = item(FakePersistentDataContainer())
        val record = sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)
        val repository = repository()
        val scheduledTasks = ArrayDeque<() -> Unit>()
        val initialChunk = chunk(entities = { arrayOf(early) })
        val canonicalChunk = chunk(entities = { arrayOf(canonical) })
        val controller =
            BukkitItemStateRecoveryController(
                repository = repository,
                runtime = FakeRuntime(record),
                entityPort = FakeEntityPort(record.fingerprint),
                taskExecutor = MainThreadTaskExecutor(scheduledTasks::addLast),
            )

        controller.recoverLoadedItems(server(initialChunk))
        controller.scheduleLoadedChunkDiscoveryRetries(server(canonicalChunk))
        scheduledTasks.removeFirst().invoke()

        val lease = repository.leaseTransientTarget(canonical)
        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))
        lease.release()
        assertEquals(record.state, loaded.state)
        assertEquals(4L, loaded.revision)
    }

    @Test
    fun `deduplicates chunk load retries into one coordinator task`() {
        val scheduledTasks = ArrayDeque<() -> Unit>()
        val chunk = chunk(entities = { emptyArray() })
        val controller =
            BukkitItemStateRecoveryController(
                repository = repository(),
                runtime = FakeRuntime(sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)),
                entityPort = FakeEntityPort(sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID).fingerprint),
                taskExecutor = MainThreadTaskExecutor(scheduledTasks::addLast),
            )

        repeat(1_000) { controller.onChunkLoad(ChunkLoadEvent(chunk, true)) }

        assertEquals(1, scheduledTasks.size)
        scheduledTasks.removeFirst().invoke()
        assertEquals(1, scheduledTasks.size)
    }

    @Test
    fun `limits startup entity scans per coordinator tick`() {
        val scheduledTasks = ArrayDeque<() -> Unit>()
        var entityScans = 0
        var snapshotReads = 0
        val chunks =
            Array(129) { index ->
                chunk(x = index, entities = {
                    entityScans++
                    emptyArray()
                })
            }
        val record = sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)
        val controller =
            BukkitItemStateRecoveryController(
                repository = repository(),
                runtime = FakeRuntime(record),
                entityPort = FakeEntityPort(record.fingerprint),
                taskExecutor = MainThreadTaskExecutor(scheduledTasks::addLast),
            )

        controller.scheduleLoadedChunkDiscoveryRetries(
            server {
                snapshotReads++
                chunks
            },
        )
        scheduledTasks.removeFirst().invoke()

        assertEquals(64, entityScans)
        assertEquals(1, snapshotReads)

        scheduledTasks.removeFirst().invoke()

        assertEquals(128, entityScans)
        assertEquals(1, snapshotReads)
    }

    @Test
    fun `fails closed before retaining an oversized loaded chunk snapshot`() {
        val scheduledTasks = ArrayDeque<() -> Unit>()
        val record = sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)
        val runtime = FakeRuntime(record)
        val repeatedChunk = chunk(entities = { emptyArray() })
        val controller =
            BukkitItemStateRecoveryController(
                repository = repository(),
                runtime = runtime,
                entityPort = FakeEntityPort(record.fingerprint),
                taskExecutor = MainThreadTaskExecutor(scheduledTasks::addLast),
            )

        controller.scheduleLoadedChunkDiscoveryRetries(server { Array(10_001) { repeatedChunk } })
        scheduledTasks.removeFirst().invoke()

        assertEquals(listOf("ChunkRecoveryCapacityExceeded:10000"), runtime.degradedReasons)
        assertEquals(0, scheduledTasks.size)
    }

    @Test
    fun `fails closed when active chunk recovery reaches its capacity`() {
        val scheduledTasks = ArrayDeque<() -> Unit>()
        val record = sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)
        val runtime = FakeRuntime(record)
        val controller =
            BukkitItemStateRecoveryController(
                repository = repository(),
                runtime = runtime,
                entityPort = FakeEntityPort(record.fingerprint),
                taskExecutor = MainThreadTaskExecutor(scheduledTasks::addLast),
            )

        repeat(10_001) { index ->
            controller.onChunkLoad(ChunkLoadEvent(chunk(x = index, entities = { emptyArray() }), true))
        }

        assertEquals(1, scheduledTasks.size)
        assertEquals(listOf("ChunkRecoveryCapacityExceeded:10000"), runtime.degradedReasons)
    }

    @Test
    fun `late startup discovery shares the original absolute deadline`() {
        val container = FakePersistentDataContainer()
        val item = item(container)
        val record = sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)
        val repository = repository()
        val scheduledTasks = ArrayDeque<() -> Unit>()
        var visibleEntities = emptyArray<Entity>()
        val delayedChunk = chunk(entities = { visibleEntities })
        var loadedChunks = emptyArray<Chunk>()
        val controller =
            BukkitItemStateRecoveryController(
                repository = repository,
                runtime = FakeRuntime(record),
                entityPort = FakeEntityPort(record.fingerprint),
                taskExecutor = MainThreadTaskExecutor(scheduledTasks::addLast),
            )

        controller.scheduleLoadedChunkDiscoveryRetries(server { loadedChunks })
        repeat(1_199) { scheduledTasks.removeFirst().invoke() }
        loadedChunks = arrayOf(delayedChunk)
        scheduledTasks.removeFirst().invoke()
        visibleEntities = arrayOf(item)
        scheduledTasks.removeFirst().invoke()

        val lease = repository.leaseTransientTarget(item)
        assertEquals(ItemStateLoadResult.Absent, repository.load(ENTITY_UUID))
        lease.release()
    }

    @Test
    fun `retries recovery when an old server replaces an early item wrapper`() {
        val early = item(FakePersistentDataContainer())
        val canonical = item(FakePersistentDataContainer())
        val record = sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)
        val repository = repository()
        val scheduledTasks = ArrayDeque<() -> Unit>()
        var visibleEntities = arrayOf<Entity>(early)
        val chunk = chunk(entities = { visibleEntities })
        val controller =
            BukkitItemStateRecoveryController(
                repository = repository,
                runtime = FakeRuntime(record),
                entityPort = FakeEntityPort(record.fingerprint),
                taskExecutor = MainThreadTaskExecutor(scheduledTasks::addLast),
            )

        controller.recoverLoadedItems(server(chunk))
        visibleEntities = arrayOf(canonical)
        scheduledTasks.removeFirst().invoke()

        val lease = repository.leaseTransientTarget(canonical)
        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))
        lease.release()
        assertEquals(record.state, loaded.state)
        assertEquals(4L, loaded.revision)
    }

    @Test
    fun `revalidates durable state after delayed chunk hydration replaces pdc`() {
        val container = FakePersistentDataContainer()
        val item = item(container)
        val record = sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)
        val repository = repository()
        val scheduledTasks = ArrayDeque<() -> Unit>()
        val controller =
            BukkitItemStateRecoveryController(
                repository = repository,
                runtime = FakeRuntime(record),
                entityPort = FakeEntityPort(record.fingerprint),
                taskExecutor = MainThreadTaskExecutor(scheduledTasks::addLast),
            )

        controller.recoverLoadedItems(server(chunk(entities = { arrayOf(item) })))
        scheduledTasks.removeFirst().invoke()
        container.clear()
        repeat(199) { scheduledTasks.removeFirst().invoke() }

        val missingLease = repository.leaseTransientTarget(item)
        assertEquals(ItemStateLoadResult.Absent, repository.load(ENTITY_UUID))
        missingLease.release()

        scheduledTasks.removeFirst().invoke()

        val restoredLease = repository.leaseTransientTarget(item)
        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))
        restoredLease.release()
        assertEquals(record.state, loaded.state)
        assertEquals(4L, loaded.revision)
    }

    @Test
    fun `recovers an item published after the chunk load event`() {
        val container = FakePersistentDataContainer()
        val item = item(container)
        val record = sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)
        val repository = repository()
        val scheduledTasks = ArrayDeque<() -> Unit>()
        var visibleEntities = emptyArray<Entity>()
        val chunk = chunk(entities = { visibleEntities })
        val controller =
            BukkitItemStateRecoveryController(
                repository = repository,
                runtime = FakeRuntime(record),
                entityPort = FakeEntityPort(record.fingerprint),
                taskExecutor = MainThreadTaskExecutor(scheduledTasks::addLast),
            )

        controller.onChunkLoad(ChunkLoadEvent(chunk, true))
        visibleEntities = arrayOf(item)
        scheduledTasks.removeFirst().invoke()

        val lease = repository.leaseTransientTarget(item)
        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))
        lease.release()
        assertEquals(record.state, loaded.state)
        assertEquals(4L, loaded.revision)
    }

    @Test
    fun `retains bounded recovery while a chunk wrapper is temporarily unloaded`() {
        val container = FakePersistentDataContainer()
        val item = item(container)
        val record = sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)
        val repository = repository()
        val scheduledTasks = ArrayDeque<() -> Unit>()
        var visibleEntities = emptyArray<Entity>()
        var isLoaded = false
        val chunk = chunk(entities = { visibleEntities }, loaded = { isLoaded })
        val controller =
            BukkitItemStateRecoveryController(
                repository = repository,
                runtime = FakeRuntime(record),
                entityPort = FakeEntityPort(record.fingerprint),
                taskExecutor = MainThreadTaskExecutor(scheduledTasks::addLast),
            )

        controller.onChunkLoad(ChunkLoadEvent(chunk, true))
        scheduledTasks.removeFirst().invoke()
        visibleEntities = arrayOf(item)
        isLoaded = true
        scheduledTasks.removeFirst().invoke()

        val lease = repository.leaseTransientTarget(item)
        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))
        lease.release()
        assertEquals(record.state, loaded.state)
        assertEquals(4L, loaded.revision)
    }

    @Test
    fun `continues recovery retry when another item was already visible at chunk load`() {
        val container = FakePersistentDataContainer()
        val delayed = item(container)
        val alreadyVisible = item(FakePersistentDataContainer(), OTHER_ENTITY_UUID)
        val record = sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)
        val repository = repository()
        val scheduledTasks = ArrayDeque<() -> Unit>()
        var visibleEntities = arrayOf<Entity>(alreadyVisible)
        val chunk = chunk(entities = { visibleEntities })
        val controller =
            BukkitItemStateRecoveryController(
                repository = repository,
                runtime = FakeRuntime(record),
                entityPort = FakeEntityPort(record.fingerprint),
                taskExecutor = MainThreadTaskExecutor(scheduledTasks::addLast),
            )

        controller.onChunkLoad(ChunkLoadEvent(chunk, true))
        visibleEntities = arrayOf(alreadyVisible, delayed)
        scheduledTasks.removeFirst().invoke()

        val lease = repository.leaseTransientTarget(delayed)
        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))
        lease.release()
        assertEquals(record.state, loaded.state)
        assertEquals(4L, loaded.revision)
    }

    @Test
    fun `restores journal state at its exact revision before applying presentation guards`() {
        val container = FakePersistentDataContainer()
        val item = item(container)
        val record = sampleJournalUpsert(revision = 4, entityUuid = ENTITY_UUID)
        val runtime = FakeRuntime(record)
        val port = FakeEntityPort(record.fingerprint)
        val repository = repository()

        assertEquals(
            ItemStateEntityRecoveryResult.Restored,
            BukkitItemStateRecoveryController(repository, runtime, port, NO_OP_TASK_EXECUTOR).recover(item),
        )

        val lease = repository.leaseTransientTarget(item)
        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))
        lease.release()
        assertEquals(record.state, loaded.state)
        assertEquals(4L, loaded.revision)
        assertEquals(record.presentation, port.restoredPresentation)
    }

    @Test
    fun `publishes a newer PDC revision and always releases the transient target`() {
        val container = FakePersistentDataContainer()
        val item = item(container)
        val repository = repository()
        val initialLease = repository.leaseTransientTarget(item)
        repository.saveRevisioned(ENTITY_UUID, ItemState(null, 1, 300))
        repository.saveRevisioned(ENTITY_UUID, ItemState(null, 2, 300))
        initialLease.release()
        val record = sampleJournalUpsert(revision = 1, entityUuid = ENTITY_UUID)
        val port = FakeEntityPort(record.fingerprint)

        assertEquals(
            ItemStateEntityRecoveryResult.Published,
            BukkitItemStateRecoveryController(repository, FakeRuntime(record), port, NO_OP_TASK_EXECUTOR).recover(item),
        )
        assertEquals(2L, port.publishedRevision)
        assertEquals(ItemStateLoadResult.MissingTarget, repository.load(ENTITY_UUID))
    }

    @Test
    fun `rejects a fingerprint mismatch without mutating PDC`() {
        val container = FakePersistentDataContainer()
        val item = item(container)
        val record = sampleJournalUpsert(revision = 2, entityUuid = ENTITY_UUID)
        val mismatched = ItemStateJournalFingerprint("minecraft:stone", "b".repeat(64))
        val repository = repository()

        assertEquals(
            ItemStateEntityRecoveryResult.Failed("FingerprintMismatch"),
            BukkitItemStateRecoveryController(
                repository,
                FakeRuntime(record),
                FakeEntityPort(mismatched),
                NO_OP_TASK_EXECUTOR,
            ).recover(item),
        )
        val lease = repository.leaseTransientTarget(item)
        assertEquals(ItemStateLoadResult.Absent, repository.load(ENTITY_UUID))
        lease.release()
    }

    @Test
    fun `does not mark state synchronized when presentation recovery is rejected`() {
        val container = FakePersistentDataContainer()
        val item = item(container)
        val record = sampleJournalUpsert(revision = 2, entityUuid = ENTITY_UUID)
        val repository = repository()
        val port = FakeEntityPort(record.fingerprint, presentationRejected = true)

        assertEquals(
            ItemStateEntityRecoveryResult.Failed("Presentation:ManagedNameMismatch"),
            BukkitItemStateRecoveryController(repository, FakeRuntime(record), port, NO_OP_TASK_EXECUTOR).recover(item),
        )
        val lease = repository.leaseTransientTarget(item)
        assertEquals(ItemStateLoadResult.Absent, repository.load(ENTITY_UUID))
        lease.release()
    }

    @Test
    fun `startup lifetime readiness fails closed and degrades on recovery conflict`() {
        val container = FakePersistentDataContainer()
        val item = item(container)
        val record = sampleJournalUpsert(revision = 2, entityUuid = ENTITY_UUID)
        val mismatched = ItemStateJournalFingerprint("minecraft:stone", "b".repeat(64))
        val runtime = FakeRuntime(record)
        val failures = mutableListOf<String>()
        val controller =
            BukkitItemStateRecoveryController(
                repository(),
                runtime,
                FakeEntityPort(mismatched),
                NO_OP_TASK_EXECUTOR,
                failureSink = failures::add,
            )

        assertEquals(false, controller.prepareForLifetimeRegistration(item))
        assertEquals(listOf("$ENTITY_UUID:FingerprintMismatch"), failures)
        assertEquals(listOf("$ENTITY_UUID:FingerprintMismatch"), runtime.degradedReasons)
    }

    private fun repository(): BukkitItemStateRepository =
        BukkitItemStateRepository(
            targetResolver = { null },
            primaryThreadCheck = { true },
        )

    private fun item(
        container: FakePersistentDataContainer,
        entityId: UUID = ENTITY_UUID,
    ): Item {
        val world =
            Proxy.newProxyInstance(
                World::class.java.classLoader,
                arrayOf(World::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getUID" -> WORLD_UUID
                    else -> primitiveDefault(method.returnType)
                }
            } as World
        return Proxy.newProxyInstance(
            Item::class.java.classLoader,
            arrayOf(Item::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getUniqueId" -> entityId
                "getPersistentDataContainer" -> container
                "getWorld" -> world
                else -> primitiveDefault(method.returnType)
            }
        } as Item
    }

    private fun chunk(
        x: Int = 0,
        z: Int = 0,
        entities: () -> Array<Entity>,
        loaded: () -> Boolean = { true },
    ): Chunk {
        val world =
            Proxy.newProxyInstance(
                World::class.java.classLoader,
                arrayOf(World::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getUID" -> WORLD_UUID
                    else -> primitiveDefault(method.returnType)
                }
            } as World
        return Proxy.newProxyInstance(
            Chunk::class.java.classLoader,
            arrayOf(Chunk::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getEntities" -> entities()
                "isLoaded" -> loaded()
                "getWorld" -> world
                "getX" -> x
                "getZ" -> z
                else -> primitiveDefault(method.returnType)
            }
        } as Chunk
    }

    private fun server(chunk: Chunk): Server = server { arrayOf(chunk) }

    private fun server(chunks: () -> Array<Chunk>): Server {
        val world =
            Proxy.newProxyInstance(
                World::class.java.classLoader,
                arrayOf(World::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getLoadedChunks" -> chunks()
                    else -> primitiveDefault(method.returnType)
                }
            } as World
        return Proxy.newProxyInstance(
            Server::class.java.classLoader,
            arrayOf(Server::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getWorlds" -> listOf(world)
                else -> primitiveDefault(method.returnType)
            }
        } as Server
    }

    private class FakeRuntime(
        record: ItemStateJournalRecord,
    ) : ItemStateJournalRuntimeAccess {
        private val records = mapOf(record.identity to record)
        val degradedReasons = mutableListOf<String>()

        override fun stage(record: ItemStateJournalRecord): ItemStateDurabilityOutcome = ItemStateDurabilityOutcome.Accepted(false)

        override fun get(identity: ItemStateJournalIdentity): ItemStateJournalRecord? = records[identity]

        override fun refreshPresentation(record: ItemStateJournalRecord): ItemStateDurabilityOutcome =
            ItemStateDurabilityOutcome.Accepted(false)

        override fun degrade(reason: String) {
            degradedReasons += reason
        }
    }

    private class FakeEntityPort(
        private val fingerprint: ItemStateJournalFingerprint,
        private val presentationRejected: Boolean = false,
    ) : BukkitItemStateJournalEntityPort {
        var publishedRevision: Long? = null
        var restoredPresentation: com.github.command1264.itemdropv2.core.ItemStateJournalPresentation? = null

        override fun publish(
            item: Item,
            state: ItemState,
            revision: Long,
        ): ItemStateDurabilityOutcome {
            publishedRevision = revision
            return ItemStateDurabilityOutcome.Accepted(false)
        }

        override fun fingerprint(item: Item): BukkitItemStateJournalFingerprintResult =
            BukkitItemStateJournalFingerprintResult.Created(fingerprint)

        override fun restorePresentation(
            item: Item,
            snapshot: com.github.command1264.itemdropv2.core.ItemStateJournalPresentation,
        ): PresentationJournalSnapshotResult {
            if (presentationRejected) {
                return PresentationJournalSnapshotResult.Rejected("ManagedNameMismatch")
            }
            restoredPresentation = snapshot
            return PresentationJournalSnapshotResult.Applied
        }
    }

    private companion object {
        private val WORLD_UUID = UUID.fromString("00000000-0000-0000-0000-000000000501")
        private val ENTITY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000502")
        private val OTHER_ENTITY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000099")
        private val NO_OP_TASK_EXECUTOR = MainThreadTaskExecutor {}

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
}
