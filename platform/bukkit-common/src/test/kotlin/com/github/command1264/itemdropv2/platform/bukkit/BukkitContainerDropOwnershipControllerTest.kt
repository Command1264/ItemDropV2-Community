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
import org.bukkit.block.Block
import org.bukkit.block.Chest
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.util.Vector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitContainerDropOwnershipControllerTest {
    @Test
    fun `container drop plan stays inside the block and adds bounded upward motion`() {
        val planner =
            VanillaLikeContainerDropSpawnPlanner(
                nextDouble = sequenceRandom(0.0, 0.5, 0.999, 1.0, 0.0, 0.0, 1.0, 0.5, 0.5),
            )

        val plan = planner.next()

        assertEquals(0.125, plan.offsetX)
        assertEquals(0.5, plan.offsetY)
        assertEquals(0.87425, plan.offsetZ, 0.000001)
        assertEquals(0.05, plan.velocityX)
        assertEquals(0.15, plan.velocityY, 0.000001)
        assertEquals(0.0, plan.velocityZ)
    }

    @Test
    fun `applies one independent spawn plan to each nonempty local slot`() {
        val firstPlan = ContainerDropSpawnPlan(0.2, 0.3, 0.4, 0.01, 0.2, -0.01)
        val secondPlan = ContainerDropSpawnPlan(0.6, 0.7, 0.8, -0.02, 0.25, 0.02)
        val plans = arrayOf(firstPlan, secondPlan).iterator()
        val target = FakeContainerDropTarget(arrayOf(ItemStack(Material.STONE, 64), null, ItemStack(Material.DIAMOND, 3)))
        val fixture = fixture(target, spawnPlanner = ContainerDropSpawnPlanner { plans.next() })

        fixture.controller.onBlockBreak(BlockBreakEvent(block(Material.CHEST), player()))

        assertEquals(listOf(firstPlan, secondPlan), target.spawnPlans)
        assertEquals(listOf(64, 3), target.spawned.map { it.itemStack.amount })
        assertEquals(
            listOf(
                Vector(firstPlan.velocityX, firstPlan.velocityY, firstPlan.velocityZ),
                Vector(secondPlan.velocityX, secondPlan.velocityY, secondPlan.velocityZ),
            ),
            target.spawned.map { it.velocity },
        )
    }

    @Test
    fun `replaces each nonempty local slot with exactly one owned item entity`() {
        val first = ItemStack(Material.STONE, 64)
        val second = ItemStack(Material.STONE, 32)
        val target = FakeContainerDropTarget(arrayOf(first, null, second))
        val fixture = fixture(target)

        fixture.controller.onBlockBreak(BlockBreakEvent(block(Material.CHEST), player()))

        assertEquals(listOf(64, 32), target.spawned.map { it.itemStack.amount })
        assertTrue(target.contents.all { it == null })
        assertEquals(
            setOf(OWNER_ID),
            target.spawned
                .mapNotNull {
                    fixture.repository.states[it.uniqueId]
                        ?.ownership
                        ?.ownerUuid
                }.toSet(),
        )
    }

    @Test
    fun `does not replace cancelled or disabled block drops`() {
        val cancelledTarget = FakeContainerDropTarget(arrayOf(ItemStack(Material.DIAMOND, 4)))
        val cancelledFixture = fixture(cancelledTarget)
        val cancelled = BlockBreakEvent(block(Material.HOPPER), player()).apply { isCancelled = true }

        cancelledFixture.controller.onBlockBreak(cancelled)

        val disabledTarget = FakeContainerDropTarget(arrayOf(ItemStack(Material.DIAMOND, 4)))
        val disabledFixture = fixture(disabledTarget)
        val disabled = BlockBreakEvent(block(Material.HOPPER), player()).apply { isDropItems = false }
        disabledFixture.controller.onBlockBreak(disabled)

        assertEquals(4, cancelledTarget.contents.single()?.amount)
        assertTrue(cancelledTarget.spawned.isEmpty())
        assertEquals(4, disabledTarget.contents.single()?.amount)
        assertTrue(disabledTarget.spawned.isEmpty())
    }

    @Test
    fun `restores cloned inventory and removes partial replacements when spawning fails`() {
        val target =
            FakeContainerDropTarget(
                arrayOf(ItemStack(Material.STONE, 12), ItemStack(Material.DIAMOND, 3)),
                failAtSpawn = 2,
            )
        val warnings = mutableListOf<String>()
        val fixture = fixture(target, warnings)

        fixture.controller.onBlockBreak(BlockBreakEvent(block(Material.BARREL), player()))

        assertEquals(listOf(12, 3), target.contents.map { it?.amount })
        assertTrue(target.spawned.single().removed)
        assertTrue(fixture.repository.states.isEmpty())
        assertTrue(warnings.single().contains("rolled back"))
    }

    @Test
    fun `container material policy excludes preserving and special containers`() {
        assertTrue(BukkitContainerDropOwnershipController.supportsReplacement("CHEST"))
        assertTrue(BukkitContainerDropOwnershipController.supportsReplacement("HOPPER"))
        assertTrue(BukkitContainerDropOwnershipController.supportsReplacement("CHISELED_BOOKSHELF"))
        assertTrue(BukkitContainerDropOwnershipController.supportsReplacement("CRAFTER"))
        assertTrue(!BukkitContainerDropOwnershipController.supportsReplacement("ENDER_CHEST"))
        assertTrue(!BukkitContainerDropOwnershipController.supportsReplacement("SHULKER_BOX"))
        assertTrue(!BukkitContainerDropOwnershipController.supportsReplacement("DECORATED_POT"))
    }

    @Test
    fun `bukkit resolver reads only the broken half of a double chest`() {
        val localContents = arrayOf<ItemStack?>(ItemStack(Material.DIAMOND, 7))
        val combinedContents =
            arrayOf<ItemStack?>(ItemStack(Material.DIAMOND, 7), ItemStack(Material.GOLD_INGOT, 9))
        val localInventory = inventory(localContents)
        val combinedInventory = inventory(combinedContents)
        val chest: Chest =
            proxy { method, _ ->
                when (method.name) {
                    "getBlockInventory" -> localInventory
                    "getInventory" -> combinedInventory
                    else -> defaultValue(method.returnType)
                }
            }
        val chestBlock: Block =
            proxy { method, _ ->
                when (method.name) {
                    "getState" -> chest
                    else -> defaultValue(method.returnType)
                }
            }

        val target = requireNotNull(BukkitContainerDropTargetResolver.resolve(chestBlock))

        assertEquals(listOf(7), target.snapshot().map { it?.amount })
    }

    @Test
    fun `bukkit target uses the planned location without natural drop offset`() {
        val spawnedLocations = mutableListOf<Location>()
        val invokedMethods = mutableListOf<String>()
        val droppedItem = FakeItem(ItemStack(Material.STONE)).entity
        val targetWorld: org.bukkit.World =
            proxy { method, args ->
                when (method.name) {
                    "dropItem", "dropItemNaturally" -> {
                        invokedMethods += method.name
                        spawnedLocations += (args?.get(0) as Location).clone()
                        droppedItem
                    }
                    else -> defaultValue(method.returnType)
                }
            }
        val localInventory = inventory(arrayOf(ItemStack(Material.STONE)))
        val chest: Chest =
            proxy { method, _ ->
                when (method.name) {
                    "getBlockInventory" -> localInventory
                    else -> defaultValue(method.returnType)
                }
            }
        val chestBlock: Block =
            proxy { method, _ ->
                when (method.name) {
                    "getState" -> chest
                    "getWorld" -> targetWorld
                    "getLocation" -> Location(targetWorld, 10.0, 64.0, -3.0)
                    else -> defaultValue(method.returnType)
                }
            }
        val target = requireNotNull(BukkitContainerDropTargetResolver.resolve(chestBlock))

        target.spawn(
            ItemStack(Material.STONE),
            ContainerDropSpawnPlan(0.2, 0.3, 0.4, 0.0, 0.2, 0.0),
        )

        assertEquals(listOf("dropItem"), invokedMethods)
        assertEquals(10.2, spawnedLocations.single().x)
        assertEquals(64.3, spawnedLocations.single().y)
        assertEquals(-2.6, spawnedLocations.single().z)
    }

    @Test
    fun `writes replacement item ownership before block break callback returns`() {
        val target =
            FakeContainerDropTarget(
                arrayOf(ItemStack(Material.STONE, 2), ItemStack(Material.STONE, 3)),
            )
        val fixture = fixture(target)
        fixture.controller.onBlockBreak(BlockBreakEvent(block(Material.CHEST), player()))
        assertEquals(2, fixture.repository.states.size)
    }

    @Test
    fun `uses the canonical spawn event item instead of the legacy api wrapper`() {
        val capture = BukkitCanonicalItemSpawnCapture(primaryThreadCheck = { true })
        val leasedItems = mutableListOf<Item>()
        val target = FakeContainerDropTarget(arrayOf(ItemStack(Material.STONE, 64)), capture = capture)
        val fixture =
            fixture(
                target,
                canonicalItemSpawnCapture = capture,
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory { item ->
                        leasedItems += item
                        TransientItemTargetLease {}
                    },
            )

        fixture.controller.onBlockBreak(BlockBreakEvent(block(Material.CHEST), player()))

        assertSame(target.canonicalSpawned.single().entity, leasedItems.single())
        assertEquals(
            64,
            target.canonicalSpawned
                .single()
                .itemStack.amount,
        )
    }

    @Test
    fun `publishes container ownership and presentation during the spawn event`() {
        val capture = BukkitCanonicalItemSpawnCapture(primaryThreadCheck = { true })
        val refreshedEntityIds = mutableListOf<UUID>()
        val target = FakeContainerDropTarget(arrayOf(ItemStack(Material.STONE, 4)), capture = capture)
        lateinit var fixture: Fixture
        target.afterSpawnEvent = { item ->
            assertEquals(
                OWNER_ID,
                fixture.repository.states[item.uniqueId]
                    ?.ownership
                    ?.ownerUuid,
            )
            assertTrue(item.uniqueId in refreshedEntityIds)
        }
        fixture =
            fixture(
                target,
                canonicalItemSpawnCapture = capture,
                itemRefresh = ItemOwnershipRefresh(refreshedEntityIds::add),
            )

        fixture.controller.onBlockBreak(BlockBreakEvent(block(Material.CHEST), player()))

        assertEquals(listOf(target.canonicalSpawned.single().uniqueId), refreshedEntityIds)
    }

    @Test
    fun `does not replace contents in a blocked world`() {
        val target = FakeContainerDropTarget(arrayOf(ItemStack(Material.STONE, 8)))
        val fixture = fixture(target, blockedWorlds = setOf("world"))

        fixture.controller.onBlockBreak(BlockBreakEvent(block(Material.CHEST), player()))

        assertEquals(8, target.contents.single()?.amount)
        assertTrue(target.spawned.isEmpty())
    }

    private fun fixture(
        target: FakeContainerDropTarget,
        warnings: MutableList<String> = mutableListOf(),
        blockedWorlds: Set<String> = emptySet(),
        spawnPlanner: ContainerDropSpawnPlanner = VanillaLikeContainerDropSpawnPlanner(),
        canonicalItemSpawnCapture: CanonicalItemSpawnCapture = DirectItemSpawnCapture,
        itemRefresh: ItemOwnershipRefresh = ItemOwnershipRefresh {},
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
            TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    ): Fixture {
        val repository = RecordingRepository()
        val template =
            (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        val settings = ItemDisplaySettings(true, blockedWorlds, template, template)
        return Fixture(
            controller =
                BukkitContainerDropOwnershipController(
                    service =
                        BlockDropOwnershipService(
                            repository,
                            ItemDisplaySettingsRepository { settings },
                        ),
                    settingsRepository = ItemDisplaySettingsRepository { settings },
                    warningSink = DisplayWarningSink(warnings::add),
                    itemRefresh = itemRefresh,
                    transientTargetLeaseFactory = transientTargetLeaseFactory,
                    itemStateCleaner =
                        ContainerDropItemStateCleaner { item ->
                            repository.states.remove(item.uniqueId)
                            ItemStateWriteResult.Applied
                        },
                    targetResolver = ContainerDropTargetResolver { target },
                    canonicalItemSpawnCapture = canonicalItemSpawnCapture,
                    spawnPlanner = spawnPlanner,
                ),
            repository = repository,
        )
    }

    private fun block(material: Material): Block =
        proxy { method, _ ->
            when (method.name) {
                "getType" -> material
                "getWorld" -> world()
                else -> defaultValue(method.returnType)
            }
        }

    private fun player(): Player =
        proxy { method, _ ->
            when (method.name) {
                "getUniqueId" -> OWNER_ID
                else -> defaultValue(method.returnType)
            }
        }

    private inline fun <reified T> proxy(crossinline answer: (Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method, args)
        } as T

    private fun world(): org.bukkit.World =
        proxy { method, _ ->
            when (method.name) {
                "getName" -> "world"
                else -> defaultValue(method.returnType)
            }
        }

    private fun inventory(contents: Array<ItemStack?>): Inventory =
        proxy { method, _ ->
            when (method.name) {
                "getContents" -> contents
                else -> defaultValue(method.returnType)
            }
        }

    private data class Fixture(
        val controller: BukkitContainerDropOwnershipController,
        val repository: RecordingRepository,
    )

    private inner class FakeContainerDropTarget(
        initialContents: Array<ItemStack?>,
        private val failAtSpawn: Int? = null,
        private val capture: BukkitCanonicalItemSpawnCapture? = null,
    ) : ContainerDropTarget {
        var contents: Array<ItemStack?> = initialContents.map { it?.clone() }.toTypedArray()
        val spawned = mutableListOf<FakeItem>()
        val canonicalSpawned = mutableListOf<FakeItem>()
        val spawnPlans = mutableListOf<ContainerDropSpawnPlan>()
        var afterSpawnEvent: ((Item) -> Unit)? = null

        override fun snapshot(): Array<ItemStack?> = contents.map { it?.clone() }.toTypedArray()

        override fun clear() {
            contents = arrayOfNulls(contents.size)
        }

        override fun restore(snapshot: Array<ItemStack?>) {
            contents = snapshot.map { it?.clone() }.toTypedArray()
        }

        override fun spawn(
            itemStack: ItemStack,
            plan: ContainerDropSpawnPlan,
        ): Item {
            if (spawned.size + 1 == failAtSpawn) error("synthetic spawn failure")
            spawnPlans += plan
            val returned = FakeItem(itemStack.clone()).also(spawned::add)
            capture?.let { eventCapture ->
                val canonical = FakeItem(itemStack.clone(), returned.uniqueId).also(canonicalSpawned::add)
                eventCapture.onItemSpawn(ItemSpawnEvent(canonical.entity))
                afterSpawnEvent?.invoke(canonical.entity)
            }
            return returned.entity
        }
    }

    private inner class FakeItem(
        val itemStack: ItemStack,
        val uniqueId: UUID = UUID.randomUUID(),
    ) {
        var removed: Boolean = false
        var velocity: Vector = Vector()
        val entity: Item =
            proxy { method, args ->
                when (method.name) {
                    "getUniqueId" -> uniqueId
                    "getWorld" -> world()
                    "getItemStack" -> itemStack
                    "getVelocity" -> velocity.clone()
                    "setVelocity" -> {
                        velocity = (args?.get(0) as Vector).clone()
                        null
                    }
                    "remove" -> {
                        removed = true
                        null
                    }
                    else -> defaultValue(method.returnType)
                }
            }
    }

    private class RecordingRepository : ItemStateRepository {
        val states = mutableMapOf<UUID, ItemState>()

        override fun load(entityId: UUID): ItemStateLoadResult =
            states[entityId]?.let(ItemStateLoadResult::Loaded) ?: ItemStateLoadResult.Absent

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            states[entityId] = state
            return ItemStateWriteResult.Applied
        }
    }

    private companion object {
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000020")

        private fun sequenceRandom(vararg values: Double): () -> Double {
            val iterator = values.iterator()
            return { iterator.nextDouble() }
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
