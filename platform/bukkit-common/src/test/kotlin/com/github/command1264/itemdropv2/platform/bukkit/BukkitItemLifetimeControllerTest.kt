package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemLifetimeService
import com.github.command1264.itemdropv2.core.ItemOwnership
import com.github.command1264.itemdropv2.core.ItemProcessingWheel
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import com.github.command1264.itemdropv2.core.VirtualItemAmount
import com.github.command1264.itemdropv2.core.VirtualItemStackingSettings
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.entity.ItemDespawnEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

@Suppress("LargeClass")
class BukkitItemLifetimeControllerTest {
    @Test
    fun `loaded item recovery gate runs before startup lifetime registration`() {
        val repository = SequencedRepository(ArrayDeque(listOf(ItemStateLoadResult.Absent)))
        val item = itemProxy()
        val chunk =
            proxy<Chunk> { method, _ ->
                when (method.name) {
                    "getEntities" -> arrayOf(item)
                    else -> defaultValue(method.returnType)
                }
            }
        val world =
            proxy<World> { method, _ ->
                when (method.name) {
                    "getLoadedChunks" -> arrayOf(chunk)
                    else -> defaultValue(method.returnType)
                }
            }
        val server =
            proxy<Server> { method, _ ->
                when (method.name) {
                    "getWorlds" -> listOf(world)
                    "getEntity" -> item
                    else -> defaultValue(method.returnType)
                }
            }
        val calls = mutableListOf<String>()
        val fixture =
            registrationFixture(
                repository = repository,
                item = item,
                server = server,
                loadedItemReadiness = {
                    calls += "recover"
                    false
                },
            )

        fixture.controller.registerLoadedItems()

        assertEquals(listOf("recover"), calls)
        assertTrue(repository.savedStates.isEmpty())
        assertTrue(fixture.wheel.trackedEntityIds().isEmpty())
    }

    @Test
    fun `loaded item recovery gate failure skips startup registration with a warning`() {
        val repository = SequencedRepository(ArrayDeque(listOf(ItemStateLoadResult.Absent)))
        val item = itemProxy()
        val chunk =
            proxy<Chunk> { method, _ ->
                if (method.name == "getEntities") arrayOf(item) else defaultValue(method.returnType)
            }
        val world =
            proxy<World> { method, _ ->
                if (method.name == "getLoadedChunks") arrayOf(chunk) else defaultValue(method.returnType)
            }
        val server =
            proxy<Server> { method, _ ->
                when (method.name) {
                    "getWorlds" -> listOf(world)
                    "getEntity" -> item
                    else -> defaultValue(method.returnType)
                }
            }
        val fixture =
            registrationFixture(
                repository = repository,
                item = item,
                server = server,
                loadedItemReadiness = { error("recovery unavailable") },
            )

        fixture.controller.registerLoadedItems()

        assertEquals(listOf("loaded item recovery readiness failed (IllegalStateException)"), fixture.warnings)
        assertTrue(repository.savedStates.isEmpty())
        assertTrue(fixture.wheel.trackedEntityIds().isEmpty())
    }

    @Test
    fun `startup loaded chunk retry registers an item published after the initial empty snapshot`() {
        val repository =
            SequencedRepository(
                ArrayDeque(listOf(ItemStateLoadResult.Loaded(ItemState(null, remainingLifetimeSeconds = 40)))),
            )
        val item = itemProxy()
        var entityReads = 0
        val chunk =
            proxy<Chunk> { method, _ ->
                when (method.name) {
                    "getEntities" ->
                        if (entityReads++ == 0) {
                            emptyArray<org.bukkit.entity.Entity>()
                        } else {
                            arrayOf(item)
                        }
                    "isLoaded" -> true
                    else -> defaultValue(method.returnType)
                }
            }
        val world =
            proxy<World> { method, _ ->
                if (method.name == "getLoadedChunks") arrayOf(chunk) else defaultValue(method.returnType)
            }
        val server =
            proxy<Server> { method, _ ->
                when (method.name) {
                    "getWorlds" -> listOf(world)
                    "getEntity" -> item
                    else -> defaultValue(method.returnType)
                }
            }
        val readinessCalls = mutableListOf<UUID>()
        val fixture =
            registrationFixture(
                repository = repository,
                item = item,
                server = server,
                loadedItemReadiness = {
                    readinessCalls += it.uniqueId
                    true
                },
            )

        fixture.controller.registerLoadedItems()
        fixture.tasks.removeFirst().invoke()

        assertEquals(listOf(ENTITY_ID), readinessCalls)
        assertEquals(setOf(ENTITY_ID), fixture.wheel.trackedEntityIds())
    }

