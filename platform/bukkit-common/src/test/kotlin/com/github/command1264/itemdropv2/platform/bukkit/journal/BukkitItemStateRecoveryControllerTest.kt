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

    private fun repository(): BukkitItemStateRepository =
        BukkitItemStateRepository(
            targetResolver = { null },
            primaryThreadCheck = { true },
        )

    private fun item(container: FakePersistentDataContainer): Item {
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
                "getUniqueId" -> ENTITY_UUID
                "getPersistentDataContainer" -> container
                "getWorld" -> world
                else -> primitiveDefault(method.returnType)
            }
        } as Item
    }

    private fun chunk(entities: () -> Array<Entity>): Chunk =
        Proxy.newProxyInstance(
            Chunk::class.java.classLoader,
            arrayOf(Chunk::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getEntities" -> entities()
                "isLoaded" -> true
                else -> primitiveDefault(method.returnType)
            }
        } as Chunk

    private class FakeRuntime(
        record: ItemStateJournalRecord,
    ) : ItemStateJournalRuntimeAccess {
        private val records = mapOf(record.identity to record)

        override fun stage(record: ItemStateJournalRecord): ItemStateDurabilityOutcome = ItemStateDurabilityOutcome.Accepted(false)

        override fun get(identity: ItemStateJournalIdentity): ItemStateJournalRecord? = records[identity]

        override fun refreshPresentation(record: ItemStateJournalRecord): ItemStateDurabilityOutcome =
            ItemStateDurabilityOutcome.Accepted(false)

        override fun degrade(reason: String) = Unit
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
