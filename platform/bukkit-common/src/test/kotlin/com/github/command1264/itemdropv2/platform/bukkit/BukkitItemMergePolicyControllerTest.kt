package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemMergeService
import com.github.command1264.itemdropv2.core.ItemOwnership
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import org.bukkit.entity.Item
import org.bukkit.event.entity.ItemMergeEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitItemMergePolicyControllerTest {
    @Test
    fun `cancels merge when only one item has an owner`() {
        val repository = repository(ItemState(ItemOwnership(OWNER_ID, 1_000), 0, 100), ItemState(null, 0, 100))
        val event = ItemMergeEvent(item(SOURCE_ID), item(TARGET_ID))

        controller(repository).onItemMerge(event)

        assertTrue(event.isCancelled)
    }

    @Test
    fun `commits matching tracked items through the controlled transaction`() {
        val repository =
            repository(
                ItemState(ItemOwnership(OWNER_ID, 1_000), 0, 100),
                ItemState(ItemOwnership(OWNER_ID, 2_000), 0, 100),
            )
        val event = ItemMergeEvent(item(SOURCE_ID), item(TARGET_ID))

        val commits = mutableListOf<Triple<UUID, UUID, ItemState>>()
        controller(
            repository,
            TrackedItemMergeTransaction { source, target, state ->
                commits += Triple(source.uniqueId, target.uniqueId, state)
                true
            },
        ).onItemMerge(event)

        assertTrue(event.isCancelled)
        assertEquals(listOf(Triple(SOURCE_ID, TARGET_ID, repository.state(TARGET_ID))), commits)
    }

    @Test
    fun `scheduled virtual dispatch rechecks mode before merge commit`() {
        val repository = repository(ItemState(null, 0, 100), ItemState(null, 0, 100))
        val context = VirtualItemMergeDispatchContext()
        var virtualStateProbes = 0
        var merges = 0
        val refreshed = mutableListOf<Item>()
        val virtualOperations =
            object : VirtualItemMergeOperations {
                override fun isVirtualPair(
                    source: Item,
                    target: Item,
                ): Boolean {
                    virtualStateProbes += 1
                    return true
                }

                override fun canAttempt(
                    source: Item,
                    target: Item,
                ): Boolean = true

                override fun merge(
                    source: Item,
                    target: Item,
                ): VirtualItemMergeTransactionOutcome {
                    merges += 1
                    return VirtualItemMergeTransactionOutcome.Merged(1, sourceRemoved = false)
                }
            }
        val directRefresh =
            object : ItemOwnershipRefresh {
                override fun refresh(entityId: UUID) {
                    error("merge event Item must be used directly")
                }

                override fun refresh(item: Item) {
                    refreshed += item
                }
            }
        val controller =
            controller(
                repository = repository,
                virtualOperations = virtualOperations,
                dispatchContext = context,
                virtualMergeRefresh = directRefresh,
            )
        val event = ItemMergeEvent(item(SOURCE_ID), item(TARGET_ID))

        context.dispatch(SOURCE_ID, TARGET_ID) {
            controller.onItemMerge(event)
            controller.onVirtualItemMerge(event)
        }

        assertTrue(event.isCancelled)
        assertEquals(1, merges)
        assertEquals(1, virtualStateProbes)
        assertEquals(listOf(TARGET_ID, SOURCE_ID), refreshed.map(Item::getUniqueId))
    }

    @Test
    fun `keeps both merge event items as direct repository targets during policy evaluation`() {
        val activeTargets = mutableSetOf<UUID>()
        val acquired = mutableListOf<UUID>()
        val released = mutableListOf<UUID>()
        val repository =
            repository(
                ItemState(ItemOwnership(OWNER_ID, 1_000), 0, 100),
                ItemState(ItemOwnership(OWNER_ID, 2_000), 0, 100),
                targetAssertion = { entityId -> check(entityId in activeTargets) },
            )
        val event = ItemMergeEvent(item(SOURCE_ID), item(TARGET_ID))

        controller(
            repository = repository,
            transientTargetLeaseFactory =
                TransientItemTargetLeaseFactory { target ->
                    acquired += target.uniqueId
                    activeTargets += target.uniqueId
                    TransientItemTargetLease {
                        activeTargets -= target.uniqueId
                        released += target.uniqueId
                    }
                },
        ).onItemMerge(event)

        assertEquals(listOf(SOURCE_ID, TARGET_ID), acquired)
        assertEquals(listOf(TARGET_ID, SOURCE_ID), released)
        assertTrue(activeTargets.isEmpty())
    }

    @Test
    fun `virtual merge rejection forwards structured source and target diagnostics`() {
        val repository = repository(ItemState(null, 100), ItemState(null, 100))
        var warningMessage: String? = null
        var warningContext: RuntimeDiagnosticContext? = null
        val warningSink =
            object : DisplayWarningSink {
                override fun warn(message: String) {
                    error("structured warning overload must be used")
                }

                override fun warn(
                    message: String,
                    context: RuntimeDiagnosticContext,
                ) {
                    warningMessage = message
                    warningContext = context
                }
            }
        val virtualOperations =
            object : VirtualItemMergeOperations {
                override fun isVirtualPair(
                    source: Item,
                    target: Item,
                ): Boolean = true

                override fun canAttempt(
                    source: Item,
                    target: Item,
                ): Boolean = true

                override fun merge(
                    source: Item,
                    target: Item,
                ): VirtualItemMergeTransactionOutcome =
                    VirtualItemMergeTransactionOutcome.Rejected(
                        "MixedVirtualState",
                        mapOf("merge.target.virtual-amount" to "missing"),
                    )
            }
        val event = ItemMergeEvent(item(SOURCE_ID), item(TARGET_ID))

        controller(repository, virtualOperations = virtualOperations, warningSink = warningSink)
            .onVirtualItemMerge(event)

        assertEquals("virtual item merge rejected (MixedVirtualState)", warningMessage)
        assertEquals("missing", warningContext?.fields?.get("merge.target.virtual-amount"))
        assertEquals(SOURCE_ID.toString(), warningContext?.fields?.get("merge.source.entity-id"))
        assertEquals(TARGET_ID.toString(), warningContext?.fields?.get("merge.target.entity-id"))
    }

    @Test
    fun `ownership policy rejections cancel virtual merge without warning`() {
        val repository =
            repository(
                ItemState(ItemOwnership(OWNER_ID, 30), 0, 100),
                ItemState(null, 0, 100),
            )
        val virtualOperations =
            object : VirtualItemMergeOperations {
                override fun isVirtualPair(
                    source: Item,
                    target: Item,
                ): Boolean = true

                override fun canAttempt(
                    source: Item,
                    target: Item,
                ): Boolean = true

                override fun merge(
                    source: Item,
                    target: Item,
                ): VirtualItemMergeTransactionOutcome = VirtualItemMergeTransactionOutcome.OwnershipMismatch
            }
        val event = ItemMergeEvent(item(SOURCE_ID), item(TARGET_ID))

        controller(repository, virtualOperations = virtualOperations).onVirtualItemMerge(event)

        assertTrue(event.isCancelled)
    }

    @Test
    fun `legacy drain pair is cancelled without merge attempt or warning`() {
        val repository = repository(ItemState(null, 0, 100), ItemState(null, 0, 100))
        var mergeCalls = 0
        val warnings = mutableListOf<String>()
        val virtualOperations =
            object : VirtualItemMergeOperations {
                override fun mode(
                    source: Item,
                    target: Item,
                ): VirtualItemMergePairMode = VirtualItemMergePairMode.LegacyDrain

                override fun isVirtualPair(
                    source: Item,
                    target: Item,
                ): Boolean = true

                override fun canAttempt(
                    source: Item,
                    target: Item,
                ): Boolean = false

                override fun merge(
                    source: Item,
                    target: Item,
                ): VirtualItemMergeTransactionOutcome {
                    mergeCalls += 1
                    return VirtualItemMergeTransactionOutcome.Rejected("unexpected")
                }
            }
        val event = ItemMergeEvent(item(SOURCE_ID), item(TARGET_ID))

        controller(
            repository,
            virtualOperations = virtualOperations,
            warningSink = DisplayWarningSink(warnings::add),
        ).onVirtualItemMerge(event)

        assertTrue(event.isCancelled)
        assertEquals(0, mergeCalls)
        assertEquals(emptyList<String>(), warnings)
    }

    private fun controller(
        repository: RecordingRepository,
        trackedTransaction: TrackedItemMergeTransaction = TrackedItemMergeTransaction { _, _, _ -> true },
        virtualOperations: VirtualItemMergeOperations? = null,
        dispatchContext: VirtualItemMergeDispatchContext? = null,
        virtualMergeRefresh: ItemOwnershipRefresh = ItemOwnershipRefresh {},
        warningSink: DisplayWarningSink = DisplayWarningSink { error("unexpected warning: $it") },
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
            TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    ): BukkitItemMergePolicyController {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        val settings = ItemDisplaySettings(true, emptySet(), template, template)
        return BukkitItemMergePolicyController(
            ItemMergeService(repository, ItemDisplaySettingsRepository { settings }),
            warningSink,
            trackedTransaction,
            virtualOperations,
            virtualMergeRefresh,
            dispatchContext = dispatchContext,
            transientTargetLeaseFactory = transientTargetLeaseFactory,
        )
    }

    private fun repository(
        source: ItemState,
        target: ItemState,
        targetAssertion: (UUID) -> Unit = {},
    ): RecordingRepository = RecordingRepository(mutableMapOf(SOURCE_ID to source, TARGET_ID to target), targetAssertion)

    private fun item(id: UUID): Item =
        Proxy.newProxyInstance(Item::class.java.classLoader, arrayOf(Item::class.java)) { _, method, _ ->
            when (method.name) {
                "getUniqueId" -> id
                else -> defaultValue(method.returnType)
            }
        } as Item

    private class RecordingRepository(
        private val states: MutableMap<UUID, ItemState>,
        private val targetAssertion: (UUID) -> Unit,
    ) : ItemStateRepository {
        override fun load(entityId: UUID): ItemStateLoadResult {
            targetAssertion(entityId)
            return ItemStateLoadResult.Loaded(states.getValue(entityId))
        }

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            states[entityId] = state
            return ItemStateWriteResult.Applied
        }

        fun state(entityId: UUID): ItemState = states.getValue(entityId)
    }

    private companion object {
        private val SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003")

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
