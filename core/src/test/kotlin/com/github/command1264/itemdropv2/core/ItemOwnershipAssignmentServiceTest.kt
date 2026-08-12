package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class ItemOwnershipAssignmentServiceTest {
    @Test
    fun `persists ordered eligible owners without changing lifetime or virtual amount`() {
        val repository =
            RecordingItemStateRepository(
                ItemState(
                    ownership = null,
                    elapsedLifetimeSeconds = 12,
                    originalLifetimeSeconds = 60,
                    virtualAmount = VirtualItemAmount.of(3),
                ),
            )
        val service = ItemOwnershipAssignmentService(repository, ItemDisplaySettingsRepository(::settings))

        val result = service.assign(ItemOwnershipAssignmentRequest(ENTITY, "world", listOf(FIRST, SECOND)))

        assertInstanceOf(ItemOwnershipAssignmentOutcome.Assigned::class.java, result)
        assertEquals(listOf(FIRST, SECOND), repository.state?.ownership?.eligibleOwnerUuids)
        assertEquals(48, repository.state?.remainingLifetimeSeconds)
        assertEquals(VirtualItemAmount.of(3), repository.state?.virtualAmount)
    }

    private fun settings(): ItemDisplaySettings {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return ItemDisplaySettings(true, emptySet(), template, template)
    }

    private class RecordingItemStateRepository(
        initial: ItemState?,
    ) : ItemStateRepository {
        var state: ItemState? = initial

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
        private val ENTITY = UUID.fromString("00000000-0000-0000-0000-000000000010")
        private val FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002")
    }
}