    @Test
    fun `spawn registration runs at highest before native item merge`() {
        val handler =
            BukkitItemLifetimeController::class.java
                .getDeclaredMethod("onItemSpawn", ItemSpawnEvent::class.java)
                .getAnnotation(EventHandler::class.java)

        assertEquals(EventPriority.HIGHEST, handler.priority)
        assertTrue(handler.ignoreCancelled)
    }

    @Test
    fun `registration retries lookup misses and tracks a visible entity`() {
        val repository =
            SequencedRepository(
                ArrayDeque(
                    listOf(
                        ItemStateLoadResult.MissingTarget,
                        ItemStateLoadResult.MissingTarget,
                        ItemStateLoadResult.MissingTarget,
                        ItemStateLoadResult.Loaded(ItemState(null, 0, 300)),
                    ),
                ),
            )
        val fixture = registrationFixture(repository)

        fixture.controller.onItemSpawn(ItemSpawnEvent(fixture.item))
        repeat(3) { fixture.tasks.removeFirst().invoke() }

        assertEquals(setOf(ENTITY_ID), fixture.wheel.trackedEntityIds())
        assertTrue(fixture.warnings.isEmpty())
    }

    @Test
    fun `spawn event immediately persists material lifetime before a merge can begin`() {
        val repository = SequencedRepository(ArrayDeque(listOf(ItemStateLoadResult.Absent)))
        val fixture = registrationFixture(repository)
        val target = itemProxy(TARGET_ID)

        fixture.controller.onItemSpawn(ItemSpawnEvent(fixture.item))
        val completedMerge = ItemMergeEvent(fixture.item, target)
        fixture.controller.onPendingLifetimeMerge(completedMerge)

        assertFalse(completedMerge.isCancelled)
        assertEquals(300, repository.savedStates.single().lifetimeSeconds)
        assertTrue(fixture.tasks.isEmpty())
    }

    @Test
    fun `spawn event persists virtual amount before native merge can inspect state`() {
        val repository = SpawnStateRepository()
        val settingsRepository =
            testSettingsRepository(
                virtualStacking = VirtualItemStackingSettings(enabled = true),
            )
        val fixture =
            registrationFixture(
                repository,
                carrierNormalization =
                    VirtualItemCarrierNormalization { item ->
                        val current = (repository.load(item.uniqueId) as ItemStateLoadResult.Loaded).state
                        val virtualAmount = VirtualItemAmount.of(item.itemStack.amount.toLong())
                        repository.save(item.uniqueId, current.copy(virtualAmount = virtualAmount))
                        VirtualItemCarrierNormalizationOutcome.Normalized(virtualAmount, migrated = true)
                    },
                settingsRepository = settingsRepository,
                item = itemProxy(stack = ItemStack(Material.STONE, 3)),
            )

        fixture.controller.onItemSpawn(ItemSpawnEvent(fixture.item))

        assertEquals(VirtualItemAmount.of(3), repository.state?.virtualAmount)
        assertEquals(300, repository.state?.remainingLifetimeSeconds)
        assertTrue(fixture.tasks.isEmpty())
    }

    @Test
    fun `spawned item keeps its initial lifetime for nineteen ticks and decrements on tick twenty`() {
        val repository = SpawnStateRepository()
        val fixture = registrationFixture(repository)

        fixture.controller.onItemSpawn(ItemSpawnEvent(fixture.item))
        repeat(ItemProcessingWheel.SLOT_COUNT - 1) {
            fixture.controller.processNextSlot()
            assertEquals(300L, repository.state?.remainingLifetimeSeconds)
        }

        fixture.controller.processNextSlot()

        assertEquals(299L, repository.state?.remainingLifetimeSeconds)
        repeat(ItemProcessingWheel.SLOT_COUNT - 1) {
            fixture.controller.processNextSlot()
            assertEquals(299L, repository.state?.remainingLifetimeSeconds)
        }
        fixture.controller.processNextSlot()
        assertEquals(298L, repository.state?.remainingLifetimeSeconds)
    }

    @Test
    fun `spawned item processing reuses its registered wrapper without a global entity scan`() {
        val repository = SpawnStateRepository()
        val item = itemProxy()
        val server =
            proxy<Server> { method, _ ->
                when (method.name) {
                    "getEntity", "getWorlds" -> error("registered wrapper should avoid global lookup")
                    else -> defaultValue(method.returnType)
                }
            }
        val fixture = registrationFixture(repository, item = item, server = server)

        fixture.controller.onItemSpawn(ItemSpawnEvent(item))
        repeat(ItemProcessingWheel.SLOT_COUNT) { fixture.controller.processNextSlot() }

        assertEquals(299L, repository.state?.remainingLifetimeSeconds)
    }

