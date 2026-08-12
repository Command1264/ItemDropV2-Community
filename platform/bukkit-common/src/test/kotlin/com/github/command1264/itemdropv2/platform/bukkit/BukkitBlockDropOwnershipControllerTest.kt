package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.BlockDropOwnershipService
import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.entity.ItemMergeEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitBlockDropOwnershipControllerTest {
    @Test
    fun `records player UUID before ordinary block drop event returns`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)
        val item = item(Material.STONE, y = 64)
        val event = BlockDropItemEvent(block(64, Material.STONE), proxy(), player(), listOf(item))

        controller.onBlockDrop(event)

        assertEquals(
            OWNER_ID,
            repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
        assertTrue(delayedTasks.isEmpty())
    }

    @Test
    fun `attributes every spawned item from a player-triggered cactus collapse`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)

        controller.onBlockBreak(BlockBreakEvent(block(64, Material.CACTUS), player()))
        val items = (64..66).map { y -> item(Material.CACTUS, y) }
        items.forEach { controller.onPlantItemSpawn(ItemSpawnEvent(it)) }

        assertEquals(3, repository.states.size)
        assertTrue(repository.states.values.all { it.ownership?.ownerUuid == OWNER_ID })
    }

    @Test
    fun `reconciles claimed plant ownership after legacy Paper publishes the item`() {
        val repository = PublicationAwareRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks, repository.leaseFactory)
        val plantItem = item(Material.SUGAR_CANE, y = 65)

        controller.onBlockBreak(BlockBreakEvent(block(64, Material.SUGAR_CANE), player()))
        controller.onPlantItemSpawn(ItemSpawnEvent(plantItem))

        assertEquals(OWNER_ID, repository.eventState?.ownership?.ownerUuid)
        repository.canonicalPublished = true
        delayedTasks.toList().forEach { it() }

        assertEquals(OWNER_ID, repository.canonicalState?.ownership?.ownerUuid)
        assertFalse(repository.leaseActive)
    }

    @Test
    fun `plant reconciliation preserves the complete event-time state when canonical publication is stale`() {
        val repository = PublicationAwareRepository()
        repository.eventState = ItemState(null, elapsedLifetimeSeconds = 7, originalLifetimeSeconds = 300)
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks, repository.leaseFactory)
        val plantItem = item(Material.CHORUS_FRUIT, y = 65)
        val topology =
            mapOf(
                ChorusBlockPosition(10, 64, 20) to Material.CHORUS_PLANT,
                ChorusBlockPosition(10, 65, 20) to Material.CHORUS_PLANT,
            )

        controller.onBlockBreak(
            BlockBreakEvent(chorusBlock(ChorusBlockPosition(10, 64, 20), topology), player()),
        )
        controller.onPlantItemSpawn(ItemSpawnEvent(plantItem))
        repository.canonicalPublished = true
        delayedTasks.toList().forEach { it() }

        assertEquals(293L, repository.canonicalState?.remainingLifetimeSeconds)
        assertEquals(7L, repository.canonicalState?.elapsedLifetimeSeconds)
        assertEquals(OWNER_ID, repository.canonicalState?.ownership?.ownerUuid)
    }

    @Test
    fun `plant reconciliation reloads the canonical item only after the event target is missing`() {
        val repository = RecordingRepository(missingLoadsRemaining = 1)
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)
        val plantItem = item(Material.SUGAR_CANE, y = 65)

        controller.onBlockBreak(BlockBreakEvent(block(64, Material.SUGAR_CANE), player()))
        controller.onPlantItemSpawn(ItemSpawnEvent(plantItem))

        assertTrue(repository.states.isEmpty())
        assertTrue(delayedTasks.isNotEmpty())
        delayedTasks.toList().forEach { it() }

        assertEquals(2, repository.loadCount)
        assertEquals(
            OWNER_ID,
            repository.states
                .getValue(plantItem.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `attributes delayed sugar cane spawns to each adjacent column owner`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)
        val otherOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000030")

        controller.onBlockBreak(BlockBreakEvent(block(64, Material.SUGAR_CANE, x = 10), player(OWNER_ID)))
        controller.onBlockBreak(BlockBreakEvent(block(64, Material.SUGAR_CANE, x = 11), player(otherOwnerId)))
        val firstItems = (64..66).map { y -> item(Material.SUGAR_CANE, y, x = 10) }
        val secondItems = (64..66).map { y -> item(Material.SUGAR_CANE, y, x = 11) }
        secondItems.forEach { controller.onPlantItemSpawn(ItemSpawnEvent(it)) }
        firstItems.forEach { controller.onPlantItemSpawn(ItemSpawnEvent(it)) }
        delayedTasks.toList().forEach { it() }

        assertTrue(
            firstItems.all {
                repository.states
                    .getValue(it.uniqueId)
                    .ownership
                    ?.ownerUuid == OWNER_ID
            },
        )
        assertTrue(
            secondItems.all {
                repository.states
                    .getValue(it.uniqueId)
                    .ownership
                    ?.ownerUuid == otherOwnerId
            },
        )
    }

    @Test
    fun `attributes delayed bamboo spawns to each adjacent column owner`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)
        val otherOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000030")

        controller.onBlockBreak(BlockBreakEvent(block(64, Material.BAMBOO, x = 10), player(OWNER_ID)))
        controller.onBlockBreak(BlockBreakEvent(block(64, Material.BAMBOO, x = 11), player(otherOwnerId)))
        val firstItems = (64..66).map { y -> item(Material.BAMBOO, y, x = 10) }
        val secondItems = (64..66).map { y -> item(Material.BAMBOO, y, x = 11) }
        secondItems.forEach { controller.onPlantItemSpawn(ItemSpawnEvent(it)) }
        firstItems.forEach { controller.onPlantItemSpawn(ItemSpawnEvent(it)) }
        delayedTasks.toList().forEach { it() }

        assertTrue(
            firstItems.all {
                repository.states
                    .getValue(it.uniqueId)
                    .ownership
                    ?.ownerUuid == OWNER_ID
            },
        )
        assertTrue(
            secondItems.all {
                repository.states
                    .getValue(it.uniqueId)
                    .ownership
                    ?.ownerUuid == otherOwnerId
            },
        )
    }

    @Test
    fun `attributes kelp body drops through the version-neutral vertical tracker`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)
        val items = (64..66).map { y -> item(Material.KELP, y) }

        controller.onBlockBreak(BlockBreakEvent(block(64, Material.KELP), player()))
        items.reversed().forEach { controller.onPlantItemSpawn(ItemSpawnEvent(it)) }
        delayedTasks.toList().forEach { it() }

        assertTrue(
            items.all { item ->
                repository.states
                    .getValue(item.uniqueId)
                    .ownership
                    ?.ownerUuid == OWNER_ID
            },
        )
    }

    @Test
    fun `attributes directly broken scaffolding through the block drop event`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)
        val item = item(Material.SCAFFOLDING, 64)

        controller.onBlockDrop(
            BlockDropItemEvent(block(64, Material.SCAFFOLDING), proxy(), player(), listOf(item)),
        )
        delayedTasks.toList().forEach { it() }

        assertEquals(
            OWNER_ID,
            repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `attributes bamboo above the former fixed 256 block scan limit`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)
        val tallColumn = 64..364
        val topItem = item(Material.BAMBOO, y = tallColumn.last)

        controller.onBlockBreak(
            BlockBreakEvent(
                block(
                    y = tallColumn.first,
                    material = Material.BAMBOO,
                    columnRange = tallColumn,
                    worldMaxHeight = 512,
                ),
                player(),
            ),
        )
        controller.onPlantItemSpawn(ItemSpawnEvent(topItem))
        delayedTasks.toList().forEach { it() }

        assertEquals(
            OWNER_ID,
            repository.states
                .getValue(topItem.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `attributes random chorus fruit to each disconnected topology owner`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)
        val otherOwnerId = UUID.fromString("00000000-0000-0000-0000-000000000030")
        val topology =
            mapOf(
                ChorusBlockPosition(10, 64, 20) to Material.CHORUS_PLANT,
                ChorusBlockPosition(10, 65, 20) to Material.CHORUS_PLANT,
                ChorusBlockPosition(11, 65, 20) to Material.CHORUS_FLOWER,
                ChorusBlockPosition(13, 64, 20) to Material.CHORUS_PLANT,
                ChorusBlockPosition(13, 65, 20) to Material.CHORUS_PLANT,
                ChorusBlockPosition(14, 65, 20) to Material.CHORUS_FLOWER,
            )

        controller.onBlockBreak(
            BlockBreakEvent(chorusBlock(ChorusBlockPosition(10, 64, 20), topology), player(OWNER_ID)),
        )
        controller.onBlockBreak(
            BlockBreakEvent(chorusBlock(ChorusBlockPosition(13, 64, 20), topology), player(otherOwnerId)),
        )
        val firstFruit = item(Material.CHORUS_FRUIT, y = 65, x = 10)
        val secondFruit = item(Material.CHORUS_FRUIT, y = 65, x = 13)
        controller.onPlantItemSpawn(ItemSpawnEvent(secondFruit))
        controller.onPlantItemSpawn(ItemSpawnEvent(firstFruit))
        delayedTasks.toList().forEach { it() }

        assertEquals(
            OWNER_ID,
            repository.states
                .getValue(firstFruit.uniqueId)
                .ownership
                ?.ownerUuid,
        )
        assertEquals(
            otherOwnerId,
            repository.states
                .getValue(secondFruit.uniqueId)
                .ownership
                ?.ownerUuid,
        )
    }

    @Test
    fun `does not attribute natural chorus fruit without a player topology context`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)

        controller.onPlantItemSpawn(ItemSpawnEvent(item(Material.CHORUS_FRUIT, y = 65, x = 10)))
        delayedTasks.toList().forEach { it() }

        assertTrue(repository.states.isEmpty())
    }

    @Test
    fun `does not attribute natural or expired sugar cane spawns`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)

        controller.onPlantItemSpawn(ItemSpawnEvent(item(Material.SUGAR_CANE, y = 64)))
        controller.onBlockBreak(BlockBreakEvent(block(64, Material.SUGAR_CANE), player()))
        delayedTasks.removeFirst().invoke()
        controller.onPlantItemSpawn(ItemSpawnEvent(item(Material.SUGAR_CANE, y = 65)))
        delayedTasks.forEach { it() }

        assertTrue(repository.states.isEmpty())
    }

    @Test
    fun `ordinary drop assignment does not schedule a UUID lookup retry`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)
        val item = item(Material.STONE, y = 64)

        controller.onBlockDrop(
            BlockDropItemEvent(block(64, Material.STONE), proxy(), player(), listOf(item)),
        )
        assertEquals(
            OWNER_ID,
            repository.states
                .getValue(item.uniqueId)
                .ownership
                ?.ownerUuid,
        )
        assertTrue(delayedTasks.isEmpty())
    }

    @Test
    fun `leases a live block drop only for the synchronous assignment`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        var leasesAcquired = 0
        var leasesReleased = 0
        val controller =
            controller(
                repository,
                delayedTasks,
                TransientItemTargetLeaseFactory {
                    leasesAcquired += 1
                    TransientItemTargetLease { leasesReleased += 1 }
                },
            )
        val pendingItem = item(Material.BAMBOO, y = 65)

        controller.onBlockDrop(
            BlockDropItemEvent(block(64, Material.BAMBOO), proxy(), player(), listOf(pendingItem)),
        )
        assertEquals(1, leasesAcquired)
        assertEquals(
            OWNER_ID,
            repository.states
                .getValue(pendingItem.uniqueId)
                .ownership
                ?.ownerUuid,
        )
        assertEquals(1, leasesReleased)
        assertTrue(delayedTasks.isEmpty())
    }

    @Test
    fun `ordinary ownership is complete before native merge can run`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)
        val pendingItem = item(Material.BAMBOO, y = 64)
        val otherItem = item(Material.BAMBOO, y = 64)

        controller.onBlockDrop(
            BlockDropItemEvent(block(64, Material.BAMBOO), proxy(), player(), listOf(pendingItem)),
        )
        val completedMerge = ItemMergeEvent(pendingItem, otherItem)
        controller.onPendingOwnershipMerge(completedMerge)
        assertFalse(completedMerge.isCancelled)
        assertEquals(
            OWNER_ID,
            repository.states
                .getValue(pendingItem.uniqueId)
                .ownership
                ?.ownerUuid,
        )
        assertTrue(delayedTasks.isEmpty())
    }

    @Test
    fun `blocks native merge for claimed plant items until collapse context expires`() {
        val repository = RecordingRepository()
        val delayedTasks = mutableListOf<() -> Unit>()
        val controller = controller(repository, delayedTasks)
        val claimedItem = item(Material.BAMBOO, y = 64)
        val otherItem = item(Material.BAMBOO, y = 64)

        controller.onBlockBreak(BlockBreakEvent(block(64, Material.BAMBOO), player()))
        controller.onPlantItemSpawn(ItemSpawnEvent(claimedItem))
        val afterAssignment = ItemMergeEvent(claimedItem, otherItem)
        controller.onPendingOwnershipMerge(afterAssignment)
        assertTrue(afterAssignment.isCancelled)

        delayedTasks.first().invoke()
        val afterExpiry = ItemMergeEvent(claimedItem, otherItem)
        controller.onPendingOwnershipMerge(afterExpiry)
        assertFalse(afterExpiry.isCancelled)
    }

    private fun controller(
        repository: ItemStateRepository,
        delayedTasks: MutableList<() -> Unit> = mutableListOf(),
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
            TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    ): BukkitBlockDropOwnershipController {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        val settings = ItemDisplaySettings(true, emptySet(), template, template)
        return BukkitBlockDropOwnershipController(
            service =
                BlockDropOwnershipService(
                    repository,
                    ItemDisplaySettingsRepository { settings },
                ),
            delayedTaskExecutor = DelayedMainThreadTaskExecutor { _, task -> delayedTasks += task },
            warningSink = DisplayWarningSink { error("unexpected warning: $it") },
            itemRefresh = ItemOwnershipRefresh {},
            transientTargetLeaseFactory = transientTargetLeaseFactory,
        )
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

    private fun block(
        y: Int,
        material: Material,
        x: Int = 10,
        columnRange: IntRange = 64..66,
        worldMaxHeight: Int = 320,
    ): Block =
        proxy { method, args ->
            when (method.name) {
                "getType" -> if (y in columnRange) material else Material.AIR
                "getX" -> x
                "getY" -> y
                "getZ" -> 20
                "getWorld" -> world(worldMaxHeight)
                "getRelative" ->
                    block(
                        y = y + (args?.get(1) as Int),
                        material = material,
                        x = x,
                        columnRange = columnRange,
                        worldMaxHeight = worldMaxHeight,
                    )
                else -> defaultValue(method.returnType)
            }
        }

    private fun item(
        material: Material,
        y: Int,
        x: Int = 10,
    ): Item {
        val id = UUID.randomUUID()
        return proxy { method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                "getWorld" -> world()
                "getLocation" -> Location(world(), x + 0.5, y + 0.2, 20.5)
                "getItemStack" -> ItemStack(material)
                else -> defaultValue(method.returnType)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun chorusBlock(
        position: ChorusBlockPosition,
        blocks: Map<ChorusBlockPosition, Material>,
    ): Block {
        lateinit var chorusWorld: World
        chorusWorld =
            proxy { method, _ ->
                when (method.name) {
                    "getUID" -> WORLD_ID
                    "getName" -> "world"
                    "getMaxHeight" -> 320
                    "isChunkLoaded" -> true
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
                    "getWorld" -> chorusWorld
                    "getRelative" ->
                        at(
                            ChorusBlockPosition(
                                x = current.x + (args?.get(0) as Int),
                                y = current.y + (args[1] as Int),
                                z = current.z + (args[2] as Int),
                            ),
                        )
                    else -> defaultValue(method.returnType)
                }
            }
        return at(position)
    }

    private fun world(maxHeight: Int = 320): World =
        proxy { method, _ ->
            when (method.name) {
                "getUID" -> WORLD_ID
                "getName" -> "world"
                "getMaxHeight" -> maxHeight
                else -> defaultValue(method.returnType)
            }
        }

    private fun player(ownerUuid: UUID = OWNER_ID): Player =
        proxy { method, _ -> if (method.name == "getUniqueId") ownerUuid else defaultValue(method.returnType) }

    private inline fun <reified T> proxy(
        crossinline answer: (Method, Array<out Any?>?) -> Any? = { method, _ -> defaultValue(method.returnType) },
    ): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method, args)
        } as T

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

    private companion object {
        private val WORLD_ID = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020")

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
