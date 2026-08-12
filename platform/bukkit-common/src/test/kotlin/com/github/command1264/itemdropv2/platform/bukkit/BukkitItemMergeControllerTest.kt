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

class BukkitItemMergeControllerTest {
    @Test
    fun `refreshes the target entity with its post-merge amount`() {
        val pendingTasks = mutableListOf<() -> Unit>()
        val presentations = mutableListOf<ItemPresentation>()
        var amount = 1L
        val controller =
            BukkitItemMergeController(
                service = service(presentations),
                taskExecutor = MainThreadTaskExecutor(pendingTasks::add),
                warningSink = DisplayWarningSink { error(it) },
                requestResolver =
                    ItemDisplayRequestResolver { entityId ->
                        ItemDisplayRequest(entityId, "world", "Stone", amount)
                    },
            )

        controller.refreshAfterMerge(ENTITY_ID)
        amount = 2
        pendingTasks.removeFirst().invoke()
        controller.refreshAfterMerge(ENTITY_ID)
        amount = 6
        pendingTasks.removeFirst().invoke()

        assertEquals(
            listOf(
                ItemPresentation(ENTITY_ID, "Stone x2", true),
                ItemPresentation(ENTITY_ID, "Stone x6", true),
            ),
            presentations,
        )
    }

    private fun service(presentations: MutableList<ItemPresentation>): ItemDisplayService {
        val single = validTemplate("%item_display_name%")
        val multiple = validTemplate("%item_display_name% x%amount%")
        val settings =
            ItemDisplaySettings(
                enabled = true,
                blockedWorlds = emptySet(),
                singleItemTemplate = single,
                multipleItemTemplate = multiple,
                rarityDisplayEnabled = false,
            )
        return ItemDisplayService(
            ItemDisplaySettingsRepository { settings },
            ItemPresentationView { presentation ->
                presentations += presentation
                PresentationResult.Applied
            },
        )
    }

    private fun validTemplate(raw: String): DisplayTemplate =
        assertInstanceOf(DisplayTemplateParseResult.Valid::class.java, DisplayTemplate.parse(raw)).template

    private companion object {
        private val ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