    @Test
    fun `missing target registration stops after its bounded retry window when entity remains absent`() {
        val repository =
            SequencedRepository(
                ArrayDeque(
                    List(20) { ItemStateLoadResult.MissingTarget },
                ),
            )
        val fixture = registrationFixture(repository)

        fixture.controller.onItemSpawn(ItemSpawnEvent(fixture.item))
        repeat(19) { fixture.tasks.removeFirst().invoke() }

        assertTrue(fixture.wheel.trackedEntityIds().isEmpty())
        assertEquals(
            listOf(
                "LIFETIME-REGISTRATION-MISSING-TARGET: registration abandoned after 20 attempts over 19 retry ticks; " +
                    "item=STONE x1, entity=$ENTITY_ID, world=world, position=(8,64,12), valid=true, dead=false, " +
                    "chunkLoaded=true; this item may use vanilla despawn timing",
            ),
            fixture.warnings,
        )
        assertTrue(fixture.tasks.isEmpty())
    }

    @Test
    fun `spawn registration uses and releases a transient target lease in the event callback`() {
        val repository = SequencedRepository(ArrayDeque(listOf(ItemStateLoadResult.Absent)))
        val leases = RecordingLeaseFactory()
        var normalizedWhileLeased = false
        val fixture =
            registrationFixture(
                repository,
                carrierNormalization = {
                    normalizedWhileLeased = leases.active == 1
                    VirtualItemCarrierNormalizationOutcome.Unmanaged
                },
                transientTargetLeaseFactory = leases,
            )

        fixture.controller.onItemSpawn(ItemSpawnEvent(fixture.item))

        assertEquals(1, leases.acquired)
        assertEquals(1, leases.released)
        assertTrue(normalizedWhileLeased)
        assertEquals(setOf(ENTITY_ID), fixture.wheel.trackedEntityIds())
        assertTrue(fixture.tasks.isEmpty())
    }

    @Test
    fun `spawn registration normalizes carrier while event item is not yet valid`() {
        val repository = SequencedRepository(ArrayDeque(listOf(ItemStateLoadResult.Absent)))
        val itemState = MutableItemState(valid = false)
        val leases = RecordingLeaseFactory()
        var normalizationCalls = 0
        var normalizedWhileLeased = false
        val fixture =
            registrationFixture(
                repository,
                item = itemProxy(state = itemState),
                carrierNormalization = {
                    normalizationCalls++
                    normalizedWhileLeased = leases.active == 1
                    VirtualItemCarrierNormalizationOutcome.Unmanaged
                },
                transientTargetLeaseFactory = leases,
            )

        fixture.controller.onItemSpawn(ItemSpawnEvent(fixture.item))

        assertEquals(300, repository.savedStates.single().lifetimeSeconds)
        assertEquals(1, normalizationCalls)
        assertTrue(normalizedWhileLeased)
        assertTrue(fixture.tasks.isEmpty())
        assertEquals(1, leases.acquired)
        assertEquals(1, leases.released)
    }

    @Test
    fun `retryable spawn carrier normalization blocks merge until retry succeeds`() {
        val repository = SequencedRepository(ArrayDeque(listOf(ItemStateLoadResult.Absent)))
        val outcomes =
            ArrayDeque<VirtualItemCarrierNormalizationOutcome>(
                listOf(
                    VirtualItemCarrierNormalizationOutcome.Rejected("StateNotReady"),
                    VirtualItemCarrierNormalizationOutcome.Unmanaged,
                ),
            )
        val fixture =
            registrationFixture(
                repository,
                carrierNormalization = { outcomes.removeFirst() },
            )
        val target = itemProxy(TARGET_ID)

        fixture.controller.onItemSpawn(ItemSpawnEvent(fixture.item))
        val pendingMerge = ItemMergeEvent(fixture.item, target)
        fixture.controller.onPendingLifetimeMerge(pendingMerge)

        assertTrue(pendingMerge.isCancelled)
        assertEquals(1, fixture.tasks.size)

        fixture.tasks.removeFirst().invoke()
        val completedMerge = ItemMergeEvent(fixture.item, target)
        fixture.controller.onPendingLifetimeMerge(completedMerge)

        assertFalse(completedMerge.isCancelled)
        assertTrue(fixture.warnings.isEmpty())
        assertTrue(fixture.tasks.isEmpty())
    }

    @Test
    fun `carrier normalization retry does not run after controller closes`() {
        val repository = SequencedRepository(ArrayDeque(listOf(ItemStateLoadResult.Absent)))
        var normalizationCalls = 0
        val fixture =
            registrationFixture(
                repository,
                carrierNormalization = {
                    normalizationCalls++
                    VirtualItemCarrierNormalizationOutcome.Rejected("StateNotReady")
                },
            )

        fixture.controller.onItemSpawn(ItemSpawnEvent(fixture.item))
        fixture.controller.close()
        fixture.tasks.removeFirst().invoke()

        assertEquals(1, normalizationCalls)
        assertTrue(fixture.warnings.isEmpty())
    }

