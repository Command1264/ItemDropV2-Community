package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.CreativeNoCapacityPickupMode
import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemOwnershipSettings
import com.github.command1264.itemdropv2.core.ItemPickupSettings
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import com.github.command1264.itemdropv2.core.VirtualCarrierAmountMode
import com.github.command1264.itemdropv2.core.VirtualItemAmount
import com.github.command1264.itemdropv2.core.VirtualItemStackingSettings
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

@Suppress("LargeClass")
class BukkitVirtualItemPickupTransactionTest {
    @Test
    fun `player transaction inserts all legal chunks and removes carrier`() {
        val repository = RecordingRepository(100)
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(stack(Material.STONE, 60), null, null))
        var cleaned = 0
        val transaction =
            transaction(repository) {
                cleaned += 1
                ItemStateWriteResult.Applied
            }

        val outcome = transaction.pickupByPlayer(carrier.item, player(inventory.inventory))

        val inserted = assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(100L, inserted.insertedAmount)
        assertEquals(0L, inserted.remainingAmount)
        assertEquals(listOf(64, 64, 32), inventory.amounts())
        assertTrue(carrier.removed)
        assertEquals(1, cleaned)
        assertEquals(emptyList<ItemState>(), repository.saved)
    }

    @Test
    fun `player pickup callback runs after persistence and before carrier removal`() {
        val repository = RecordingRepository(100)
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(null, null))
        val observations = mutableListOf<String>()

        val outcome =
            transaction(repository).pickupByPlayer(carrier.item, player(inventory.inventory)) {
                consumedAmount,
                remainingAmount,
                ->
                observations +=
                    "amount=$consumedAmount,remaining=$remainingAmount,removed=${carrier.removed}," +
                    "inventory=${inventory.amounts().filterNotNull().sum()}"
            }

        assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(listOf("amount=100,remaining=0,removed=false,inventory=100"), observations)
        assertTrue(carrier.removed)
    }

    @Test
    fun `partial player pickup callback reports the persisted remainder before carrier mutation`() {
        val repository = RecordingRepository(100)
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(null))
        val observations = mutableListOf<String>()

        val outcome =
            transaction(repository).pickupByPlayer(carrier.item, player(inventory.inventory)) {
                consumedAmount,
                remainingAmount,
                ->
                observations +=
                    "amount=$consumedAmount,remaining=$remainingAmount,removed=${carrier.removed}," +
                    "carrier=${carrier.stack.amount},inventory=${inventory.amounts().filterNotNull().sum()}"
            }

        val inserted = assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(64L, inserted.insertedAmount)
        assertEquals(36L, inserted.remainingAmount)
        assertEquals(
            listOf("amount=64,remaining=36,removed=false,carrier=1,inventory=64"),
            observations,
        )
        assertEquals(false, carrier.removed)
    }

    @Test
    fun `hopper transaction moves only one native batch and persists remainder`() {
        val repository = RecordingRepository(8_192)
        val carrier = item(stack(Material.STONE, 64))
        val inventory = inventory(arrayOf(null, null))

        val outcome = transaction(repository).pickupByInventory(carrier.item, inventory.inventory)

        val inserted = assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(64L, inserted.insertedAmount)
        assertEquals(8_128L, inserted.remainingAmount)
        assertEquals(listOf(64, null), inventory.amounts())
        assertEquals(
            8_128L,
            repository.saved
                .single()
                .virtualAmount
                ?.value,
        )
        assertEquals(false, carrier.removed)
        assertEquals(64, carrier.stack.amount)
    }

    @Test
    fun `unstackable player pickup fills one item per slot and preserves remainder`() {
        val repository = RecordingRepository(3)
        val carrier = item(stack(Material.DIAMOND_SWORD))
        val inventory = inventory(arrayOf(null, null))

        val outcome =
            transaction(
                repository,
                nativeStacks = 128,
                unstackableItemsEnabled = true,
            ).pickupByPlayer(carrier.item, player(inventory.inventory))

        val inserted = assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(2L, inserted.insertedAmount)
        assertEquals(1L, inserted.remainingAmount)
        assertEquals(listOf(1, 1), inventory.amounts())
        assertEquals(
            1L,
            repository.saved
                .single()
                .virtualAmount
                ?.value,
        )
        assertEquals(false, carrier.removed)
        assertEquals(1, carrier.stack.amount)
    }

    @Test
    fun `count capped unstackable carrier still inserts only legal native stacks`() {
        val repository = RecordingRepository(128)
        val carrier = item(stack(Material.DIAMOND_SWORD, 64))
        val inventory = inventory(arrayOf(null))

        val outcome =
            transaction(
                repository,
                nativeStacks = 128,
                unstackableItemsEnabled = true,
                carrierAmountMode = VirtualCarrierAmountMode.COUNT_CAPPED,
            ).pickupByPlayer(carrier.item, player(inventory.inventory))

        val inserted = assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(1L, inserted.insertedAmount)
        assertEquals(127L, inserted.remainingAmount)
        assertEquals(listOf(1), inventory.amounts())
        assertEquals(
            127L,
            repository.saved
                .single()
                .virtualAmount
                ?.value,
        )
        assertEquals(64, carrier.stack.amount)
    }

    @Test
    fun `unstackable hopper pickup moves one item per event`() {
        val repository = RecordingRepository(3)
        val carrier = item(stack(Material.DIAMOND_SWORD))
        val inventory = inventory(arrayOf(null, null))

        val outcome =
            transaction(
                repository,
                nativeStacks = 128,
                unstackableItemsEnabled = true,
            ).pickupByInventory(carrier.item, inventory.inventory)

        val inserted = assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(1L, inserted.insertedAmount)
        assertEquals(2L, inserted.remainingAmount)
        assertEquals(listOf(1, null), inventory.amounts())
        assertEquals(
            2L,
            repository.saved
                .single()
                .virtualAmount
                ?.value,
        )
    }

    @Test
    fun `full inventory leaves pdc and carrier unchanged`() {
        val repository = RecordingRepository(8)
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(stack(Material.STONE, 64), stack(Material.DIRT, 1)))

        val outcome = transaction(repository).pickupByPlayer(carrier.item, player(inventory.inventory))

        assertInstanceOf(VirtualItemPickupOutcome.NoCapacity::class.java, outcome)
        assertEquals(listOf(64, 1), inventory.amounts())
        assertEquals(emptyList<ItemState>(), repository.saved)
        assertEquals(false, carrier.removed)
    }

    @Test
    fun `creative no-capacity pickup consumes the carrier and discards overflow once`() {
        val repository = RecordingRepository(8_192)
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(stack(Material.STONE, 64), stack(Material.DIRT, 64)))

        val outcome =
            transaction(repository).pickupByPlayer(
                carrier.item,
                player(inventory.inventory, GameMode.CREATIVE),
            )

        val inserted = assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(0L, inserted.insertedAmount)
        assertEquals(0L, inserted.remainingAmount)
        assertEquals(8_192L, inserted.discardedAmount)
        assertEquals(listOf(64, 64), inventory.amounts())
        assertTrue(carrier.removed)
        assertEquals(emptyList<ItemState>(), repository.saved)
    }

    @Test
    fun `creative no-capacity collision fallback consumes the carrier without a pickup event`() {
        val repository = RecordingRepository(8_192)
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(stack(Material.STONE, 64), stack(Material.DIRT, 64)))

        val outcome =
            transaction(repository).pickupByCreativeNoCapacityCollision(
                carrier.item,
                player(inventory.inventory, GameMode.CREATIVE),
            )

        val inserted = assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(0L, inserted.insertedAmount)
        assertEquals(0L, inserted.remainingAmount)
        assertEquals(8_192L, inserted.discardedAmount)
        assertEquals(listOf(64, 64), inventory.amounts())
        assertTrue(carrier.removed)
    }

    @Test
    fun `creative collision fallback leaves partial capacity to the native pickup event`() {
        val repository = RecordingRepository(100)
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(stack(Material.STONE, 60), stack(Material.DIRT, 64)))

        val outcome =
            transaction(repository).pickupByCreativeNoCapacityCollision(
                carrier.item,
                player(inventory.inventory, GameMode.CREATIVE),
            )

        assertInstanceOf(VirtualItemPickupOutcome.NotApplicable::class.java, outcome)
        assertEquals(listOf(60, 64), inventory.amounts())
        assertFalse(carrier.removed)
        assertEquals(emptyList<ItemState>(), repository.saved)
    }

    @Test
    fun `creative collision fallback honors full inventory deny mode`() {
        val repository = RecordingRepository(8_192)
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(stack(Material.STONE, 64), stack(Material.DIRT, 64)))

        val outcome =
            transaction(
                repository,
                creativeNoCapacityPickupMode = CreativeNoCapacityPickupMode.DENY,
            ).pickupByCreativeNoCapacityCollision(
                carrier.item,
                player(inventory.inventory, GameMode.CREATIVE),
            )

        assertInstanceOf(VirtualItemPickupOutcome.NoCapacity::class.java, outcome)
        assertEquals(listOf(64, 64), inventory.amounts())
        assertFalse(carrier.removed)
        assertEquals(emptyList<ItemState>(), repository.saved)
    }

    @Test
    fun `creative no-capacity deny mode preserves carrier and pdc`() {
        val repository = RecordingRepository(8_192)
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(stack(Material.STONE, 64), stack(Material.DIRT, 64)))

        val outcome =
            transaction(
                repository,
                creativeNoCapacityPickupMode = CreativeNoCapacityPickupMode.DENY,
            ).pickupByPlayer(
                carrier.item,
                player(inventory.inventory, GameMode.CREATIVE),
            )

        assertInstanceOf(VirtualItemPickupOutcome.NoCapacity::class.java, outcome)
        assertEquals(listOf(64, 64), inventory.amounts())
        assertEquals(emptyList<ItemState>(), repository.saved)
        assertEquals(false, carrier.removed)
    }

    @Test
    fun `creative deny mode still inserts partial capacity and discards overflow`() {
        val repository = RecordingRepository(100)
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(stack(Material.STONE, 60), null))

        val outcome =
            transaction(
                repository,
                creativeNoCapacityPickupMode = CreativeNoCapacityPickupMode.DENY,
            ).pickupByPlayer(
                carrier.item,
                player(inventory.inventory, GameMode.CREATIVE),
            )

        val inserted = assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(68L, inserted.insertedAmount)
        assertEquals(0L, inserted.remainingAmount)
        assertEquals(32L, inserted.discardedAmount)
        assertEquals(listOf(64, 64), inventory.amounts())
        assertTrue(carrier.removed)
    }

    @Test
    fun `creative partial inventory inserts legal stacks and discards only the overflow`() {
        val repository = RecordingRepository(100)
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(stack(Material.STONE, 60), null))

        val outcome =
            transaction(repository).pickupByPlayer(
                carrier.item,
                player(inventory.inventory, GameMode.CREATIVE),
            )

        val inserted = assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(68L, inserted.insertedAmount)
        assertEquals(0L, inserted.remainingAmount)
        assertEquals(32L, inserted.discardedAmount)
        assertEquals(listOf(64, 64), inventory.amounts())
        assertTrue(carrier.removed)
    }

    @Test
    fun `failed pdc write rolls inventory back without removing carrier`() {
        val repository = RecordingRepository(100, ItemStateWriteResult.Failed("Storage"))
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(stack(Material.STONE, 60), null))

        val outcome = transaction(repository).pickupByPlayer(carrier.item, player(inventory.inventory))

        assertInstanceOf(VirtualItemPickupOutcome.Failed::class.java, outcome)
        assertEquals(listOf(60, null), inventory.amounts())
        assertEquals(false, carrier.removed)
    }

    @Test
    fun `inventory exception after mutation restores the complete slot snapshot`() {
        val repository = RecordingRepository(100)
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(stack(Material.STONE, 60), null), failFirstWrite = true)

        val outcome = transaction(repository).pickupByPlayer(carrier.item, player(inventory.inventory))

        assertInstanceOf(VirtualItemPickupOutcome.Failed::class.java, outcome)
        assertEquals(listOf(60, null), inventory.amounts())
        assertEquals(emptyList<ItemState>(), repository.saved)
        assertEquals(false, carrier.removed)
    }

    @Test
    fun `non-player virtual pickup splits excess and preserves the native carrier`() {
        val repository = RecordingRepository(8)
        val carrier = item(stack(Material.STONE))
        val spawned = mutableListOf<ItemFixture>()
        val virtual =
            transaction(
                repository,
                remainderSpawner = { stack ->
                    item(stack, REMAINDER_ENTITY_ID).also(spawned::add).item
                },
            ).pickupByNonPlayer(carrier.item, nativePickupRemaining = 0)

        val prepared = assertInstanceOf(VirtualItemPickupOutcome.NativePickupPrepared::class.java, virtual)
        assertEquals(ENTITY_ID, prepared.sourceEntityId)
        assertEquals(1, spawned.size)
        assertEquals(listOf(7L, 1L), repository.saved.map { requireNotNull(it.virtualAmount).value })
        assertEquals(false, carrier.removed)
        assertEquals(false, spawned.single().removed)

        val nativeRepository = RecordingRepository(null)
        val native = transaction(nativeRepository).pickupByNonPlayer(item(stack(Material.STONE)).item, 0)
        assertInstanceOf(VirtualItemPickupOutcome.NotVirtual::class.java, native)
    }

    @Test
    fun `legacy mob pickup creates exactly one replacement and does not churn during reconciliation`() {
        val repository = RecordingRepository(8_192)
        val carrier = item(stack(Material.STONE))
        lateinit var replacement: ItemFixture
        var spawnCalls = 0
        val transaction =
            transaction(
                repository,
                enabled = false,
                remainderSpawner = { stack ->
                    spawnCalls += 1
                    item(stack, REMAINDER_ENTITY_ID).also { replacement = it }.item
                },
                itemResolver = { entityId -> carrier.item.takeIf { entityId == ENTITY_ID } },
            )

        val outcome = transaction.pickupByNonPlayer(carrier.item, nativePickupRemaining = 0)
        repeat(40) { transaction.reconcileNonPlayerPickup(ENTITY_ID) }

        assertInstanceOf(VirtualItemPickupOutcome.NativePickupPrepared::class.java, outcome)
        assertEquals(1, spawnCalls)
        assertEquals(
            8_191L,
            repository.saved
                .first()
                .virtualAmount
                ?.value,
        )
        assertEquals(1L, repository.saved[1].virtualAmount?.value)
        assertEquals(8_192L, repository.saved.take(2).sumOf { requireNotNull(it.virtualAmount).value })
        assertEquals(1, replacement.stack.amount)
        assertFalse(replacement.removed)
    }

    @Test
    fun `non-player pickup trusts the direct event item before legacy Spigot marks it valid`() {
        val repository = RecordingRepository(8)
        val carrier = item(stack(Material.STONE), initiallyValid = false)
        val spawned = mutableListOf<ItemFixture>()

        val outcome =
            transaction(
                repository,
                remainderSpawner = { stack ->
                    item(stack, REMAINDER_ENTITY_ID).also(spawned::add).item
                },
            ).pickupByNonPlayer(carrier.item, nativePickupRemaining = 0)

        assertInstanceOf(VirtualItemPickupOutcome.NativePickupPrepared::class.java, outcome)
        assertEquals(listOf(7L, 1L), repository.saved.map { requireNotNull(it.virtualAmount).value })
        assertEquals(1, spawned.size)
    }

    @Test
    fun `non-player partial native pickup reconciles pdc to the physical remainder`() {
        val repository = RecordingRepository(8)
        val carrier = item(stack(Material.STONE, 4))
        val transaction =
            transaction(
                repository,
                itemResolver = { entityId -> carrier.item.takeIf { entityId == ENTITY_ID } },
            )

        val outcome = transaction.reconcileNonPlayerPickup(ENTITY_ID)

        val applied = assertInstanceOf(NonPlayerPickupReconciliationOutcome.Applied::class.java, outcome)
        assertSame(carrier.item, applied.item)
        assertEquals(
            4L,
            repository.saved
                .single()
                .virtualAmount
                ?.value,
        )
        assertEquals(1, carrier.stack.amount)
    }

    @Test
    fun `non-player pickup rejects an impossible native remainder without spawning`() {
        var spawned = false
        val outcome =
            transaction(
                RecordingRepository(8),
                remainderSpawner = {
                    spawned = true
                    item(it, REMAINDER_ENTITY_ID).item
                },
            ).pickupByNonPlayer(item(stack(Material.STONE)).item, nativePickupRemaining = 2)

        val rejected = assertInstanceOf(VirtualItemPickupOutcome.Rejected::class.java, outcome)
        assertEquals("InvalidNativePickupRemaining", rejected.reason)
        assertEquals(false, spawned)
    }

    @Test
    fun `failed remainder state write removes the split entity and preserves source`() {
        val repository = RecordingRepository(8, ItemStateWriteResult.Failed("Storage"))
        val carrier = item(stack(Material.STONE))
        lateinit var remainder: ItemFixture
        val outcome =
            transaction(
                repository,
                remainderSpawner = { stack ->
                    item(stack, REMAINDER_ENTITY_ID).also { remainder = it }.item
                },
            ).pickupByNonPlayer(carrier.item, nativePickupRemaining = 0)

        val failed = assertInstanceOf(VirtualItemPickupOutcome.Failed::class.java, outcome)
        assertEquals("Storage", failed.errorType)
        assertEquals(false, carrier.removed)
        assertEquals(true, remainder.removed)
        assertEquals(1, carrier.stack.amount)
    }

    @Test
    fun `replacement removal failure returns bounded rollback failure without respawning`() {
        val repository = RecordingRepository(8, ItemStateWriteResult.Failed("Storage"))
        var spawnCalls = 0
        val carrier = item(stack(Material.STONE))

        val outcome =
            transaction(
                repository,
                remainderSpawner = { stack ->
                    spawnCalls += 1
                    item(stack, REMAINDER_ENTITY_ID, failRemove = true).item
                },
            ).pickupByNonPlayer(carrier.item, nativePickupRemaining = 0)

        val failed = assertInstanceOf(VirtualItemPickupOutcome.Failed::class.java, outcome)
        assertEquals("NonPlayerPickupRollbackFailed", failed.errorType)
        assertEquals(1, spawnCalls)
    }

    @Test
    fun `second player cannot transact an entity removed by the first player`() {
        val repository = RecordingRepository(8)
        val carrier = item(stack(Material.STONE))
        val firstInventory = inventory(arrayOf(null))
        val secondInventory = inventory(arrayOf(null))
        val transaction = transaction(repository)

        val first = transaction.pickupByPlayer(carrier.item, player(firstInventory.inventory, playerId = FIRST_PLAYER_ID))
        val second = transaction.pickupByPlayer(carrier.item, player(secondInventory.inventory, playerId = SECOND_PLAYER_ID))

        assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, first)
        assertInstanceOf(VirtualItemPickupOutcome.Failed::class.java, second)
        assertEquals(listOf(8), firstInventory.amounts())
        assertEquals(listOf(null), secondInventory.amounts())
    }

    @Test
    fun `same player and item reentry is rejected before a second inventory mutation`() {
        val repository = RecordingRepository(8)
        val carrier = item(stack(Material.STONE))
        lateinit var transaction: BukkitVirtualItemPickupTransaction
        lateinit var player: Player
        var nestedOutcome: VirtualItemPickupOutcome? = null
        val inventory =
            inventory(arrayOf(null)) {
                nestedOutcome = transaction.pickupByPlayer(carrier.item, player)
            }
        player = player(inventory.inventory)
        transaction = transaction(repository)

        val outerOutcome = transaction.pickupByPlayer(carrier.item, player)

        assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outerOutcome)
        assertInstanceOf(VirtualItemPickupOutcome.Rejected::class.java, nestedOutcome)
        assertEquals(listOf(8), inventory.amounts())
        assertTrue(carrier.removed)
    }

    @Test
    fun `asynchronous player pickup fails before reading player or item identity`() {
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(null))

        val outcome =
            transaction(RecordingRepository(8), primaryThread = false)
                .pickupByPlayer(carrier.item, player(inventory.inventory))

        val failed = assertInstanceOf(VirtualItemPickupOutcome.Failed::class.java, outcome)
        assertEquals("AsyncBukkitAccess", failed.errorType)
        assertEquals(listOf(null), inventory.amounts())
        assertEquals(false, carrier.removed)
    }

    @Test
    fun `existing schema five carrier remains safe after feature is disabled`() {
        val repository = RecordingRepository(8)
        val carrier = item(stack(Material.STONE))
        val inventory = inventory(arrayOf(null))

        val outcome =
            transaction(repository, enabled = false).pickupByPlayer(
                carrier.item,
                player(inventory.inventory),
            )

        assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(listOf(8), inventory.amounts())
        assertEquals(true, carrier.removed)
    }

    @Test
    fun `disabled virtual stacking drains a partial pickup without respawning or expanding the carrier`() {
        val repository = RecordingRepository(100)
        val carrier = item(stack(Material.STONE, 64))
        val inventory = inventory(arrayOf(stack(Material.STONE, 24)))

        val outcome =
            transaction(repository, enabled = false).pickupByInventory(
                carrier.item,
                inventory.inventory,
            )

        val inserted = assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(40L, inserted.insertedAmount)
        assertEquals(60L, inserted.remainingAmount)
        assertEquals(listOf(64), inventory.amounts())
        assertEquals(
            60L,
            repository.saved
                .single()
                .virtualAmount
                ?.value,
        )
        assertEquals(60, carrier.stack.amount)
        assertFalse(carrier.removed)
    }

    @Test
    fun `carrier above a reduced maximum remains drainable`() {
        val repository = RecordingRepository(8_192)
        val carrier = item(stack(Material.STONE, 64))
        val inventory = inventory(arrayOf(null))

        val outcome =
            transaction(repository, maximumAmount = 128).pickupByInventory(
                carrier.item,
                inventory.inventory,
            )

        val inserted = assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals(64L, inserted.insertedAmount)
        assertEquals(8_128L, inserted.remainingAmount)
        assertEquals(
            8_128L,
            repository.saved
                .single()
                .virtualAmount
                ?.value,
        )
        assertEquals(64, carrier.stack.amount)
    }

    @Test
    fun `inventory insertion uses the item stack after pickup state cleanup`() {
        val repository = RecordingRepository(8)
        val carrier = item(TaggedItemStack(Material.STONE, 1, "dirty"))
        val inventory = inventory(arrayOf(null))

        val outcome =
            transaction(repository) { item ->
                item.setItemStack(TaggedItemStack(Material.STONE, 1, "clean"))
                ItemStateWriteResult.Applied
            }.pickupByPlayer(carrier.item, player(inventory.inventory))

        assertInstanceOf(VirtualItemPickupOutcome.Inserted::class.java, outcome)
        assertEquals("clean", (inventory.itemAt(0) as TaggedItemStack).tag)
    }

    private fun transaction(
        repository: ItemStateRepository,
        enabled: Boolean = true,
        primaryThread: Boolean = true,
        creativeNoCapacityPickupMode: CreativeNoCapacityPickupMode =
            CreativeNoCapacityPickupMode.DESTROY,
        nativeStacks: Long? = null,
        unstackableItemsEnabled: Boolean = false,
        carrierAmountMode: VirtualCarrierAmountMode = VirtualCarrierAmountMode.PROPORTIONAL,
        maximumAmount: Long = 8_192,
        remainderSpawner: (ItemStack) -> Item = { error("unexpected remainder spawn") },
        itemResolver: (UUID) -> Item? = { null },
        cleaner: (Item) -> ItemStateWriteResult = { ItemStateWriteResult.Applied },
    ): BukkitVirtualItemPickupTransaction =
        BukkitVirtualItemPickupTransaction(
            stateRepository = repository,
            settingsRepository =
                settings(
                    enabled,
                    creativeNoCapacityPickupMode,
                    nativeStacks,
                    unstackableItemsEnabled,
                    carrierAmountMode,
                    maximumAmount,
                ),
            pickedUpStateCleaner = cleaner,
            primaryThreadCheck = { primaryThread },
            remainderSpawner = { _, stack -> remainderSpawner(stack) },
            itemResolver = itemResolver,
        )

    private fun settings(
        enabled: Boolean,
        creativeNoCapacityPickupMode: CreativeNoCapacityPickupMode,
        nativeStacks: Long?,
        unstackableItemsEnabled: Boolean,
        carrierAmountMode: VirtualCarrierAmountMode,
        maximumAmount: Long,
    ): ItemDisplaySettingsRepository {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return ItemDisplaySettingsRepository {
            ItemDisplaySettings(
                enabled = true,
                blockedWorlds = emptySet(),
                singleItemTemplate = template,
                multipleItemTemplate = template,
                virtualStacking =
                    VirtualItemStackingSettings(
                        enabled = enabled,
                        maximumAmountPerEntity = VirtualItemAmount.of(maximumAmount),
                        maximumNativeStacksPerEntity = nativeStacks,
                        unstackableItemsEnabled = unstackableItemsEnabled,
                        carrierAmountMode = carrierAmountMode,
                    ),
                ownership =
                    ItemOwnershipSettings(
                        pickup =
                            ItemPickupSettings(
                                creativeNoCapacityPickupMode = creativeNoCapacityPickupMode,
                            ),
                    ),
            )
        }
    }

    private fun inventory(
        initial: Array<ItemStack?>,
        failFirstWrite: Boolean = false,
        beforeFirstWrite: () -> Unit = {},
    ): InventoryFixture {
        var contents = initial.map { it?.clone() }.toTypedArray()
        var writes = 0
        val inventory =
            Proxy.newProxyInstance(
                PlayerInventory::class.java.classLoader,
                arrayOf(PlayerInventory::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "getStorageContents" -> contents.map { it?.clone() }.toTypedArray()
                    "setStorageContents" -> {
                        if (writes == 0) beforeFirstWrite()
                        contents = (arguments.orEmpty().single() as Array<*>).map { (it as ItemStack?)?.clone() }.toTypedArray()
                        writes += 1
                        if (failFirstWrite && writes == 1) throw IllegalStateException("injected inventory failure")
                        null
                    }
                    "getMaxStackSize" -> 64
                    else -> null
                }
            } as PlayerInventory
        return InventoryFixture(inventory) { contents }
    }

    private fun item(
        initialStack: ItemStack,
        entityId: UUID = ENTITY_ID,
        initiallyValid: Boolean = true,
        failRemove: Boolean = false,
    ): ItemFixture {
        var removed = false
        var stack = initialStack
        val item =
            Proxy.newProxyInstance(
                Item::class.java.classLoader,
                arrayOf(Item::class.java),
            ) { _, method, arguments ->
                when (method.name) {
                    "getUniqueId" -> entityId
                    "getItemStack" -> stack
                    "setItemStack" -> {
                        stack = arguments.orEmpty().single() as ItemStack
                        null
                    }
                    "isValid" -> initiallyValid && !removed
                    "remove" -> {
                        if (failRemove) throw IllegalStateException("injected remove failure")
                        removed = true
                        null
                    }
                    else -> null
                }
            } as Item
        return ItemFixture(item, { removed }, { stack })
    }

    private fun player(
        inventory: Inventory,
        gameMode: GameMode = GameMode.SURVIVAL,
        playerId: UUID = FIRST_PLAYER_ID,
    ): Player =
        Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getUniqueId" -> playerId
                "getInventory" -> inventory
                "getGameMode" -> gameMode
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.firstOrNull()
                else -> null
            }
        } as Player

    private fun stack(
        material: Material,
        amount: Int = 1,
    ): ItemStack =
        object : ItemStack(material, amount) {
            override fun isSimilar(stack: ItemStack?): Boolean = stack?.type == type
        }

    private class InventoryFixture(
        val inventory: PlayerInventory,
        private val contents: () -> Array<ItemStack?>,
    ) {
        fun amounts(): List<Int?> = contents().map { it?.amount }

        fun itemAt(index: Int): ItemStack? = contents()[index]
    }

    private class TaggedItemStack(
        material: Material,
        amount: Int,
        val tag: String,
    ) : ItemStack(material, amount) {
        override fun clone(): ItemStack = TaggedItemStack(type, amount, tag)

        override fun isSimilar(stack: ItemStack?): Boolean = stack?.type == type
    }

    private class ItemFixture(
        val item: Item,
        private val removedProvider: () -> Boolean,
        private val stackProvider: () -> ItemStack,
    ) {
        val removed: Boolean
            get() = removedProvider()
        val stack: ItemStack
            get() = stackProvider()
    }

    private class RecordingRepository(
        amount: Long?,
        private val writeResult: ItemStateWriteResult = ItemStateWriteResult.Applied,
    ) : ItemStateRepository {
        private val state = ItemState(null, 0, 300, amount?.let(VirtualItemAmount::of))
        val saved = mutableListOf<ItemState>()

        override fun load(entityId: UUID): ItemStateLoadResult = ItemStateLoadResult.Loaded(state)

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            saved += state
            return writeResult
        }
    }

    private companion object {
        private val ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000060")
        private val REMAINDER_ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000063")
        private val FIRST_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000061")
        private val SECOND_PLAYER_ID = UUID.fromString("00000000-0000-0000-0000-000000000062")
    }
}
