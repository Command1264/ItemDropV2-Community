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

class ExistingItemRefreshCoordinatorTest {
    @Test
    fun `processes bounded batches and deduplicates item ids`() {
        val fixture = fixture(maxChunksPerTick = 1, maxItemsPerTick = 1)
        fixture.chunkItems[CHUNK_A] = listOf(ITEM_A, ITEM_B)
        fixture.chunkItems[CHUNK_B] = listOf(ITEM_B, ITEM_C)

        fixture.coordinator.requestChunks(listOf(CHUNK_A, CHUNK_B, CHUNK_A))

        assertEquals(1, fixture.tasks.size)
        fixture.runNextTask()
        assertEquals(1, fixture.presentations.size)
        assertEquals(1, fixture.tasks.size)

        fixture.runAllTasks()

        assertEquals(listOf(ITEM_A, ITEM_B, ITEM_C), fixture.presentations.map(ItemPresentation::entityId))
    }

    @Test
    fun `refreshes an existing item again after the language catalog changes`() {
        val fixture = fixture()
        fixture.chunkItems[CHUNK_A] = listOf(ITEM_A)

        fixture.itemName = "Stone"
        fixture.coordinator.requestChunks(listOf(CHUNK_A))
        fixture.runAllTasks()

        fixture.itemName = "石頭"
        fixture.coordinator.requestChunks(listOf(CHUNK_A))
        fixture.runAllTasks()

        assertEquals(listOf("Stone", "石頭"), fixture.presentations.map(ItemPresentation::text))
    }

    @Test
    fun `close prevents queued and future refresh work`() {
        val fixture = fixture()
        fixture.chunkItems[CHUNK_A] = listOf(ITEM_A)
        fixture.coordinator.requestChunks(listOf(CHUNK_A))

        fixture.coordinator.close()
        fixture.runAllTasks()
        fixture.coordinator.requestChunks(listOf(CHUNK_A))

        assertEquals(emptyList<ItemPresentation>(), fixture.presentations)
        assertEquals(0, fixture.tasks.size)
    }

    @Test
    fun `reports chunk resolution failures and continues with later chunks`() {
        val fixture = fixture()
        fixture.failingChunks += CHUNK_A
        fixture.chunkItems[CHUNK_B] = listOf(ITEM_B)

        fixture.coordinator.requestChunks(listOf(CHUNK_A, CHUNK_B))
        fixture.runAllTasks()

        assertEquals(listOf(ITEM_B), fixture.presentations.map(ItemPresentation::entityId))
        assertEquals(listOf("existing item chunk refresh failed (IllegalStateException)"), fixture.warnings)
    }

    @Test
    fun `reports item resolution failures and continues with later items`() {
        val fixture = fixture()
        fixture.chunkItems[CHUNK_A] = listOf(ITEM_A, ITEM_B)
        fixture.failingItems += ITEM_A

        fixture.coordinator.requestChunks(listOf(CHUNK_A))
        fixture.runAllTasks()

        assertEquals(listOf(ITEM_B), fixture.presentations.map(ItemPresentation::entityId))
        assertEquals(listOf("existing item refresh failed (IllegalStateException)"), fixture.warnings)
    }

    private fun fixture(
        maxChunksPerTick: Int = 8,
        maxItemsPerTick: Int = 100,
    ): Fixture {
        val singleTemplate =
            assertInstanceOf(
                DisplayTemplateParseResult.Valid::class.java,
                DisplayTemplate.parse("%item_display_name%"),
            ).template
        val settings =
            ItemDisplaySettings(
                enabled = true,
                blockedWorlds = emptySet(),
                singleItemTemplate = singleTemplate,
                multipleItemTemplate = singleTemplate,
                rarityDisplayEnabled = false,
            )
        val presentations = mutableListOf<ItemPresentation>()
        val tasks = mutableListOf<() -> Unit>()
        val warnings = mutableListOf<String>()
        val chunkItems = mutableMapOf<LoadedChunkReference, List<UUID>>()
        val failingChunks = mutableSetOf<LoadedChunkReference>()
        val failingItems = mutableSetOf<UUID>()
        var fixture: Fixture? = null
        val service =
            ItemDisplayService(
                ItemDisplaySettingsRepository { settings },
                ItemPresentationView { presentation ->
                    presentations += presentation
                    PresentationResult.Applied
                },
            )
        val coordinator =
            ExistingItemRefreshCoordinator(
                service = service,
                taskExecutor = MainThreadTaskExecutor(tasks::add),
                warningSink = DisplayWarningSink(warnings::add),
                chunkItemResolver =
                    LoadedChunkItemResolver { chunk ->
                        if (chunk in failingChunks) error("chunk unavailable")
                        chunkItems[chunk].orEmpty()
                    },
                requestResolver =
                    ItemDisplayRequestResolver { entityId ->
                        if (entityId in failingItems) error("item unavailable")
                        ItemDisplayRequest(entityId, "world", requireNotNull(fixture).itemName, 1)
                    },
                maxChunksPerTick = maxChunksPerTick,
                maxItemsPerTick = maxItemsPerTick,
            )
        return Fixture(coordinator, tasks, presentations, warnings, chunkItems, failingChunks, failingItems).also {
            fixture = it
        }
    }

    private data class Fixture(
        val coordinator: ExistingItemRefreshCoordinator,
        val tasks: MutableList<() -> Unit>,
        val presentations: MutableList<ItemPresentation>,
        val warnings: MutableList<String>,
        val chunkItems: MutableMap<LoadedChunkReference, List<UUID>>,
        val failingChunks: MutableSet<LoadedChunkReference>,
        val failingItems: MutableSet<UUID>,
        var itemName: String = "Stone",
    ) {
        fun runNextTask() {
            tasks.removeFirst().invoke()
        }

        fun runAllTasks() {
            while (tasks.isNotEmpty()) runNextTask()
        }
    }

    private companion object {
        private val CHUNK_A = LoadedChunkReference("world", 0, 0)
        private val CHUNK_B = LoadedChunkReference("world", 1, 0)
        private val ITEM_A = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val ITEM_B = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val ITEM_C = UUID.fromString("00000000-0000-0000-0000-000000000003")
    }
}
