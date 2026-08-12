package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ItemLifetimeServiceTest {
    @Test
    fun `register creates plugin lifetime state in seconds`() {
        val repository = RecordingRepository()

        val outcome = service(repository).register(ENTITY_ID)

        val registered = assertInstanceOf(ItemLifetimeRegistrationOutcome.Registered::class.java, outcome)
        assertEquals(ItemState(null, remainingLifetimeSeconds = 300), registered.state)
        assertEquals(registered.state, repository.states[ENTITY_ID])
    }

    @Test
    fun `material override supports immediate forever and Long max lifetimes`() {
        val repository = RecordingRepository()
        val settings =
            lifetimeSettings(
                default = Long.MAX_VALUE,
                overrides = mapOf("STONE" to 0, "DIAMOND" to -1),
            )
        val service = service(repository, settings)

        assertEquals(ItemLifetimeRegistrationOutcome.ItemExpired, service.register(ENTITY_ID, "STONE"))
        assertTrue(repository.savedStates.isEmpty())

        val forever =
            assertInstanceOf(
                ItemLifetimeRegistrationOutcome.Registered::class.java,
                service.register(OTHER_ID, "DIAMOND"),
            )
        assertEquals(-1, forever.state.remainingLifetimeSeconds)

        val maximum =
            assertInstanceOf(
                ItemLifetimeRegistrationOutcome.Registered::class.java,
                service.register(THIRD_ID, "DIRT"),
            )
        assertEquals(Long.MAX_VALUE, maximum.state.remainingLifetimeSeconds)
    }

    @Test
    fun `forever lifetime never expires and finite countdown does not overflow`() {
        val repository =
            RecordingRepository(
                mutableMapOf(
                    ENTITY_ID to ItemState(null, Long.MAX_VALUE, -1),
                    OTHER_ID to ItemState(null, Long.MAX_VALUE - 1, Long.MAX_VALUE),
                ),
            )
        val service = service(repository)

        assertEquals(ItemLifetimeProcessingOutcome.Active, service.processSecond(ENTITY_ID))
        assertEquals(-1, repository.states.getValue(ENTITY_ID).remainingLifetimeSeconds)
        assertEquals(ItemLifetimeProcessingOutcome.ItemExpired, service.processSecond(OTHER_ID))
    }

    @Test
    fun `one processing cycle advances age by one second`() {
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(null, 10, 100)))

        assertEquals(ItemLifetimeProcessingOutcome.Active, service(repository).processSecond(ENTITY_ID))

        assertEquals(ItemState(null, 11, 100), repository.states[ENTITY_ID])
    }

    @Test
    fun `requests a display refresh each second when a time placeholder is configured`() {
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(null, 10, 100)))

        val outcome =
            service(
                repository,
                templateRaw = "%item_display_name% %lifetime_remaining%",
            ).processSecond(ENTITY_ID)

        assertEquals(ItemLifetimeProcessingOutcome.DisplayTimeChanged, outcome)
        assertEquals(ItemState(null, 11, 100), repository.states[ENTITY_ID])
    }

    @Test
    fun `refreshes changing protection but not permanent lifetime text`() {
        val repository =
            RecordingRepository(
                mutableMapOf(
                    ENTITY_ID to ItemState(ItemOwnership(OWNER_ID, 10), 0, -1),
                    OTHER_ID to ItemState(null, 0, -1),
                ),
            )

        assertEquals(
            ItemLifetimeProcessingOutcome.DisplayTimeChanged,
            service(repository, templateRaw = "%item_display_name% %protection_remaining%")
                .processSecond(ENTITY_ID),
        )
        assertEquals(
            ItemLifetimeProcessingOutcome.Active,
            service(repository, templateRaw = "%item_display_name% %lifetime_remaining%")
                .processSecond(OTHER_ID),
        )
    }

    @Test
    fun `refreshes permanent item custom name when elapsed lifetime is displayed`() {
        val repository =
            RecordingRepository(
                mutableMapOf(
                    ENTITY_ID to ItemState(null, 42, ItemLifetimeSettings.NEVER_EXPIRES),
                ),
            )

        assertEquals(
            ItemLifetimeProcessingOutcome.DisplayTimeChanged,
            service(repository, templateRaw = "%item_display_name% %lifetime_elapsed%")
                .processSecond(ENTITY_ID),
        )
        assertEquals(43L, repository.states.getValue(ENTITY_ID).elapsedLifetimeSeconds)
        assertEquals(
            ItemLifetimeSettings.NEVER_EXPIRES,
            repository.states.getValue(ENTITY_ID).remainingLifetimeSeconds,
        )
    }

    @Test
    fun `refreshes changing protection used only by the owner prefix`() {
        val repository =
            RecordingRepository(
                mutableMapOf(
                    ENTITY_ID to ItemState(ItemOwnership(OWNER_ID, 10), 0, -1),
                ),
            )

        assertEquals(
            ItemLifetimeProcessingOutcome.DisplayTimeChanged,
            service(
                repository,
                ownerTemplateRaw = "[%player_name%] %protection_remaining%s ",
            ).processSecond(ENTITY_ID),
        )
    }

    @Test
    fun `owner protection expires on the thirtieth processed second`() {
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(ItemOwnership(OWNER_ID, 30), 0, 300)))
        val service = service(repository)

        repeat(29) { assertEquals(ItemLifetimeProcessingOutcome.Active, service.processSecond(ENTITY_ID)) }
        assertEquals(ItemLifetimeProcessingOutcome.OwnershipExpired, service.processSecond(ENTITY_ID))
        assertEquals(null, repository.states.getValue(ENTITY_ID).ownership)
        assertEquals(270, repository.states.getValue(ENTITY_ID).remainingLifetimeSeconds)
    }

    @Test
    fun `item expires on its configured second`() {
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(null, 8, 10)))
        val service = service(repository)

        assertEquals(ItemLifetimeProcessingOutcome.Active, service.processSecond(ENTITY_ID))
        assertEquals(ItemLifetimeProcessingOutcome.ItemExpired, service.processSecond(ENTITY_ID))
    }

    @Test
    fun `requests a display refresh when shared owner rotation advances`() {
        val ownership = ItemOwnership(OWNER_ID, 26, listOf(OWNER_ID, OTHER_ID))
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to ItemState(ownership, remainingLifetimeSeconds = 296)))

        assertEquals(ItemLifetimeProcessingOutcome.OwnershipDisplayChanged, service(repository).processSecond(ENTITY_ID))
        assertEquals(295, repository.states.getValue(ENTITY_ID).remainingLifetimeSeconds)
    }

    @Test
    fun `owner expiry makes the item merge-compatible with an unowned item`() {
        val otherId = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val repository =
            RecordingRepository(
                mutableMapOf(
                    ENTITY_ID to ItemState(ItemOwnership(OWNER_ID, 1), 0, 300),
                    otherId to ItemState(null, 0, 300),
                ),
            )
        val lifetimeService = service(repository)

        assertEquals(ItemLifetimeProcessingOutcome.OwnershipExpired, lifetimeService.processSecond(ENTITY_ID))
        val merged = ItemMergeService(repository, settingsRepository()).merge(ENTITY_ID, otherId)

        assertInstanceOf(ItemMergeOutcome.Merged::class.java, merged)
    }

    @Test
    fun `legacy seconds migrate without tick conversion`() {
        val repository = RecordingRepository()
        repository.loadOverrides[ENTITY_ID] = ItemStateLoadResult.Legacy(LegacyItemState(12, 1, OWNER_ID, 18))

        val migrated = assertInstanceOf(ItemLifetimeRegistrationOutcome.Registered::class.java, service(repository).register(ENTITY_ID))

        assertEquals(ItemState(ItemOwnership(OWNER_ID, 18), 12, 300), migrated.state)
    }

    @Test
    fun `overage legacy item expires on its first recovered processing second`() {
        val repository = RecordingRepository()
        repository.loadOverrides[ENTITY_ID] = ItemStateLoadResult.Legacy(LegacyItemState(301, 1, null, null))
        val service = service(repository)

        assertEquals(ItemLifetimeRegistrationOutcome.ItemExpired, service.register(ENTITY_ID))
        assertEquals(null, repository.states[ENTITY_ID])
    }

    @Test
    fun `register rewrites older loaded schemas while preserving converted state`() {
        val converted = ItemState(ItemOwnership(OWNER_ID, 18), 12, 120)
        val repository = RecordingRepository()
        repository.loadOverrides[ENTITY_ID] = ItemStateLoadResult.Loaded(converted, requiresSchemaUpgrade = true)

        val registered = assertInstanceOf(ItemLifetimeRegistrationOutcome.Registered::class.java, service(repository).register(ENTITY_ID))

        assertEquals(converted, registered.state)
        assertEquals(listOf(converted), repository.savedStates)
    }

    @Test
    fun `register gives recovered state an explicit default lifetime`() {
        val repository = RecordingRepository()
        repository.loadOverrides[ENTITY_ID] =
            ItemStateLoadResult.Loaded(
                ItemState(
                    null,
                    elapsedLifetimeSeconds = 301,
                    originalLifetimeSeconds = null,
                ),
            )

        val registered = assertInstanceOf(ItemLifetimeRegistrationOutcome.Registered::class.java, service(repository).register(ENTITY_ID))

        assertEquals(ItemState(null, remainingLifetimeSeconds = 300), registered.state)
        assertEquals(listOf(ItemState(null, remainingLifetimeSeconds = 300)), repository.savedStates)
    }

    @Test
    fun `expired incomplete legacy schema is removed instead of persisting zero`() {
        val repository = RecordingRepository()
        repository.loadOverrides[ENTITY_ID] =
            ItemStateLoadResult.Loaded(
                state = ItemState(null, remainingLifetimeSeconds = null),
                requiresSchemaUpgrade = true,
                elapsedSecondsForLifetimeMigration = 301,
            )

        assertEquals(ItemLifetimeRegistrationOutcome.ItemExpired, service(repository).register(ENTITY_ID))
        assertTrue(repository.savedStates.isEmpty())
    }

    @Test
    fun `incomplete legacy schema with zero-second material policy expires before state construction`() {
        val repository = RecordingRepository()
        repository.loadOverrides[ENTITY_ID] =
            ItemStateLoadResult.Loaded(
                state = ItemState(null, remainingLifetimeSeconds = null),
                requiresSchemaUpgrade = true,
                elapsedSecondsForLifetimeMigration = 130,
            )
        val lifetime = ItemLifetimeSettings(materialLifetimeSeconds = mapOf("DIAMOND" to 0))

        assertEquals(
            ItemLifetimeRegistrationOutcome.ItemExpired,
            service(repository, lifetime).register(ENTITY_ID, "DIAMOND"),
        )
        assertTrue(repository.savedStates.isEmpty())
    }

    @Test
    fun `processing incomplete schema with zero-second default expires before state construction`() {
        val repository = RecordingRepository()
        repository.loadOverrides[ENTITY_ID] =
            ItemStateLoadResult.Loaded(
                state = ItemState(null, remainingLifetimeSeconds = null),
                requiresSchemaUpgrade = true,
                elapsedSecondsForLifetimeMigration = 1,
            )
        val lifetime = ItemLifetimeSettings(defaultLifetimeSeconds = 0)

        assertEquals(
            ItemLifetimeProcessingOutcome.ItemExpired,
            service(repository, lifetime).processSecond(ENTITY_ID),
        )
        assertTrue(repository.savedStates.isEmpty())
    }

    @Test
    fun `register does not rewrite current complete state`() {
        val current = ItemState(null, 12, 300)
        val repository = RecordingRepository(mutableMapOf(ENTITY_ID to current))

        assertEquals(ItemLifetimeRegistrationOutcome.Registered(current), service(repository).register(ENTITY_ID))

        assertTrue(repository.savedStates.isEmpty())
    }

    private fun service(
        repository: RecordingRepository,
        lifetime: ItemLifetimeSettings = ItemLifetimeSettings(),
        templateRaw: String = "%item_display_name%",
        ownerTemplateRaw: String = "[%player_name%] ",
    ): ItemLifetimeService {
        val template = (DisplayTemplate.parse(templateRaw) as DisplayTemplateParseResult.Valid).template
        val ownerTemplate =
            (OwnerDisplayTemplate.parse(ownerTemplateRaw) as OwnerDisplayTemplateParseResult.Valid).template
        val settings =
            ItemDisplaySettings(
                true,
                emptySet(),
                template,
                template,
                lifetime = lifetime,
                ownership =
                    ItemOwnershipSettings(
                        display =
                            ItemOwnershipDisplaySettings(
                                singleOwnerPrefixTemplate = ownerTemplate,
                                multipleOwnersPrefixTemplate =
                                    ownerTemplate(
                                        "[%player_name%]+%additional_owner_count% ",
                                    ),
                            ),
                    ),
            )
        return ItemLifetimeService(repository, ItemDisplaySettingsRepository { settings })
    }

    private fun ownerTemplate(raw: String): OwnerDisplayTemplate =
        (OwnerDisplayTemplate.parse(raw) as OwnerDisplayTemplateParseResult.Valid).template

    private fun lifetimeSettings(
        default: Long,
        overrides: Map<String, Long>,
    ): ItemLifetimeSettings = ItemLifetimeSettings(default, overrides)

    private fun settingsRepository(): ItemDisplaySettingsRepository {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return ItemDisplaySettingsRepository { ItemDisplaySettings(true, emptySet(), template, template) }
    }

    private class RecordingRepository(
        val states: MutableMap<UUID, ItemState> = mutableMapOf(),
    ) : ItemStateRepository {
        val loadOverrides = mutableMapOf<UUID, ItemStateLoadResult>()
        val savedStates = mutableListOf<ItemState>()

        override fun load(entityId: UUID): ItemStateLoadResult =
            loadOverrides.remove(entityId) ?: states[entityId]?.let(ItemStateLoadResult::Loaded) ?: ItemStateLoadResult.Absent

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            states[entityId] = state
            savedStates += state
            return ItemStateWriteResult.Applied
        }
    }

    private companion object {
        private val ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003")
        private val THIRD_ID = UUID.fromString("00000000-0000-0000-0000-000000000004")
    }
}