    @Test
    fun `despawn inspection uses and releases the event item transient target`() {
        val repository =
            SequencedRepository(
                ArrayDeque(
                    listOf(
                        ItemStateLoadResult.Absent,
                        ItemStateLoadResult.Loaded(ItemState(null, remainingLifetimeSeconds = 300)),
                    ),
                ),
            )
        val leases = RecordingLeaseFactory()
        val fixture = registrationFixture(repository, transientTargetLeaseFactory = leases)
        fixture.controller.onItemSpawn(ItemSpawnEvent(fixture.item))
        val event = ItemDespawnEvent(fixture.item, fixture.item.location)

        fixture.controller.onItemDespawn(event)

        assertTrue(event.isCancelled)
        assertEquals(2, leases.acquired)
        assertEquals(2, leases.released)
    }

    @Test
    fun `removed target after bounded lookup attempts is treated as normal lifecycle completion`() {
        val repository = SequencedRepository(ArrayDeque(List(20) { ItemStateLoadResult.MissingTarget }))
        val itemState = MutableItemState(valid = true, dead = false, chunkLoaded = true)
        val fixture = registrationFixture(repository, item = itemProxy(state = itemState))

        fixture.controller.onItemSpawn(ItemSpawnEvent(fixture.item))
        itemState.valid = false
        itemState.dead = true
        repeat(19) { fixture.tasks.removeFirst().invoke() }

        assertTrue(fixture.warnings.isEmpty())
        assertTrue(fixture.wheel.trackedEntityIds().isEmpty())
    }

    @Test
    fun `unloaded target after bounded lookup attempts waits for chunk recovery without warning`() {
        val repository = SequencedRepository(ArrayDeque(List(20) { ItemStateLoadResult.MissingTarget }))
        val itemState = MutableItemState(valid = true, dead = false, chunkLoaded = false)
        val fixture =
            registrationFixture(
                repository,
                item = itemProxy(state = itemState),
                loadedChunkCheck = { itemState.chunkLoaded },
            )

        fixture.controller.onItemSpawn(ItemSpawnEvent(fixture.item))
        repeat(19) { fixture.tasks.removeFirst().invoke() }

        assertTrue(fixture.warnings.isEmpty())
        assertTrue(fixture.wheel.trackedEntityIds().isEmpty())
    }

    @Test
    fun `never pickup transient item is not registered for plugin lifetime`() {
        val repository = SequencedRepository(ArrayDeque(listOf(ItemStateLoadResult.Absent)))
        val fixture = registrationFixture(repository, item = itemProxy(pickupDelay = Short.MAX_VALUE.toInt()))

        fixture.controller.onItemSpawn(ItemSpawnEvent(fixture.item))

        assertTrue(fixture.tasks.isEmpty())
        assertTrue(fixture.wheel.trackedEntityIds().isEmpty())
        assertTrue(repository.savedStates.isEmpty())
    }

