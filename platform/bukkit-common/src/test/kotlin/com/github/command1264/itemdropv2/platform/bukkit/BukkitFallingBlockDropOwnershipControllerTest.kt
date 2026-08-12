package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentService
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.data.BlockData
import org.bukkit.entity.FallingBlock
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDropItemEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitFallingBlockDropOwnershipControllerTest {
    @Test
    fun `keeps a gravity source context alive for every sequential block in its column`() {
        val bottom = source(Material.GRAVEL, x = 10, owner = OWNER_ID)

        assertEquals(
            152L,
            fallingSourceLifetimeTicks(listOf(bottom, bottom.copy(y = 66), bottom.copy(y = 67))),
        )
    }

    @Test
    fun `attributes an immediately falling player placed gravity block`() {
        val fixture = Fixture()
        val placedBlock = block(Material.SAND, x = 10)
        val placeEvent =
            BlockPlaceEvent(
                placedBlock,
                proxy<BlockState> { method, _ -> defaultValue(method.returnType) },
                block(Material.STONE, x = 10),
                ItemStack(Material.SAND),
                player(OWNER_ID),
                true,
                EquipmentSlot.HAND,
            )
        val entity = fallingBlock(Material.SAND, x = 10)
        val item = item(Material.SAND, x = 10)

        fixture.controller.onBlockPlace(placeEvent)
        fixture.controller.onFallingBlockSpawn(EntitySpawnEvent(entity))
        fixture.controller.onFallingBlockDrop(EntityDropItemEvent(entity, item))
        fixture.runOwnershipTasks()

        assertEquals(
            OWNER_ID,
            fixture.repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `attributes scaffolding that collapses directly into an item`() {
        val fixture = Fixture()
        val item = item(Material.SCAFFOLDING, x = 10)
        fixture.tracker.record(listOf(source(Material.SCAFFOLDING, x = 10, owner = OWNER_ID)))

        fixture.controller.onPhysicsItemSpawn(ItemSpawnEvent(item))
        fixture.runOwnershipTasks()

        assertEquals(
            OWNER_ID,
            fixture.repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `attributes a directly broken scaffolding item without a block drop event`() {
        val fixture = Fixture()
        val brokenBlock =
            proxy<Block> { method, _ ->
                when (method.name) {
                    "getType" -> Material.SCAFFOLDING
                    "getX" -> 10
                    "getY" -> 65
                    "getZ" -> 20
                    "getWorld" -> world()
                    "getRelative" -> block(Material.AIR, x = 10)
                    else -> defaultValue(method.returnType)
                }
            }
        val item = item(Material.SCAFFOLDING, x = 10)

        fixture.controller.onBlockBreak(BlockBreakEvent(brokenBlock, player(OWNER_ID)))
        fixture.controller.onPhysicsItemSpawn(ItemSpawnEvent(item))
        fixture.runOwnershipTasks()

        assertEquals(
            OWNER_ID,
            fixture.repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `leases the event item for synchronous ownership assignment`() {
        var leasesAcquired = 0
        var leasesReleased = 0
        val fixture =
            Fixture(
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory {
                        leasesAcquired += 1
                        TransientItemTargetLease { leasesReleased += 1 }
                    },
            )
        val item = item(Material.SCAFFOLDING, x = 10)
        fixture.tracker.record(listOf(source(Material.SCAFFOLDING, x = 10, owner = OWNER_ID)))

        fixture.controller.onPhysicsItemSpawn(ItemSpawnEvent(item))

        assertEquals(1, leasesAcquired)
        assertEquals(1, leasesReleased)
    }

    @Test
    fun `reconciles falling block ownership after Spigot publishes the dropped item`() {
        val repository = PublicationAwareRepository()
        val tracker = FallingBlockOwnershipTracker()
        val delayedTasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller =
            BukkitFallingBlockDropOwnershipController(
                assignmentService =
                    ItemOwnershipAssignmentService(
                        repository,
                        ItemDisplaySettingsRepository { settings() },
                    ),
                delayedTaskExecutor = DelayedMainThreadTaskExecutor { delay, task -> delayedTasks += delay to task },
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                itemRefresh = ItemOwnershipRefresh {},
                sourceScanner = FallingBlockSourceScanner(),
                tracker = tracker,
                ownerStore = MemoryOwnerStore(),
                transientTargetLeaseFactory = repository.leaseFactory,
            )
        val entity = fallingBlock(Material.GRAVEL, x = 10)
        val item = item(Material.GRAVEL, x = 10)
        tracker.record(listOf(source(Material.GRAVEL, x = 10, owner = OWNER_ID)))

        controller.onFallingBlockSpawn(EntitySpawnEvent(entity))
        controller.onFallingBlockDrop(EntityDropItemEvent(entity, item))

        assertEquals(OWNER_ID, repository.eventState?.ownership?.ownerUuid)
        assertNull(repository.canonicalState)
        repository.canonicalPublished = true
        delayedTasks.single { (delay, _) -> delay == 1L }.second.invoke()

        assertEquals(OWNER_ID, repository.canonicalState?.ownership?.ownerUuid)
        assertFalse(repository.leaseActive)
    }

    @Test
    fun `falling reconciliation preserves the complete event-time state when canonical publication is stale`() {
        val repository = PublicationAwareRepository()
        repository.eventState = ItemState(null, elapsedLifetimeSeconds = 7, originalLifetimeSeconds = 300)
        val tracker = FallingBlockOwnershipTracker()
        val delayedTasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller =
            BukkitFallingBlockDropOwnershipController(
                assignmentService =
                    ItemOwnershipAssignmentService(
                        repository,
                        ItemDisplaySettingsRepository { settings() },
                    ),
                sourceScanner = FallingBlockSourceScanner(),
                tracker = tracker,
                ownerStore = MemoryOwnerStore(),
                delayedTaskExecutor = DelayedMainThreadTaskExecutor { delay, task -> delayedTasks += delay to task },
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                transientTargetLeaseFactory = repository.leaseFactory,
                itemRefresh = ItemOwnershipRefresh {},
            )
        val entity = fallingBlock(Material.GRAVEL, x = 10)
        val item = item(Material.GRAVEL, x = 10)
        tracker.record(listOf(source(Material.GRAVEL, x = 10, owner = OWNER_ID)))

        controller.onFallingBlockSpawn(EntitySpawnEvent(entity))
        controller.onFallingBlockDrop(EntityDropItemEvent(entity, item))
        repository.canonicalPublished = true
        delayedTasks.single { (delay, _) -> delay == 1L }.second.invoke()

        assertEquals(293L, repository.canonicalState?.remainingLifetimeSeconds)
        assertEquals(7L, repository.canonicalState?.elapsedLifetimeSeconds)
        assertEquals(OWNER_ID, repository.canonicalState?.ownership?.ownerUuid)
    }

    @Test
    fun `falling reconciliation reloads the canonical item only after the event target is missing`() {
        val repository = RecordingRepository(missingLoadsRemaining = 1)
        val fixture = Fixture(repository = repository)
        val entity = fallingBlock(Material.GRAVEL, x = 10)
        val item = item(Material.GRAVEL, x = 10)
        fixture.tracker.record(listOf(source(Material.GRAVEL, x = 10, owner = OWNER_ID)))

        fixture.controller.onFallingBlockSpawn(EntitySpawnEvent(entity))
        fixture.controller.onFallingBlockDrop(EntityDropItemEvent(entity, item))

        assertTrue(repository.states.isEmpty())
        fixture.runOwnershipTasks()

        assertEquals(2, repository.loadCount)
        assertEquals(
            OWNER_ID,
            repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `hands falling drop ownership to the matching Spigot item spawn wrapper`() {
        val fixture = Fixture()
        val entity = fallingBlock(Material.GRAVEL, x = 10)
        val item = item(Material.GRAVEL, x = 10)
        fixture.tracker.record(listOf(source(Material.GRAVEL, x = 10, owner = OWNER_ID)))

        fixture.controller.onFallingBlockSpawn(EntitySpawnEvent(entity))
        fixture.controller.onFallingBlockDrop(EntityDropItemEvent(entity, item))
        fixture.repository.states.clear()

        fixture.controller.onPhysicsItemSpawn(ItemSpawnEvent(item))

        assertEquals(
            OWNER_ID,
            fixture.repository.states[item.uniqueId]
                ?.ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `releases a pending falling block item lease when the controller closes`() {
        val repository = PublicationAwareRepository()
        val fixture = Fixture(transientTargetLeaseFactory = repository.leaseFactory)
        val entity = fallingBlock(Material.GRAVEL, x = 10)
        val item = item(Material.GRAVEL, x = 10)
        fixture.tracker.record(listOf(source(Material.GRAVEL, x = 10, owner = OWNER_ID)))

        fixture.controller.onFallingBlockSpawn(EntitySpawnEvent(entity))
        fixture.controller.onFallingBlockDrop(EntityDropItemEvent(entity, item))
        assertTrue(repository.leaseActive)

        fixture.controller.close()

        assertFalse(repository.leaseActive)
    }

    @Test
    fun `carries an exact source owner through falling block to item`() {
        val fixture = Fixture()
        val entity = fallingBlock(Material.GRAVEL, x = 10)
        val item = item(Material.GRAVEL, x = 10)
        fixture.tracker.record(listOf(source(Material.GRAVEL, x = 10, owner = OWNER_ID)))

        fixture.controller.onFallingBlockSpawn(EntitySpawnEvent(entity))
        fixture.controller.onFallingBlockChange(
            EntityChangeBlockEvent(entity, proxy<Block> { method, _ -> defaultValue(method.returnType) }, blockData(Material.AIR)),
        )
        fixture.controller.onFallingBlockDrop(EntityDropItemEvent(entity, item))
        fixture.runOwnershipTasks()

        assertEquals(
            OWNER_ID,
            fixture.repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
        assertNull(fixture.store.read(entity))
        assertFalse(fixture.tracker.complete(entity.uniqueId))
    }

    @Test
    fun `binds owner when modern server reports source removal before entity spawn`() {
        val fixture = Fixture()
        val entity = fallingBlock(Material.GRAVEL, x = 10)
        val item = item(Material.GRAVEL, x = 10)
        fixture.tracker.record(listOf(source(Material.GRAVEL, x = 10, owner = OWNER_ID)))

        fixture.controller.onFallingBlockChange(
            EntityChangeBlockEvent(entity, proxy<Block> { method, _ -> defaultValue(method.returnType) }, blockData(Material.AIR)),
        )
        fixture.controller.onFallingBlockSpawn(EntitySpawnEvent(entity))
        fixture.controller.onFallingBlockDrop(EntityDropItemEvent(entity, item))
        fixture.runOwnershipTasks()

        assertEquals(
            OWNER_ID,
            fixture.repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `landing clears transient owner without assigning an item`() {
        val fixture = Fixture()
        val entity = fallingBlock(Material.SAND, x = 10)
        fixture.tracker.record(listOf(source(Material.SAND, x = 10, owner = OWNER_ID)))

        fixture.controller.onFallingBlockSpawn(EntitySpawnEvent(entity))
        fixture.controller.onFallingBlockChange(
            EntityChangeBlockEvent(entity, proxy<Block> { method, _ -> defaultValue(method.returnType) }, blockData(Material.SAND)),
        )

        assertNull(fixture.store.read(entity))
        assertFalse(fixture.tracker.complete(entity.uniqueId))
        assertTrue(fixture.repository.states.isEmpty())
    }

    @Test
    fun `persisted falling owner survives a controller restart`() {
        val entity = fallingBlock(Material.GRAVEL, x = 10)
        val item = item(Material.GRAVEL, x = 10)
        val store = MemoryOwnerStore()
        store.write(entity, OWNER_ID)
        val fixture = Fixture(store = store)

        fixture.controller.onFallingBlockDrop(EntityDropItemEvent(entity, item))
        fixture.runOwnershipTasks()

        assertEquals(
            OWNER_ID,
            fixture.repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
        assertNull(store.read(entity))
    }

    @Test
    fun `natural falling blocks remain unowned`() {
        val fixture = Fixture()
        val entity = fallingBlock(Material.SAND, x = 10)
        val item = item(Material.SAND, x = 10)

        fixture.controller.onFallingBlockSpawn(EntitySpawnEvent(entity))
        fixture.controller.onFallingBlockDrop(EntityDropItemEvent(entity, item))
        fixture.runOwnershipTasks()

        assertTrue(fixture.repository.states.isEmpty())
    }

    private class Fixture(
        val store: MemoryOwnerStore = MemoryOwnerStore(),
        val repository: RecordingRepository = RecordingRepository(),
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
            TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    ) {
        val tracker = FallingBlockOwnershipTracker()
        private val delayedTasks = mutableListOf<Pair<Long, () -> Unit>>()
        val controller =
            BukkitFallingBlockDropOwnershipController(
                assignmentService =
                    ItemOwnershipAssignmentService(
                        repository,
                        ItemDisplaySettingsRepository { settings() },
                    ),
                delayedTaskExecutor = DelayedMainThreadTaskExecutor { delay, task -> delayedTasks += delay to task },
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                itemRefresh = ItemOwnershipRefresh {},
                sourceScanner = FallingBlockSourceScanner(),
                tracker = tracker,
                ownerStore = store,
                transientTargetLeaseFactory = transientTargetLeaseFactory,
            )

        fun runOwnershipTasks() {
            delayedTasks
                .filter { (delay, _) -> delay == 1L }
                .toList()
                .forEach { (_, task) -> task() }
        }
    }

    private class MemoryOwnerStore : FallingBlockOwnerStore {
        private val owners = mutableMapOf<UUID, UUID>()

        override fun read(entity: FallingBlock): UUID? = owners[entity.uniqueId]

        override fun write(
            entity: FallingBlock,
            ownerUuid: UUID,
        ) {
            owners[entity.uniqueId] = ownerUuid
        }

        override fun clear(entity: FallingBlock) {
            owners.remove(entity.uniqueId)
        }
    }

    private class RecordingRepository(
        private var missingLoadsRemaining: Int = 0,
    ) : ItemStateRepository {
        val states = mutableMapOf<UUID, ItemState>()
        var loadCount = 0
            private set

        override fun load(entityId: UUID): ItemStateLoadResult {
            loadCount++
            if (missingLoadsRemaining > 0) {
                missingLoadsRemaining--
                return ItemStateLoadResult.MissingTarget
            }
            return states[entityId]?.let(ItemStateLoadResult::Loaded) ?: ItemStateLoadResult.Absent
        }

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            states[entityId] = state
            return ItemStateWriteResult.Applied
        }
    }

    private class PublicationAwareRepository : ItemStateRepository {
        var leaseActive = false
        var canonicalPublished = false
        var eventState: ItemState? = null
        var canonicalState: ItemState? = null
        val leaseFactory =
            TransientItemTargetLeaseFactory {
                leaseActive = true
                TransientItemTargetLease { leaseActive = false }
            }

        override fun load(entityId: UUID): ItemStateLoadResult =
            (if (leaseActive) eventState else canonicalState)
                ?.let(ItemStateLoadResult::Loaded)
                ?: ItemStateLoadResult.Absent

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            if (leaseActive) {
                eventState = state
            } else if (canonicalPublished) {
                canonicalState = state
            }
            return ItemStateWriteResult.Applied
        }
    }

    private fun fallingBlock(
        material: Material,
        x: Int,
    ): FallingBlock {
        val id = UUID.randomUUID()
        return proxy { method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                "getWorld" -> world()
                "getLocation" -> Location(world(), x.toDouble(), 65.0, 20.0)
                "getBlockData" -> blockData(material)
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun item(
        material: Material,
        x: Int,
    ): Item {
        val id = UUID.randomUUID()
        return proxy { method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                "getWorld" -> world()
                "getLocation" -> Location(world(), x + 0.5, 65.2, 20.5)
                "getItemStack" -> ItemStack(material)
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun blockData(material: Material): BlockData =
        proxy { method, _ -> if (method.name == "getMaterial") material else defaultValue(method.returnType) }

    private fun block(
        material: Material,
        x: Int,
    ): Block =
        proxy { method, _ ->
            when (method.name) {
                "getType" -> material
                "getX" -> x
                "getY" -> 65
                "getZ" -> 20
                "getWorld" -> world()
                else -> defaultValue(method.returnType)
            }
        }

    private fun player(ownerUuid: UUID): Player =
        proxy { method, _ -> if (method.name == "getUniqueId") ownerUuid else defaultValue(method.returnType) }

    private fun world(): World =
        proxy { method, _ ->
            when (method.name) {
                "getUID" -> WORLD_ID
                "getName" -> "world"
                else -> defaultValue(method.returnType)
            }
        }

    private fun source(
        material: Material,
        x: Int,
        owner: UUID,
    ): FallingBlockSource =
        FallingBlockSource(
            worldId = WORLD_ID,
            x = x,
            y = 65,
            z = 20,
            materialName = material.name,
            ownerUuid = owner,
        )

    private inline fun <reified T> proxy(crossinline answer: (Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method, args)
        } as T

    private companion object {
        private val WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020")

        private fun settings(): ItemDisplaySettings {
            val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
            return ItemDisplaySettings(true, emptySet(), template, template)
        }

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
