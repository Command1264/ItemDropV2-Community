package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class ItemMergeServiceTest {
    @Test
    fun `rejects owned and unowned items and different owners`() {
        val repository = FakeItemStateRepository()
        repository.states[SOURCE_ID] = ItemState(ItemOwnership(OWNER_ID, 1_000), 10, 100)
        repository.states[TARGET_ID] = ItemState(null, 20, 100)
        val service = service(repository, MergeLifetimeStrategy.AVERAGE)

        assertEquals(ItemMergeOutcome.Rejected(ItemMergeRejection.OWNER_MISMATCH), service.merge(SOURCE_ID, TARGET_ID))

        repository.states[TARGET_ID] = ItemState(ItemOwnership(OTHER_OWNER_ID, 1_000), 20, 100)
        assertEquals(ItemMergeOutcome.Rejected(ItemMergeRejection.OWNER_MISMATCH), service.merge(SOURCE_ID, TARGET_ID))
    }

    @Test
    fun `matching owners use configured ownership strategy`() {
        val expected =
            mapOf(
                MergeOwnershipStrategy.AVERAGE to 1_500L,
                MergeOwnershipStrategy.MAXIMUM to 2_000L,
                MergeOwnershipStrategy.MINIMUM to 1_000L,
                MergeOwnershipStrategy.RESET to 30L,
            )
        expected.forEach { (ownershipStrategy, expectedSeconds) ->
            val repository = FakeItemStateRepository()
            repository.states[SOURCE_ID] = ItemState(ItemOwnership(OWNER_ID, 2_000), 10, 100)
            repository.states[TARGET_ID] = ItemState(ItemOwnership(OWNER_ID, 1_000), 20, 100)

            val merged =
                assertInstanceOf(
                    ItemMergeOutcome.Merged::class.java,
                    service(repository, ownershipStrategy = ownershipStrategy).merge(SOURCE_ID, TARGET_ID),
                )

            assertEquals(ItemOwnership(OWNER_ID, expectedSeconds), merged.state.ownership)
        }
    }

    @Test
    fun `reset with zero configured protection clears ownership`() {
        val repository = FakeItemStateRepository()
        repository.states[SOURCE_ID] = ItemState(ItemOwnership(OWNER_ID, 2_000), 10, 100)
        repository.states[TARGET_ID] = ItemState(ItemOwnership(OWNER_ID, 1_000), 20, 100)

        val merged =
            assertInstanceOf(
                ItemMergeOutcome.Merged::class.java,
                service(
                    repository,
                    ownershipStrategy = MergeOwnershipStrategy.RESET,
                    protectionSeconds = 0,
                ).merge(SOURCE_ID, TARGET_ID),
            )

        assertEquals(null, merged.state.ownership)
    }

    @Test
    fun `only merges identical shared ownership groups`() {
        val repository = FakeItemStateRepository()
        val shared = listOf(OWNER_ID, OTHER_OWNER_ID)
        repository.states[SOURCE_ID] = ItemState(ItemOwnership(OWNER_ID, 2_000, shared), 10, 100)
        repository.states[TARGET_ID] = ItemState(ItemOwnership(OWNER_ID, 1_000, shared), 20, 100)

        val merged = assertInstanceOf(ItemMergeOutcome.Merged::class.java, service(repository).merge(SOURCE_ID, TARGET_ID))
        assertEquals(shared, requireNotNull(merged.state.ownership).eligibleOwnerUuids)

        repository.states[TARGET_ID] = ItemState(ItemOwnership(OWNER_ID, 1_000), 20, 100)
        assertEquals(ItemMergeOutcome.Rejected(ItemMergeRejection.OWNER_MISMATCH), service(repository).merge(SOURCE_ID, TARGET_ID))
    }

    @Test
    fun `allows two unowned items`() {
        val repository = FakeItemStateRepository()
        repository.states[SOURCE_ID] = ItemState(null, 10, 100)
        repository.states[TARGET_ID] = ItemState(null, 20, 100)

        assertInstanceOf(ItemMergeOutcome.Merged::class.java, service(repository).merge(SOURCE_ID, TARGET_ID))
    }

    @Test
    fun `allows two absent startup items without persisting a default before material resolution`() {
        val repository = FakeItemStateRepository()

        assertEquals(ItemMergeOutcome.Untracked, service(repository).merge(SOURCE_ID, TARGET_ID))
        assertEquals(emptyMap<UUID, ItemState>(), repository.states)
    }

    @Test
    fun `selects elapsed lifetime and preserves target original lifetime`() {
        val expected =
            mapOf(
                MergeLifetimeStrategy.AVERAGE to 46L,
                MergeLifetimeStrategy.MAXIMUM to 20L,
                MergeLifetimeStrategy.MINIMUM to 71L,
            )
        expected.forEach { (strategy, expectedElapsed) ->
            val repository = FakeItemStateRepository()
            repository.states[SOURCE_ID] = ItemState(null, 20, 100)
            repository.states[TARGET_ID] = ItemState(null, 71, 140)

            val merged = assertInstanceOf(ItemMergeOutcome.Merged::class.java, service(repository, strategy).merge(SOURCE_ID, TARGET_ID))

            assertEquals(ItemState(null, expectedElapsed, 140), merged.state)
            assertEquals(merged.state, repository.states[TARGET_ID])
        }
    }

    @Test
    fun `uses persisted plugin remaining seconds`() {
        val repository = FakeItemStateRepository()
        repository.states[SOURCE_ID] = ItemState(null, 5, 100)
        repository.states[TARGET_ID] = ItemState(null, 5, 100)
        val merged =
            assertInstanceOf(
                ItemMergeOutcome.Merged::class.java,
                service(repository, MergeLifetimeStrategy.AVERAGE).merge(SOURCE_ID, TARGET_ID),
            )

        assertEquals(95, merged.state.remainingLifetimeSeconds)
    }

    @Test
    fun `average is overflow safe and preserves target original lifetime`() {
        val repository = FakeItemStateRepository()
        repository.states[SOURCE_ID] = ItemState(null, Long.MAX_VALUE, -1)
        repository.states[TARGET_ID] = ItemState(null, 5, Long.MAX_VALUE)

        val merged =
            assertInstanceOf(
                ItemMergeOutcome.Merged::class.java,
                service(repository, MergeLifetimeStrategy.AVERAGE).merge(SOURCE_ID, TARGET_ID),
            )

        assertEquals(Long.MAX_VALUE, merged.state.originalLifetimeSeconds)
        assertEquals(4_611_686_018_427_387_906L, merged.state.elapsedLifetimeSeconds)
        assertEquals(4_611_686_018_427_387_901L, merged.state.remainingLifetimeSeconds)
    }

    @Test
    fun `fails closed for legacy invalid and unsupported item state`() {
        val repository = FakeItemStateRepository()
        val service = service(repository)
        val rejectedInputs =
            listOf<ItemStateLoadResult>(
                ItemStateLoadResult.Legacy(LegacyItemState(0, 1, null, null)),
                ItemStateLoadResult.Invalid(listOf("broken")),
                ItemStateLoadResult.UnsupportedSchema(2),
            )
        rejectedInputs.forEach { input ->
            repository.results[SOURCE_ID] = input
            repository.states[TARGET_ID] = ItemState(null, 0, 100)
            assertInstanceOf(ItemMergeOutcome.Rejected::class.java, service.merge(SOURCE_ID, TARGET_ID))
        }
    }

    private fun service(
        repository: FakeItemStateRepository,
        strategy: MergeLifetimeStrategy = MergeLifetimeStrategy.AVERAGE,
        ownershipStrategy: MergeOwnershipStrategy = MergeOwnershipStrategy.AVERAGE,
        protectionSeconds: Long = ItemOwnershipSettings.DEFAULT_PROTECTION_SECONDS,
    ): ItemMergeService {
        val settings = testSettings(strategy, ownershipStrategy, protectionSeconds)
        return ItemMergeService(repository, ItemDisplaySettingsRepository { settings })
    }

    private fun testSettings(
        strategy: MergeLifetimeStrategy,
        ownershipStrategy: MergeOwnershipStrategy,
        protectionSeconds: Long,
    ): ItemDisplaySettings =
        ItemDisplaySettings(
            enabled = true,
            blockedWorlds = emptySet(),
            singleItemTemplate = validTemplate("%item_display_name%"),
            multipleItemTemplate = validTemplate("%item_display_name% x%amount%"),
            ownership = ItemOwnershipSettings(protectionSeconds = protectionSeconds),
            lifetime = ItemLifetimeSettings(defaultLifetimeSeconds = 300),
            merge = ItemMergeSettings(strategy, ownershipStrategy),
        )

    private fun validTemplate(raw: String): DisplayTemplate = (DisplayTemplate.parse(raw) as DisplayTemplateParseResult.Valid).template

    private class FakeItemStateRepository : ItemStateRepository {
        val states = mutableMapOf<UUID, ItemState>()
        val results = mutableMapOf<UUID, ItemStateLoadResult>()

        override fun load(entityId: UUID): ItemStateLoadResult =
            results[entityId] ?: states[entityId]?.let(ItemStateLoadResult::Loaded) ?: ItemStateLoadResult.Absent

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            states[entityId] = state
            return ItemStateWriteResult.Applied
        }
    }

    private companion object {
        private val SOURCE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val TARGET_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003")
        private val OTHER_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004")
    }
}
