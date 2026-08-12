package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemOwnership
import com.github.command1264.itemdropv2.core.ItemOwnershipSettings
import com.github.command1264.itemdropv2.core.ItemPickupProtectionService
import com.github.command1264.itemdropv2.core.ItemPickupSettings
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import com.github.command1264.itemdropv2.core.PickupWarningMessageType
import com.github.command1264.itemdropv2.core.PluginMessageLanguage
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.PlayerInventory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitItemPickupProtectionControllerTest {
    @Test
    fun `creative no-capacity candidate resolver accepts CraftBukkit negative pickup delay`() {
        val candidate = creativePickupCandidate(pickupDelay = -1)
        val inventory =
            proxy<PlayerInventory> { method ->
                when (method.name) {
                    "getStorageContents" -> arrayOf(ItemStack(Material.STONE, 64))
                    else -> defaultValue(method.returnType)
                }
            }
        val player =
            proxy<Player> { method ->
                when (method.name) {
                    "getGameMode" -> GameMode.CREATIVE
                    "getInventory" -> inventory
                    "getNearbyEntities" -> listOf(candidate)
                    else -> defaultValue(method.returnType)
                }
            }

        val resolved = resolveCreativeNoCapacityPickupCandidates(player)
        assertEquals(1, resolved.size)
        assertSame(candidate, resolved.single())
    }

    @Test
    fun `creative candidate resolver waits for native pickup when storage has an empty slot`() {
        var nearbyQueries = 0
        val inventory =
            proxy<PlayerInventory> { method ->
                when (method.name) {
                    "getStorageContents" -> arrayOf<ItemStack?>(null)
                    else -> defaultValue(method.returnType)
                }
            }
        val player =
            proxy<Player> { method ->
                when (method.name) {
                    "getGameMode" -> GameMode.CREATIVE
                    "getInventory" -> inventory
                    "getNearbyEntities" -> {
                        nearbyQueries += 1
                        listOf(creativePickupCandidate(pickupDelay = -1))
                    }
                    else -> defaultValue(method.returnType)
                }
            }

        assertTrue(resolveCreativeNoCapacityPickupCandidates(player).isEmpty())
        assertEquals(0, nearbyQueries)
    }

    @Test
    fun `cancels another player pickup and sends a throttled localized warning`() {
        val messages = mutableListOf<String>()
        var now = 0L
        val controller = controller { now }
        val player = player(messages, pickup = true, pickupOther = false)
        val first = EntityPickupItemEvent(player, item(), 0)
        val second = EntityPickupItemEvent(player, item(), 0)

        controller.onEntityPickup(first)
        controller.onEntityPickup(second)
        now = 5_000_000_000L
        controller.onEntityPickup(EntityPickupItemEvent(player, item(), 0))

        assertTrue(first.isCancelled)
        assertTrue(second.isCancelled)
        assertEquals(2, messages.size)
        assertTrue(messages.all { it.contains("17") && it.contains("Steve") })
    }

    @Test
    fun `shared owner warning lists three resolved names and bounds the remainder`() {
        val messages = mutableListOf<String>()
        val owners = listOf(OWNER_ID, SECOND_OWNER_ID, THIRD_OWNER_ID, FOURTH_OWNER_ID, FIFTH_OWNER_ID)
        val controller =
            controller(
                ownership = ItemOwnership(OWNER_ID, 17, owners),
                ownerNameResolver = { ownerId ->
                    when (ownerId) {
                        OWNER_ID -> "Steve"
                        SECOND_OWNER_ID -> "Alex"
                        THIRD_OWNER_ID -> ""
                        FOURTH_OWNER_ID -> "Sunny"
                        FIFTH_OWNER_ID -> "Rain"
                        else -> null
                    }
                },
                nanoTime = { 0 },
            )

        controller.onEntityPickup(EntityPickupItemEvent(player(messages, pickup = true, pickupOther = false), item(), 0))

        assertEquals(listOf("Steve, Alex, Sunny +2 17"), messages)
    }

    @Test
    fun `other pickup permission allows protected item`() {
        val messages = mutableListOf<String>()
        val event = EntityPickupItemEvent(player(messages, pickup = true, pickupOther = true), item(), 0)
        val cleaned = mutableListOf<UUID>()

        controller(
            nanoTime = { 0 },
            cleaner = {
                cleaned += it.uniqueId
                ItemStateWriteResult.Applied
            },
        ).onEntityPickup(event)

        assertFalse(event.isCancelled)
        assertTrue(messages.isEmpty())
        assertEquals(listOf(ITEM_ID), cleaned)
    }

    @Test
    fun `cancels inventory pickup while ownership protection remains`() {
        val event = InventoryPickupItemEvent(inventory(), item())

        controller { 0 }.onInventoryPickup(event)

        assertTrue(event.isCancelled)
    }

    @Test
    fun `successful virtual inventory pickup notifies the mutated inventory once`() {
        val targetInventory = inventory()
        val event = InventoryPickupItemEvent(targetInventory, item())
        val notified = mutableListOf<Inventory>()
        val queued = mutableListOf<() -> Unit>()
        val delays = mutableListOf<Long>()

        controller(
            ownership = ItemOwnership(OTHER_ID, 17),
            virtualPickupHandling = inventoryVirtualHandling(VirtualItemPickupOutcome.Inserted(64, 8_128)),
            inventoryMutationNotifier =
                InventoryMutationNotifier { inventory ->
                    notified += inventory
                    InventoryMutationNotificationResult.Applied
                },
            allowHopperPickup = true,
            delayedTaskExecutor =
                DelayedMainThreadTaskExecutor { delayTicks, task ->
                    delays += delayTicks
                    queued += task
                },
            nanoTime = { 0 },
        ).onInventoryPickup(event)

        assertTrue(event.isCancelled)
        assertEquals(listOf(1L), delays)
        assertTrue(notified.isEmpty())
        queued.single().invoke()
        assertEquals(1, notified.size)
        assertTrue(notified.single() === targetInventory)
    }

    @Test
    fun `virtual inventory pickup without capacity does not schedule a mutation notification`() {
        val queued = mutableListOf<() -> Unit>()
        val event = InventoryPickupItemEvent(inventory(), item())

        controller(
            ownership = ItemOwnership(OTHER_ID, 17),
            virtualPickupHandling = inventoryVirtualHandling(VirtualItemPickupOutcome.NoCapacity),
            allowHopperPickup = true,
            delayedTaskExecutor = DelayedMainThreadTaskExecutor { _, task -> queued += task },
            nanoTime = { 0 },
        ).onInventoryPickup(event)

        assertTrue(event.isCancelled)
        assertTrue(queued.isEmpty())
    }

    @Test
    fun `allowed virtual pickup cancels native event without running native cleanup`() {
        val event = EntityPickupItemEvent(player(mutableListOf(), pickup = true, pickupOther = true), item(), 0)
        var nativeCleanupCount = 0
        val virtualHandling =
            object : VirtualItemPickupHandling {
                override fun pickupByPlayer(
                    item: Item,
                    player: Player,
                    beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
                ): VirtualItemPickupOutcome = VirtualItemPickupOutcome.Inserted(64, 8_128)

                override fun pickupByInventory(
                    item: Item,
                    inventory: Inventory,
                ): VirtualItemPickupOutcome = error("unexpected inventory pickup")

                override fun pickupByNonPlayer(
                    item: Item,
                    nativePickupRemaining: Int,
                ): VirtualItemPickupOutcome = error("unexpected non-player pickup")
            }

        controller(
            cleaner = {
                nativeCleanupCount += 1
                ItemStateWriteResult.Applied
            },
            virtualPickupHandling = virtualHandling,
            nanoTime = { 0 },
        ).onEntityPickup(event)

        assertTrue(event.isCancelled)
        assertEquals(0, nativeCleanupCount)
    }

    @Test
    fun `partial virtual pickup refreshes the surviving carrier display`() {
        val refreshed = mutableListOf<Item>()
        val feedback = mutableListOf<Pair<Long, Long>>()
        val event = EntityPickupItemEvent(player(mutableListOf(), pickup = true, pickupOther = true), item(), 0)
        val virtualHandling =
            object : VirtualItemPickupHandling {
                override fun pickupByPlayer(
                    item: Item,
                    player: Player,
                    beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
                ): VirtualItemPickupOutcome {
                    beforeCarrierMutation(4, 124)
                    return VirtualItemPickupOutcome.Inserted(4, 124)
                }

                override fun pickupByInventory(
                    item: Item,
                    inventory: Inventory,
                ): VirtualItemPickupOutcome = error("unexpected inventory pickup")

                override fun pickupByNonPlayer(
                    item: Item,
                    nativePickupRemaining: Int,
                ): VirtualItemPickupOutcome = error("unexpected non-player pickup")
            }

        controller(
            virtualPickupHandling = virtualHandling,
            virtualPickupFeedback =
                VirtualItemPickupFeedback { _, _, consumedAmount, remainingAmount ->
                    feedback += consumedAmount to remainingAmount
                },
            itemRefresh =
                object : ItemOwnershipRefresh {
                    override fun refresh(entityId: UUID) {
                        error("event Item must be used directly")
                    }

                    override fun refresh(item: Item) {
                        refreshed += item
                    }
                },
            nanoTime = { 0 },
        ).onEntityPickup(event)

        assertTrue(event.isCancelled)
        assertEquals(listOf(4L to 124L), feedback)
        assertEquals(1, refreshed.size)
        assertSame(event.item, refreshed.single())
    }

    @Test
    fun `creative virtual pickups coalesce inventory synchronization to the next tick`() {
        val feedback = mutableListOf<Long>()
        val synchronized = mutableListOf<UUID>()
        val queued = mutableListOf<() -> Unit>()
        val delays = mutableListOf<Long>()
        val creative = player(mutableListOf(), pickup = true, pickupOther = true, gameMode = GameMode.CREATIVE)
        val virtualHandling =
            object : VirtualItemPickupHandling {
                override fun pickupByPlayer(
                    item: Item,
                    player: Player,
                    beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
                ): VirtualItemPickupOutcome {
                    beforeCarrierMutation(8_192, 0)
                    return VirtualItemPickupOutcome.Inserted(64, 0, discardedAmount = 8_128)
                }

                override fun pickupByInventory(
                    item: Item,
                    inventory: Inventory,
                ): VirtualItemPickupOutcome = error("unexpected inventory pickup")

                override fun pickupByNonPlayer(
                    item: Item,
                    nativePickupRemaining: Int,
                ): VirtualItemPickupOutcome = error("unexpected non-player pickup")
            }

        val controller =
            controller(
                virtualPickupHandling = virtualHandling,
                virtualPickupFeedback = VirtualItemPickupFeedback { _, _, amount, _ -> feedback += amount },
                inventorySynchronizer = PlayerInventorySynchronizer { synchronized += it.uniqueId },
                delayedTaskExecutor =
                    DelayedMainThreadTaskExecutor { delayTicks, task ->
                        delays += delayTicks
                        queued += task
                    },
                nanoTime = { 0 },
            )

        controller.onEntityPickup(EntityPickupItemEvent(creative, item(ITEM_ID), 0))
        controller.onEntityPickup(EntityPickupItemEvent(creative, item(SECOND_ITEM_ID), 0))

        assertEquals(listOf(8_192L, 8_192L), feedback)
        assertEquals(listOf(1L), delays)
        assertTrue(synchronized.isEmpty())
        queued.single().invoke()
        assertEquals(listOf(OTHER_ID), synchronized)

        controller.onEntityPickup(EntityPickupItemEvent(creative, item(ITEM_ID), 0))
        assertEquals(listOf(1L, 1L), delays)
        controller.close()
        queued.last().invoke()
        assertEquals(listOf(OTHER_ID), synchronized)
    }

    @Test
    fun `paper attempt pickup is handled when zero-capacity policy preserves the carrier`() {
        val creative = player(mutableListOf(), pickup = true, pickupOther = true, gameMode = GameMode.CREATIVE)
        val target = item()
        val controller =
            controller(
                virtualPickupHandling =
                    creativeCollisionHandling {
                        VirtualItemPickupOutcome.NoCapacity
                    },
                nanoTime = { 0 },
            )

        assertTrue(controller.processCreativeNoCapacityPickupAttempt(creative, target))
    }

    @Test
    fun `paper attempt pickup is handled after zero-capacity destroy consumes the carrier`() {
        val feedback = mutableListOf<Long>()
        val creative = player(mutableListOf(), pickup = true, pickupOther = true, gameMode = GameMode.CREATIVE)
        val target = item()
        val controller =
            controller(
                virtualPickupHandling =
                    creativeCollisionHandling { beforeCarrierMutation ->
                        beforeCarrierMutation(8_192L, 0L)
                        VirtualItemPickupOutcome.Inserted(0L, 0L, discardedAmount = 8_192L)
                    },
                virtualPickupFeedback = VirtualItemPickupFeedback { _, _, consumed, _ -> feedback += consumed },
                nanoTime = { 0 },
            )

        assertTrue(controller.processCreativeNoCapacityPickupAttempt(creative, target))
        assertEquals(listOf(8_192L), feedback)
    }

    @Test
    fun `paper attempt pickup remains native when compatible capacity exists`() {
        val creative = player(mutableListOf(), pickup = true, pickupOther = true, gameMode = GameMode.CREATIVE)
        val target = item()
        val controller =
            controller(
                virtualPickupHandling =
                    creativeCollisionHandling {
                        VirtualItemPickupOutcome.NotApplicable
                    },
                nanoTime = { 0 },
            )

        assertFalse(controller.processCreativeNoCapacityPickupAttempt(creative, target))
    }

    @Test
    fun `paper attempt pickup fails closed before virtual mutation when ownership denies it`() {
        var virtualCalls = 0
        val creative = player(mutableListOf(), pickup = true, pickupOther = false, gameMode = GameMode.CREATIVE)
        val controller =
            controller(
                virtualPickupHandling =
                    creativeCollisionHandling {
                        virtualCalls += 1
                        VirtualItemPickupOutcome.NotApplicable
                    },
                nanoTime = { 0 },
            )

        assertTrue(controller.processCreativeNoCapacityPickupAttempt(creative, item()))
        assertEquals(0, virtualCalls)
    }

    @Test
    fun `paper attempt pickup does not alter survival pickup routing`() {
        var virtualCalls = 0
        val survival = player(mutableListOf(), pickup = true, pickupOther = false)
        val controller =
            controller(
                virtualPickupHandling =
                    creativeCollisionHandling {
                        virtualCalls += 1
                        VirtualItemPickupOutcome.NoCapacity
                    },
                nanoTime = { 0 },
            )

        assertFalse(controller.processCreativeNoCapacityPickupAttempt(survival, item()))
        assertEquals(0, virtualCalls)
    }

    @Test
    fun `creative no-capacity discard does not synchronize an unchanged inventory`() {
        val feedback = mutableListOf<Long>()
        val synchronized = mutableListOf<UUID>()
        val delays = mutableListOf<Long>()
        val processed = mutableListOf<UUID>()
        val creative = player(mutableListOf(), pickup = true, pickupOther = true, gameMode = GameMode.CREATIVE)
        val target = item()
        val virtualHandling =
            object : VirtualItemPickupHandling {
                override fun pickupByPlayer(
                    item: Item,
                    player: Player,
                    beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
                ): VirtualItemPickupOutcome = error("native pickup event was not expected")

                override fun pickupByCreativeNoCapacityCollision(
                    item: Item,
                    player: Player,
                    beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
                ): VirtualItemPickupOutcome {
                    processed += item.uniqueId
                    beforeCarrierMutation(8_192, 0)
                    return VirtualItemPickupOutcome.Inserted(0, 0, discardedAmount = 8_192)
                }

                override fun pickupByInventory(
                    item: Item,
                    inventory: Inventory,
                ): VirtualItemPickupOutcome = error("unexpected inventory pickup")

                override fun pickupByNonPlayer(
                    item: Item,
                    nativePickupRemaining: Int,
                ): VirtualItemPickupOutcome = error("unexpected non-player pickup")
            }

        controller(
            virtualPickupHandling = virtualHandling,
            creativeNoCapacityPickupCandidates = { listOf(target) },
            virtualPickupFeedback = VirtualItemPickupFeedback { _, _, amount, _ -> feedback += amount },
            inventorySynchronizer = PlayerInventorySynchronizer { synchronized += it.uniqueId },
            delayedTaskExecutor = DelayedMainThreadTaskExecutor { delayTicks, _ -> delays += delayTicks },
            nanoTime = { 0 },
        ).processCreativeNoCapacityPickups(listOf(creative))

        assertEquals(listOf(ITEM_ID), processed)
        assertEquals(listOf(8_192L), feedback)
        assertTrue(delays.isEmpty())
        assertTrue(synchronized.isEmpty())
    }

    @Test
    fun `partial native pickup refreshes after the server applies its remainder`() {
        val refreshed = mutableListOf<UUID>()
        val queued = mutableListOf<() -> Unit>()
        val delays = mutableListOf<Long>()
        val event = EntityPickupItemEvent(player(mutableListOf(), pickup = true, pickupOther = true), item(), 5)

        controller(
            itemRefresh = ItemOwnershipRefresh(refreshed::add),
            delayedTaskExecutor =
                DelayedMainThreadTaskExecutor { delayTicks, task ->
                    delays += delayTicks
                    queued += task
                },
            nanoTime = { 0 },
        ).onEntityPickup(event)

        assertFalse(event.isCancelled)
        assertEquals(listOf(1L), delays)
        assertTrue(refreshed.isEmpty())
        queued.single().invoke()
        assertEquals(listOf(ITEM_ID), refreshed)
    }

    @Test
    fun `allowed non-player virtual pickup keeps native event active and reconciles next tick`() {
        val queued = mutableListOf<() -> Unit>()
        val delays = mutableListOf<Long>()
        val refreshed = mutableListOf<Item>()
        val target = item()
        val observedRemaining = mutableListOf<Int>()
        val virtualHandling =
            object : VirtualItemPickupHandling {
                override fun pickupByPlayer(
                    item: Item,
                    player: Player,
                    beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
                ): VirtualItemPickupOutcome = error("unexpected player pickup")

                override fun pickupByInventory(
                    item: Item,
                    inventory: Inventory,
                ): VirtualItemPickupOutcome = error("unexpected inventory pickup")

                override fun pickupByNonPlayer(
                    item: Item,
                    nativePickupRemaining: Int,
                ): VirtualItemPickupOutcome {
                    observedRemaining += nativePickupRemaining
                    return VirtualItemPickupOutcome.NativePickupPrepared(
                        item.uniqueId,
                        listOf(item),
                        reconcileAfterNativePickup = true,
                    )
                }

                override fun reconcileNonPlayerPickup(sourceEntityId: UUID): NonPlayerPickupReconciliationOutcome =
                    NonPlayerPickupReconciliationOutcome.Applied(target)
            }
        val event = EntityPickupItemEvent(livingEntity(), target, 3)

        controller(
            ownership = null,
            virtualPickupHandling = virtualHandling,
            itemRefresh =
                object : ItemOwnershipRefresh {
                    override fun refresh(entityId: UUID) {
                        error("prepared Item must be refreshed directly")
                    }

                    override fun refresh(item: Item) {
                        refreshed += item
                    }
                },
            delayedTaskExecutor =
                DelayedMainThreadTaskExecutor { delayTicks, task ->
                    delays += delayTicks
                    queued += task
                },
            nanoTime = { 0 },
        ).onEntityPickup(event)

        assertFalse(event.isCancelled)
        assertEquals(listOf(3), observedRemaining)
        assertEquals(1, refreshed.size)
        assertSame(target, refreshed.single())
        assertEquals(listOf(1L), delays)
        queued.single().invoke()
        assertEquals(2, refreshed.size)
        assertTrue(refreshed.all { it === target })
    }

    @Test
    fun `protected item denies non-player before virtual pickup preparation`() {
        var pickupCalls = 0
        val event = EntityPickupItemEvent(livingEntity(), item(), 0)

        controller(
            virtualPickupHandling =
                object : VirtualItemPickupHandling {
                    override fun pickupByPlayer(
                        item: Item,
                        player: Player,
                        beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
                    ): VirtualItemPickupOutcome = error("unexpected player pickup")

                    override fun pickupByInventory(
                        item: Item,
                        inventory: Inventory,
                    ): VirtualItemPickupOutcome = error("unexpected inventory pickup")

                    override fun pickupByNonPlayer(
                        item: Item,
                        nativePickupRemaining: Int,
                    ): VirtualItemPickupOutcome {
                        pickupCalls += 1
                        return VirtualItemPickupOutcome.NotVirtual
                    }
                },
            nanoTime = { 0 },
        ).onEntityPickup(event)

        assertTrue(event.isCancelled)
        assertEquals(0, pickupCalls)
    }

    @Test
    fun `keeps the event item as the direct repository target during pickup evaluation`() {
        val activeTargets = mutableSetOf<UUID>()
        val acquired = mutableListOf<UUID>()
        val released = mutableListOf<UUID>()
        val event = EntityPickupItemEvent(player(mutableListOf(), pickup = true, pickupOther = true), item(), 0)

        controller(
            repositoryTargetAssertion = { entityId -> check(entityId in activeTargets) },
            transientTargetLeaseFactory =
                TransientItemTargetLeaseFactory { target ->
                    acquired += target.uniqueId
                    activeTargets += target.uniqueId
                    TransientItemTargetLease {
                        activeTargets -= target.uniqueId
                        released += target.uniqueId
                    }
                },
            nanoTime = { 0 },
        ).onEntityPickup(event)

        assertEquals(listOf(ITEM_ID), acquired)
        assertEquals(listOf(ITEM_ID), released)
        assertTrue(activeTargets.isEmpty())
    }

    @Test
    fun `keeps the inventory pickup event item as the direct repository target`() {
        val activeTargets = mutableSetOf<UUID>()
        val event = InventoryPickupItemEvent(inventory(), item())

        controller(
            allowHopperPickup = true,
            repositoryTargetAssertion = { entityId -> check(entityId in activeTargets) },
            transientTargetLeaseFactory =
                TransientItemTargetLeaseFactory { target ->
                    activeTargets += target.uniqueId
                    TransientItemTargetLease { activeTargets -= target.uniqueId }
                },
            nanoTime = { 0 },
        ).onInventoryPickup(event)

        assertTrue(activeTargets.isEmpty())
    }

    private fun controller(
        cleaner: (Item) -> ItemStateWriteResult = { ItemStateWriteResult.Applied },
        virtualPickupHandling: VirtualItemPickupHandling =
            object : VirtualItemPickupHandling {
                override fun pickupByPlayer(
                    item: Item,
                    player: Player,
                    beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
                ): VirtualItemPickupOutcome = VirtualItemPickupOutcome.NotVirtual

                override fun pickupByInventory(
                    item: Item,
                    inventory: Inventory,
                ): VirtualItemPickupOutcome = VirtualItemPickupOutcome.NotVirtual

                override fun pickupByNonPlayer(
                    item: Item,
                    nativePickupRemaining: Int,
                ): VirtualItemPickupOutcome = VirtualItemPickupOutcome.NotVirtual
            },
        inventoryMutationNotifier: InventoryMutationNotifier =
            InventoryMutationNotifier {
                InventoryMutationNotificationResult.NotRequired
            },
        itemRefresh: ItemOwnershipRefresh = ItemOwnershipRefresh {},
        delayedTaskExecutor: DelayedMainThreadTaskExecutor = DelayedMainThreadTaskExecutor { _, _ -> },
        virtualPickupFeedback: VirtualItemPickupFeedback = VirtualItemPickupFeedback { _, _, _, _ -> },
        inventorySynchronizer: PlayerInventorySynchronizer = PlayerInventorySynchronizer {},
        creativeNoCapacityPickupCandidates: (Player) -> List<Item> = { emptyList() },
        ownership: ItemOwnership? = ItemOwnership(OWNER_ID, 17),
        ownerNameResolver: (UUID) -> String? = { "Steve" },
        allowHopperPickup: Boolean = false,
        repositoryTargetAssertion: (UUID) -> Unit = {},
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
            TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
        nanoTime: () -> Long,
    ): BukkitItemPickupProtectionController {
        val settings = settings(allowHopperPickup)
        val repository = Repository(ItemState(ownership, 0, 300), repositoryTargetAssertion)
        return BukkitItemPickupProtectionController(
            service = ItemPickupProtectionService(repository, settings),
            settingsRepository = settings,
            messages =
                BukkitPickupMessageCatalog(
                    PluginMessageLanguage.BUILT_IN.associateWith {
                        mapOf(
                            PickupMessageKey.NO_PERMISSION to "No %permission%",
                            PickupMessageKey.OTHER_OWNER to "%owner_names% %seconds%",
                        )
                    },
                ),
            ownerNameResolver = ownerNameResolver,
            warningSink = DisplayWarningSink { error("unexpected warning: $it") },
            nanoTime = nanoTime,
            pickedUpStateCleaner = cleaner,
            virtualPickupHandling = virtualPickupHandling,
            inventoryMutationNotifier = inventoryMutationNotifier,
            virtualPickupFeedback = virtualPickupFeedback,
            inventorySynchronizer = inventorySynchronizer,
            creativeNoCapacityPickupCandidates = creativeNoCapacityPickupCandidates,
            itemRefresh = itemRefresh,
            delayedTaskExecutor = delayedTaskExecutor,
            transientTargetLeaseFactory = transientTargetLeaseFactory,
        )
    }

    private fun settings(allowHopperPickup: Boolean = false): ItemDisplaySettingsRepository {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return ItemDisplaySettingsRepository {
            ItemDisplaySettings(
                true,
                emptySet(),
                template,
                template,
                ownership =
                    ItemOwnershipSettings(
                        pickup =
                            ItemPickupSettings(
                                allowHopperPickup = allowHopperPickup,
                                warningCooldownSeconds = 5,
                                warningMessageType = PickupWarningMessageType.CHAT,
                            ),
                    ),
            )
        }
    }

    private fun player(
        messages: MutableList<String>,
        pickup: Boolean,
        pickupOther: Boolean,
        gameMode: GameMode = GameMode.SURVIVAL,
    ): Player =
        Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, arguments ->
            when (method.name) {
                "getUniqueId" -> OTHER_ID
                "getInventory" -> playerInventory()
                "getGameMode" -> gameMode
                "hasPermission" ->
                    when (arguments?.firstOrNull()) {
                        "itemdrop.event.pickup" -> pickup
                        "itemdrop.event.pickup.other" -> pickupOther
                        else -> false
                    }
                "sendMessage" -> {
                    (arguments?.firstOrNull() as? String)?.let(messages::add)
                    Unit
                }
                else -> defaultValue(method.returnType)
            }
        } as Player

    private fun item(entityId: UUID = ITEM_ID): Item {
        val world = proxy<World> { method -> if (method.name == "getName") "world" else defaultValue(method.returnType) }
        return proxy { method ->
            when (method.name) {
                "getUniqueId" -> entityId
                "getWorld" -> world
                else -> defaultValue(method.returnType)
            }
        }
    }

    private fun creativePickupCandidate(pickupDelay: Int): Item =
        proxy { method ->
            when (method.name) {
                "getPickupDelay" -> pickupDelay
                "isValid" -> true
                "isDead" -> false
                "getUniqueId" -> ITEM_ID
                else -> defaultValue(method.returnType)
            }
        }

    private fun livingEntity(): LivingEntity = proxy { method -> defaultValue(method.returnType) }

    private fun inventory(): Inventory = proxy { method -> defaultValue(method.returnType) }

    private fun playerInventory(): PlayerInventory = proxy { method -> defaultValue(method.returnType) }

    private fun inventoryVirtualHandling(outcome: VirtualItemPickupOutcome): VirtualItemPickupHandling =
        object : VirtualItemPickupHandling {
            override fun pickupByPlayer(
                item: Item,
                player: Player,
                beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
            ): VirtualItemPickupOutcome = error("unexpected player pickup")

            override fun pickupByInventory(
                item: Item,
                inventory: Inventory,
            ): VirtualItemPickupOutcome = outcome

            override fun pickupByNonPlayer(
                item: Item,
                nativePickupRemaining: Int,
            ): VirtualItemPickupOutcome = error("unexpected non-player pickup")
        }

    private fun creativeCollisionHandling(
        outcome: ((consumedAmount: Long, remainingAmount: Long) -> Unit) -> VirtualItemPickupOutcome,
    ): VirtualItemPickupHandling =
        object : VirtualItemPickupHandling {
            override fun pickupByPlayer(
                item: Item,
                player: Player,
                beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
            ): VirtualItemPickupOutcome = error("native pickup event was not expected")

            override fun pickupByCreativeNoCapacityCollision(
                item: Item,
                player: Player,
                beforeCarrierMutation: (consumedAmount: Long, remainingAmount: Long) -> Unit,
            ): VirtualItemPickupOutcome = outcome(beforeCarrierMutation)

            override fun pickupByInventory(
                item: Item,
                inventory: Inventory,
            ): VirtualItemPickupOutcome = error("unexpected inventory pickup")

            override fun pickupByNonPlayer(
                item: Item,
                nativePickupRemaining: Int,
            ): VirtualItemPickupOutcome = error("unexpected non-player pickup")
        }

    private inline fun <reified T> proxy(crossinline answer: (java.lang.reflect.Method) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ -> answer(method) } as T

    private class Repository(
        private val state: ItemState,
        private val targetAssertion: (UUID) -> Unit,
    ) : ItemStateRepository {
        override fun load(entityId: UUID): ItemStateLoadResult {
            targetAssertion(entityId)
            return ItemStateLoadResult.Loaded(state)
        }

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult = error("unexpected save")
    }

    private companion object {
        private val ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val SECOND_ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000006")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003")
        private val SECOND_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004")
        private val THIRD_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000005")
        private val FOURTH_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000006")
        private val FIFTH_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000007")

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
