package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class BlockDropOwnershipServiceTest {
    @Test
    fun `assigns owner and remaining protection seconds to a new item`() {
        val repository = RecordingItemStateRepository(ItemStateLoadResult.Absent)
        val service = service(repository = repository)

        val outcome = service.assign(request())

        assertInstanceOf(BlockDropOwnershipOutcome.Assigned::class.java, outcome)
        assertEquals(
            ItemState(
                ownership = ItemOwnership(OWNER_ID, protectionSecondsRemaining = 30),
                elapsedLifetimeSeconds = 0,
                originalLifetimeSeconds = null,
            ),
            repository.savedState,
        )
    }

    @Test
    fun `preserves existing age and lifetime while replacing ownership`() {
        val existing =
            ItemState(
                ItemOwnership(OTHER_OWNER_ID, 20),
                elapsedLifetimeSeconds = 80,
                originalLifetimeSeconds = 300,
            )
        val repository = RecordingItemStateRepository(ItemStateLoadResult.Loaded(existing))

        service(repository = repository).assign(request())

        assertEquals(
            ItemState(
                ItemOwnership(OWNER_ID, 30),
                elapsedLifetimeSeconds = 80,
                originalLifetimeSeconds = 300,
            ),
            repository.savedState,
        )
    }

    @Test
    fun `ignores disabled ownership and blocked worlds without touching repository`() {
        val repository = RecordingItemStateRepository(ItemStateLoadResult.Absent)
        val disabled = service(repository, ownershipEnabled = false).assign(request())
        val blocked = service(repository, blockedWorlds = setOf("blocked")).assign(request(worldName = "blocked"))

        assertEquals(BlockDropOwnershipIgnoredReason.DISABLED, assertIgnored(disabled).reason)
        assertEquals(BlockDropOwnershipIgnoredReason.BLOCKED_WORLD, assertIgnored(blocked).reason)
        assertEquals(0, repository.loadCount)
    }

    @Test
    fun `rejects legacy invalid unsupported and repository write failures explicitly`() {
        val legacy = LegacyItemState(age = 0, amount = 1, ownerUuid = null, ownerTime = null)

        assertEquals(
            "LegacyStateRequiresMigration",
            assertRejected(service(RecordingItemStateRepository(ItemStateLoadResult.Legacy(legacy))).assign(request())).reason,
        )
        assertEquals(
            "InvalidExistingState",
            assertRejected(
                service(RecordingItemStateRepository(ItemStateLoadResult.Invalid(listOf("bad")))).assign(request()),
            ).reason,
        )
        assertEquals(
            "UnsupportedSchema:2",
            assertRejected(
                service(RecordingItemStateRepository(ItemStateLoadResult.UnsupportedSchema(2))).assign(request()),
            ).reason,
        )
        val writeFailure = RecordingItemStateRepository(ItemStateLoadResult.Absent, ItemStateWriteResult.Failed("IO"))
        assertEquals("IO", assertFailed(service(writeFailure).assign(request())).errorType)
    }

    private fun service(
        repository: RecordingItemStateRepository,
        ownershipEnabled: Boolean = true,
        blockedWorlds: Set<String> = emptySet(),
    ): BlockDropOwnershipService =
        BlockDropOwnershipService(
            repository = repository,
            settingsRepository =
                ItemDisplaySettingsRepository {
                    ItemDisplaySettings(
                        enabled = true,
                        blockedWorlds = blockedWorlds,
                        singleItemTemplate = template("%item_display_name%"),
                        multipleItemTemplate = template("%item_display_name% x%amount%"),
                        ownership =
                            ItemOwnershipSettings(
                                enabled = ownershipEnabled,
                                protectionSeconds = 30,
                                display =
                                    ItemOwnershipDisplaySettings(
                                        singleOwnerPrefixTemplate = ownerTemplate("[%player_name%] "),
                                    ),
                            ),
                    )
                },
        )

    private fun request(worldName: String = "world"): BlockDropOwnershipRequest =
        BlockDropOwnershipRequest(
            entityId = ENTITY_ID,
            worldName = worldName,
            ownerUuid = OWNER_ID,
        )

    private fun assertIgnored(outcome: BlockDropOwnershipOutcome): BlockDropOwnershipOutcome.Ignored =
        assertInstanceOf(BlockDropOwnershipOutcome.Ignored::class.java, outcome)

    private fun assertRejected(outcome: BlockDropOwnershipOutcome): BlockDropOwnershipOutcome.Rejected =
        assertInstanceOf(BlockDropOwnershipOutcome.Rejected::class.java, outcome)

    private fun assertFailed(outcome: BlockDropOwnershipOutcome): BlockDropOwnershipOutcome.Failed =
        assertInstanceOf(BlockDropOwnershipOutcome.Failed::class.java, outcome)

    private fun template(raw: String): DisplayTemplate =
        assertInstanceOf(DisplayTemplateParseResult.Valid::class.java, DisplayTemplate.parse(raw)).template

    private fun ownerTemplate(raw: String): OwnerDisplayTemplate =
        assertInstanceOf(OwnerDisplayTemplateParseResult.Valid::class.java, OwnerDisplayTemplate.parse(raw)).template

    private class RecordingItemStateRepository(
        private val loadResult: ItemStateLoadResult,
        private val writeResult: ItemStateWriteResult = ItemStateWriteResult.Applied,
    ) : ItemStateRepository {
        var loadCount = 0
        var savedState: ItemState? = null

        override fun load(entityId: UUID): ItemStateLoadResult {
            loadCount++
            return loadResult
        }

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            savedState = state
            return writeResult
        }
    }

    private companion object {
        private val ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val OTHER_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003")
    }
}
