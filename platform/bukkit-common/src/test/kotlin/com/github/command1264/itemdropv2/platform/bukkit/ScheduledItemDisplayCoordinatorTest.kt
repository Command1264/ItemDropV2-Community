package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplayRequest
import com.github.command1264.itemdropv2.core.ItemDisplayService
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemPresentation
import com.github.command1264.itemdropv2.core.ItemPresentationView
import com.github.command1264.itemdropv2.core.PresentationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.UUID

class ScheduledItemDisplayCoordinatorTest {
    @Test
    fun `waits for the owned main-thread task before resolving the entity`() {
        val presentations = mutableListOf<ItemPresentation>()
        val pendingTasks = mutableListOf<() -> Unit>()
        val coordinator =
            coordinator(
                view =
                    ItemPresentationView { presentation ->
                        presentations += presentation
                        PresentationResult.Applied
                    },
                executor = MainThreadTaskExecutor(pendingTasks::add),
            )

        coordinator.submit(request())

        assertEquals(emptyList<ItemPresentation>(), presentations)
        assertEquals(1, pendingTasks.size)

        pendingTasks.single().invoke()

        assertEquals(listOf(ItemPresentation(ENTITY_ID, "Stone", true)), presentations)
    }

    @Test
    fun `reports scheduling failure without running the service`() {
        val warnings = mutableListOf<String>()
        val coordinator =
            coordinator(
                view = ItemPresentationView { error("view must not be called") },
                executor = MainThreadTaskExecutor { throw IllegalStateException("scheduler stopped") },
                warnings = warnings,
            )

        coordinator.submit(request())

        assertEquals(listOf("item presentation scheduling failed (IllegalStateException)"), warnings)
    }

    @Test
    fun `reads the surviving item amount after the merge event has completed`() {
        val presentations = mutableListOf<ItemPresentation>()
        val pendingTasks = mutableListOf<() -> Unit>()
        var survivingAmount = 1L
        val coordinator =
            coordinator(
                view =
                    ItemPresentationView { presentation ->
                        presentations += presentation
                        PresentationResult.Applied
                    },
                executor = MainThreadTaskExecutor(pendingTasks::add),
            )

        coordinator.submitDeferred { request(amount = survivingAmount) }
        survivingAmount = 2
        pendingTasks.removeFirst().invoke()

        coordinator.submitDeferred { request(amount = survivingAmount) }
        survivingAmount = 5
        pendingTasks.removeFirst().invoke()

        assertEquals(
            listOf(
                ItemPresentation(ENTITY_ID, "Stone x2", true),
                ItemPresentation(ENTITY_ID, "Stone x5", true),
            ),
            presentations,
        )
    }

    @Test
    fun `ignores a merge refresh when the surviving item no longer exists`() {
        val presentations = mutableListOf<ItemPresentation>()
        val pendingTasks = mutableListOf<() -> Unit>()
        val coordinator =
            coordinator(
                view =
                    ItemPresentationView { presentation ->
                        presentations += presentation
                        PresentationResult.Applied
                    },
                executor = MainThreadTaskExecutor(pendingTasks::add),
            )

        coordinator.submitDeferred { null }
        pendingTasks.single().invoke()

        assertEquals(emptyList<ItemPresentation>(), presentations)
    }

    private fun coordinator(
        view: ItemPresentationView,
        executor: MainThreadTaskExecutor,
        warnings: MutableList<String> = mutableListOf(),
    ): ScheduledItemDisplayCoordinator {
        val singleTemplate =
            assertInstanceOf(
                DisplayTemplateParseResult.Valid::class.java,
                DisplayTemplate.parse("%item_display_name%"),
            ).template
        val multipleTemplate =
            assertInstanceOf(
                DisplayTemplateParseResult.Valid::class.java,
                DisplayTemplate.parse("%item_display_name% x%amount%"),
            ).template
        val settings =
            ItemDisplaySettings(
                enabled = true,
                blockedWorlds = emptySet(),
                singleItemTemplate = singleTemplate,
                multipleItemTemplate = multipleTemplate,
                rarityDisplayEnabled = false,
            )
        val service = ItemDisplayService(ItemDisplaySettingsRepository { settings }, view)
        return ScheduledItemDisplayCoordinator(service, executor, DisplayWarningSink(warnings::add))
    }

    private fun request(amount: Long = 1): ItemDisplayRequest = ItemDisplayRequest(ENTITY_ID, "world", "Stone", amount)

    private companion object {
        private val ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