    @Test
    fun `owner expiry clears state and refreshes display`() {
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(ItemOwnership(OWNER_ID, 1), 0, 300)))
        val item = itemProxy()
        val server = proxy<Server> { method, _ -> if (method.name == "getEntity") item else defaultValue(method.returnType) }
        var refreshedItem: Item? = null
        val refresh =
            object : ItemOwnershipRefresh {
                override fun refresh(entityId: UUID) {
                    error("lifetime refresh must retain the leased Item wrapper")
                }

                override fun refresh(item: Item) {
                    refreshedItem = item
                }
            }
        val leases = RecordingLeaseFactory()
        val wheel = ItemProcessingWheel()
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        val service =
            ItemLifetimeService(
                repository,
                ItemDisplaySettingsRepository { ItemDisplaySettings(true, emptySet(), template, template) },
            )
        val controller =
            BukkitItemLifetimeController(
                server,
                service,
                wheel,
                refresh,
                DisplayWarningSink { error(it) },
                MainThreadTaskExecutor { it() },
                transientTargetLeaseFactory = leases,
                loadedChunkCheck = { true },
            )
        wheel.register(ENTITY_ID)

        repeat(ItemProcessingWheel.SLOT_COUNT - 1) { controller.processNextSlot() }

        assertEquals(null, refreshedItem)
        assertEquals(
            1L,
            repository.states
                .getValue(ENTITY_ID)
                .ownership
                ?.protectionSecondsRemaining,
        )

        controller.processNextSlot()
        assertSame(item, refreshedItem)
        assertEquals(null, repository.states.getValue(ENTITY_ID).ownership)
        assertEquals(1, leases.acquired)
        assertEquals(1, leases.released)
    }

    @Test
    fun `owner expiry uses loaded item fallback when server entity lookup is transiently missing`() {
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(ItemOwnership(OWNER_ID, 1), 0, 300)))
        val item = itemProxy()
        val chunk =
            proxy<Chunk> { method, _ ->
                if (method.name == "getEntities") arrayOf(item) else defaultValue(method.returnType)
            }
        val world =
            proxy<World> { method, _ ->
                if (method.name == "getLoadedChunks") arrayOf(chunk) else defaultValue(method.returnType)
            }
        val server =
            proxy<Server> { method, _ ->
                when (method.name) {
                    "getEntity" -> null
                    "getWorlds" -> listOf(world)
                    else -> defaultValue(method.returnType)
                }
            }
        val refreshed = mutableListOf<UUID>()
        val wheel = ItemProcessingWheel()
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        val controller =
            BukkitItemLifetimeController(
                server,
                ItemLifetimeService(
                    repository,
                    ItemDisplaySettingsRepository { ItemDisplaySettings(true, emptySet(), template, template) },
                ),
                wheel,
                ItemOwnershipRefresh(refreshed::add),
                DisplayWarningSink { error(it) },
                MainThreadTaskExecutor { it() },
                loadedChunkCheck = { true },
            )
        wheel.register(ENTITY_ID)

        repeat(ItemProcessingWheel.SLOT_COUNT) { controller.processNextSlot() }

        assertEquals(listOf(ENTITY_ID), refreshed)
        assertEquals(null, repository.states.getValue(ENTITY_ID).ownership)
    }

    @Test
    fun `transiently invisible item remains scheduled for the next lifetime cycle`() {
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(null, remainingLifetimeSeconds = 40)))
        val item = itemProxy()
        var directLookups = 0
        val server =
            proxy<Server> { method, _ ->
                when (method.name) {
                    "getEntity" -> if (directLookups++ == 0) null else item
                    "getWorlds" -> emptyList<World>()
                    else -> defaultValue(method.returnType)
                }
            }
        val wheel = ItemProcessingWheel()
        val controller =
            BukkitItemLifetimeController(
                server,
                ItemLifetimeService(repository, testSettingsRepository()),
                wheel,
                ItemOwnershipRefresh {},
                DisplayWarningSink { error(it) },
                MainThreadTaskExecutor { it() },
                loadedChunkCheck = { true },
            )
        wheel.register(ENTITY_ID)

        repeat(ItemProcessingWheel.SLOT_COUNT) { controller.processNextSlot() }

        assertEquals(40L, repository.states.getValue(ENTITY_ID).remainingLifetimeSeconds)
        assertTrue(wheel.isRegistered(ENTITY_ID))

        repeat(ItemProcessingWheel.SLOT_COUNT) { controller.processNextSlot() }

        assertEquals(39L, repository.states.getValue(ENTITY_ID).remainingLifetimeSeconds)
        assertTrue(wheel.isRegistered(ENTITY_ID))
    }

    @Test
    fun `transiently invalid canonical item remains scheduled until it becomes valid`() {
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(null, remainingLifetimeSeconds = 40)))
        val itemState = MutableItemState(valid = false)
        val item = itemProxy(state = itemState)
        val server = proxy<Server> { method, _ -> if (method.name == "getEntity") item else defaultValue(method.returnType) }
        val wheel = ItemProcessingWheel()
        val controller =
            BukkitItemLifetimeController(
                server,
                ItemLifetimeService(repository, testSettingsRepository()),
                wheel,
                ItemOwnershipRefresh {},
                DisplayWarningSink { error(it) },
                MainThreadTaskExecutor { it() },
                loadedChunkCheck = { true },
            )
        wheel.register(ENTITY_ID)

        repeat(ItemProcessingWheel.SLOT_COUNT) { controller.processNextSlot() }

        assertEquals(40L, repository.states.getValue(ENTITY_ID).remainingLifetimeSeconds)
        assertTrue(wheel.isRegistered(ENTITY_ID))

        itemState.valid = true
        repeat(ItemProcessingWheel.SLOT_COUNT) { controller.processNextSlot() }

        assertEquals(39L, repository.states.getValue(ENTITY_ID).remainingLifetimeSeconds)
        assertTrue(wheel.isRegistered(ENTITY_ID))
    }

    @Test
    fun `invalid registered wrapper is replaced by a valid canonical wrapper`() {
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(null, remainingLifetimeSeconds = 40)))
        val registered = itemProxy(state = MutableItemState(valid = false))
        val canonical = itemProxy()
        val server = proxy<Server> { method, _ -> if (method.name == "getEntity") canonical else defaultValue(method.returnType) }
        val fixture = registrationFixture(repository, item = registered, server = server)

        fixture.controller.onItemSpawn(ItemSpawnEvent(registered))
        repeat(ItemProcessingWheel.SLOT_COUNT) { fixture.controller.processNextSlot() }

        assertEquals(39L, repository.states.getValue(ENTITY_ID).remainingLifetimeSeconds)
        assertTrue(fixture.wheel.isRegistered(ENTITY_ID))
    }

    @Test
    fun `item absent for twenty lifetime cycles is forgotten`() {
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(null, remainingLifetimeSeconds = 40)))
        val server =
            proxy<Server> { method, _ ->
                when (method.name) {
                    "getEntity" -> null
                    "getWorlds" -> emptyList<World>()
                    else -> defaultValue(method.returnType)
                }
            }
        val wheel = ItemProcessingWheel()
        val controller =
            BukkitItemLifetimeController(
                server,
                ItemLifetimeService(repository, testSettingsRepository()),
                wheel,
                ItemOwnershipRefresh {},
                DisplayWarningSink { error(it) },
                MainThreadTaskExecutor { it() },
                loadedChunkCheck = { true },
            )
        wheel.register(ENTITY_ID)

        repeat(ItemProcessingWheel.SLOT_COUNT * 19) { controller.processNextSlot() }
        assertTrue(wheel.isRegistered(ENTITY_ID))

        repeat(ItemProcessingWheel.SLOT_COUNT) { controller.processNextSlot() }

        assertEquals(40L, repository.states.getValue(ENTITY_ID).remainingLifetimeSeconds)
        assertTrue(!wheel.isRegistered(ENTITY_ID))
    }

    @Test
    fun `stale entity lookup does not advance lifetime after its chunk unloaded`() {
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(null, remainingLifetimeSeconds = 40)))
        val item = itemProxy()
        val server = proxy<Server> { method, _ -> if (method.name == "getEntity") item else defaultValue(method.returnType) }
        val wheel = ItemProcessingWheel()
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        val service =
            ItemLifetimeService(
                repository,
                ItemDisplaySettingsRepository { ItemDisplaySettings(true, emptySet(), template, template) },
            )
        val controller =
            BukkitItemLifetimeController(
                server,
                service,
                wheel,
                ItemOwnershipRefresh {},
                DisplayWarningSink { error(it) },
                MainThreadTaskExecutor { it() },
                loadedChunkCheck = { false },
            )
        wheel.register(ENTITY_ID)

        repeat(ItemProcessingWheel.SLOT_COUNT) { controller.processNextSlot() }

        assertEquals(40L, repository.states.getValue(ENTITY_ID).remainingLifetimeSeconds)
        assertTrue(wheel.trackedEntityIds().isEmpty())
    }

    @Test
    fun `dead item is forgotten without advancing lifetime`() {
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(null, remainingLifetimeSeconds = 40)))
        val item = itemProxy(state = MutableItemState(dead = true))
        val server = proxy<Server> { method, _ -> if (method.name == "getEntity") item else defaultValue(method.returnType) }
        val wheel = ItemProcessingWheel()
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        val controller =
            BukkitItemLifetimeController(
                server,
                ItemLifetimeService(
                    repository,
                    ItemDisplaySettingsRepository { ItemDisplaySettings(true, emptySet(), template, template) },
                ),
                wheel,
                ItemOwnershipRefresh {},
                DisplayWarningSink { error(it) },
                MainThreadTaskExecutor { it() },
                loadedChunkCheck = { true },
            )
        wheel.register(ENTITY_ID)

        repeat(ItemProcessingWheel.SLOT_COUNT) { controller.processNextSlot() }

        assertEquals(40L, repository.states.getValue(ENTITY_ID).remainingLifetimeSeconds)
        assertTrue(wheel.trackedEntityIds().isEmpty())
    }

    @Test
    fun `empty item is forgotten without advancing lifetime`() {
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(null, remainingLifetimeSeconds = 40)))
        val emptyStack =
            object : ItemStack(Material.STONE) {
                override fun getAmount(): Int = 0
            }
        val item = itemProxy(stack = emptyStack)
        val server = proxy<Server> { method, _ -> if (method.name == "getEntity") item else defaultValue(method.returnType) }
        val wheel = ItemProcessingWheel()
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        val controller =
            BukkitItemLifetimeController(
                server,
                ItemLifetimeService(
                    repository,
                    ItemDisplaySettingsRepository { ItemDisplaySettings(true, emptySet(), template, template) },
                ),
                wheel,
                ItemOwnershipRefresh {},
                DisplayWarningSink { error(it) },
                MainThreadTaskExecutor { it() },
                loadedChunkCheck = { true },
            )
        wheel.register(ENTITY_ID)

        repeat(ItemProcessingWheel.SLOT_COUNT) { controller.processNextSlot() }

        assertEquals(40L, repository.states.getValue(ENTITY_ID).remainingLifetimeSeconds)
        assertTrue(wheel.trackedEntityIds().isEmpty())
    }

    @Test
    fun `chunk load upgrades recovered state and registers it in the wheel`() {
        val repository =
            SequencedRepository(
                ArrayDeque(
                    listOf(
                        ItemStateLoadResult.Loaded(
                            ItemState(null, remainingLifetimeSeconds = null),
                            requiresSchemaUpgrade = true,
                        ),
                    ),
                ),
            )
        val fixture = registrationFixture(repository)
        val chunk =
            proxy<Chunk> { method, _ ->
                if (method.name == "getEntities") arrayOf(fixture.item) else defaultValue(method.returnType)
            }

        fixture.controller.onChunkLoad(ChunkLoadEvent(chunk, false))

        assertEquals(listOf(ItemState(null, remainingLifetimeSeconds = 300)), repository.savedStates)
        assertEquals(setOf(ENTITY_ID), fixture.wheel.trackedEntityIds())
        assertEquals(1, fixture.tasks.size)
    }

    @Test
    fun `chunk load keeps its dedicated bounded retry window until old server exposes entities`() {
        val repository =
            SequencedRepository(
                ArrayDeque(listOf(ItemStateLoadResult.Loaded(ItemState(null, remainingLifetimeSeconds = 40)))),
            )
        val fixture = registrationFixture(repository)
        var entityReads = 0
        val chunk =
            proxy<Chunk> { method, _ ->
                when (method.name) {
                    "getEntities" ->
                        if (entityReads++ < 80) {
                            emptyArray<org.bukkit.entity.Entity>()
                        } else {
                            arrayOf(fixture.item)
                        }
                    "isLoaded" -> true
                    else -> defaultValue(method.returnType)
                }
            }

        fixture.controller.onChunkLoad(ChunkLoadEvent(chunk, false))
        assertTrue(fixture.wheel.trackedEntityIds().isEmpty())
        repeat(80) {
            fixture.tasks.removeFirst().invoke()
            if (it < 79) assertTrue(fixture.wheel.trackedEntityIds().isEmpty())
        }

        assertEquals(setOf(ENTITY_ID), fixture.wheel.trackedEntityIds())
    }

    @Test
    fun `chunk load retry continues after a partial old server entity snapshot`() {
        val repository =
            SequencedRepository(
                ArrayDeque(
                    listOf(
                        ItemStateLoadResult.Loaded(ItemState(null, remainingLifetimeSeconds = 40)),
                        ItemStateLoadResult.Loaded(ItemState(null, remainingLifetimeSeconds = 40)),
                    ),
                ),
            )
        val fixture = registrationFixture(repository)
        val delayed = itemProxy(TARGET_ID)
        var entityReads = 0
        val chunk =
            proxy<Chunk> { method, _ ->
                when (method.name) {
                    "getEntities" ->
                        if (entityReads++ == 0) {
                            arrayOf(fixture.item)
                        } else {
                            arrayOf(fixture.item, delayed)
                        }
                    "isLoaded" -> true
                    else -> defaultValue(method.returnType)
                }
            }

        fixture.controller.onChunkLoad(ChunkLoadEvent(chunk, false))
        fixture.tasks.removeFirst().invoke()

        assertEquals(setOf(ENTITY_ID, TARGET_ID), fixture.wheel.trackedEntityIds())
    }

    @Test
    fun `chunk load retry restores an observed item lost during transient hydration`() {
        val repository =
            SequencedRepository(
                ArrayDeque(
                    listOf(
                        ItemStateLoadResult.Loaded(ItemState(null, remainingLifetimeSeconds = 40)),
                        ItemStateLoadResult.Loaded(ItemState(null, remainingLifetimeSeconds = 40)),
                    ),
                ),
            )
        var chunkReady = true
        val fixture =
            registrationFixture(
                repository,
                loadedChunkCheck = { chunkReady },
            )
        val chunk =
            proxy<Chunk> { method, _ ->
                when (method.name) {
                    "getEntities" -> arrayOf(fixture.item)
                    "isLoaded" -> true
                    else -> defaultValue(method.returnType)
                }
            }

        fixture.controller.onChunkLoad(ChunkLoadEvent(chunk, false))
        chunkReady = false
        repeat(ItemProcessingWheel.SLOT_COUNT) { fixture.controller.processNextSlot() }
        assertTrue(fixture.wheel.trackedEntityIds().isEmpty())

        chunkReady = true
        fixture.tasks.removeFirst().invoke()

        assertEquals(setOf(ENTITY_ID), fixture.wheel.trackedEntityIds())
    }

    @Test
    fun `chunk recovery normalizes carrier after lifetime state is ready`() {
        val repository =
            SequencedRepository(
                ArrayDeque(listOf(ItemStateLoadResult.Loaded(ItemState(null, 120, 300)))),
            )
        val normalized = mutableListOf<UUID>()
        val fixture =
            registrationFixture(
                repository,
                VirtualItemCarrierNormalization { item ->
                    normalized += item.uniqueId
                    VirtualItemCarrierNormalizationOutcome.Normalized(
                        com.github.command1264.itemdropv2.core.VirtualItemAmount.ONE,
                        migrated = true,
                    )
                },
            )
        val chunk =
            proxy<Chunk> { method, _ ->
                if (method.name == "getEntities") arrayOf(fixture.item) else defaultValue(method.returnType)
            }

        fixture.controller.onChunkLoad(ChunkLoadEvent(chunk, false))

        assertEquals(listOf(ENTITY_ID), normalized)
        assertEquals(setOf(ENTITY_ID), fixture.wheel.trackedEntityIds())
    }

    private inline fun <reified T> proxy(crossinline answer: (Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> answer(method, args) } as T

    private fun registrationFixture(
        repository: ItemStateRepository,
        carrierNormalization: VirtualItemCarrierNormalization =
            VirtualItemCarrierNormalization { VirtualItemCarrierNormalizationOutcome.Unmanaged },
        item: Item = itemProxy(),
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
            TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
        loadedChunkCheck: (Item) -> Boolean = { true },
        settingsRepository: ItemDisplaySettingsRepository = testSettingsRepository(),
        server: Server = proxy { method, _ -> if (method.name == "getEntity") item else defaultValue(method.returnType) },
        loadedItemReadiness: (Item) -> Boolean = { true },
    ): RegistrationFixture {
        val tasks = ArrayDeque<() -> Unit>()
        val warnings = mutableListOf<String>()
        val wheel = ItemProcessingWheel()
        val service =
            ItemLifetimeService(
                repository,
                settingsRepository,
            )
        return RegistrationFixture(
            BukkitItemLifetimeController(
                server,
                service,
                wheel,
                ItemOwnershipRefresh {},
                DisplayWarningSink(warnings::add),
                MainThreadTaskExecutor(tasks::add),
                carrierNormalization,
                transientTargetLeaseFactory,
                loadedChunkCheck,
                loadedItemReadiness,
            ),
            wheel,
            item,
            tasks,
            warnings,
        )
    }

    private fun testSettingsRepository(
        virtualStacking: VirtualItemStackingSettings = VirtualItemStackingSettings(),
    ): ItemDisplaySettingsRepository {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return ItemDisplaySettingsRepository {
            ItemDisplaySettings(
                enabled = true,
                blockedWorlds = emptySet(),
                singleItemTemplate = template,
                multipleItemTemplate = template,
                virtualStacking = virtualStacking,
            )
        }
    }

    private data class RegistrationFixture(
        val controller: BukkitItemLifetimeController,
        val wheel: ItemProcessingWheel,
        val item: Item,
        val tasks: ArrayDeque<() -> Unit>,
        val warnings: List<String>,
    )

    private fun itemProxy(
        entityId: UUID = ENTITY_ID,
        pickupDelay: Int = 0,
        state: MutableItemState = MutableItemState(),
        stack: ItemStack = ItemStack(Material.STONE),
    ): Item =
        proxy { method, _ ->
            when (method.name) {
                "getUniqueId" -> entityId
                "getItemStack" -> stack
                "getPickupDelay" -> pickupDelay
                "isValid" -> state.valid
                "isDead" -> state.dead
                "getWorld" -> worldProxy()
                "getLocation" -> Location(worldProxy(), 8.0, 64.0, 12.0)
                else -> defaultValue(method.returnType)
            }
        }

    private fun worldProxy(): World =
        proxy { method, _ ->
            when (method.name) {
                "getName" -> "world"
                else -> defaultValue(method.returnType)
            }
        }

    private data class MutableItemState(
        var valid: Boolean = true,
        var dead: Boolean = false,
        var chunkLoaded: Boolean = true,
    )

    private class RecordingLeaseFactory : TransientItemTargetLeaseFactory {
        var acquired: Int = 0
        var released: Int = 0
        var active: Int = 0

        override fun acquire(item: Item): TransientItemTargetLease {
            acquired++
            active++
            return TransientItemTargetLease {
                released++
                active--
            }
        }
    }

    private class SequencedRepository(
        private val results: ArrayDeque<ItemStateLoadResult>,
    ) : ItemStateRepository {
        val savedStates = mutableListOf<ItemState>()

        override fun load(entityId: UUID): ItemStateLoadResult = results.removeFirst()

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            savedStates += state
            return ItemStateWriteResult.Applied
        }
    }

    private class RecordingRepository(
        val states: MutableMap<UUID, ItemState>,
    ) : ItemStateRepository {
        override fun load(entityId: UUID): ItemStateLoadResult =
            states[entityId]?.let(ItemStateLoadResult::Loaded) ?: ItemStateLoadResult.MissingTarget

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            states[entityId] = state
            return ItemStateWriteResult.Applied
        }
    }

    private class SpawnStateRepository : ItemStateRepository {
        var state: ItemState? = null

        override fun load(entityId: UUID): ItemStateLoadResult = state?.let(ItemStateLoadResult::Loaded) ?: ItemStateLoadResult.Absent

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            this.state = state
            return ItemStateWriteResult.Applied
        }
    }

    private companion object {
        private val ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000005")

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
