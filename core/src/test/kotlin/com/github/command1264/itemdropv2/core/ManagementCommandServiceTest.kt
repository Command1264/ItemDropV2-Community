package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class ManagementCommandServiceTest {
    @Test
    fun `toggle inverts state and refreshes only after persistence succeeds`() {
        val repository = FakeSettingsManager(settings(enabled = true))
        val refresh = RecordingRefreshView()
        val service = ManagementCommandService(repository, refresh)

        assertInstanceOf(ManagementCommandOutcome.Disabled::class.java, service.toggle(null))
        assertEquals(false, repository.settings().enabled)
        assertEquals(1, refresh.count)

        assertInstanceOf(ManagementCommandOutcome.Enabled::class.java, service.toggle(null))
        assertEquals(true, repository.settings().enabled)
        assertEquals(2, refresh.count)
    }

    @Test
    fun `explicit toggle reports already state without writing or refreshing`() {
        val repository = FakeSettingsManager(settings(enabled = true))
        val refresh = RecordingRefreshView()
        val service = ManagementCommandService(repository, refresh)

        assertInstanceOf(ManagementCommandOutcome.AlreadyEnabled::class.java, service.toggle(true))

        assertEquals(0, repository.writeCount)
        assertEquals(0, refresh.count)
    }

    @Test
    fun `failed toggle retains runtime state and does not refresh`() {
        val repository = FakeSettingsManager(settings(enabled = true), failWrites = true)
        val refresh = RecordingRefreshView()
        val service = ManagementCommandService(repository, refresh)

        val outcome = service.toggle(false)

        assertEquals("disk failure", assertInstanceOf(ManagementCommandOutcome.Failed::class.java, outcome).reason)
        assertEquals(true, repository.settings().enabled)
        assertEquals(0, refresh.count)
    }

    @Test
    fun `reload applies validated settings then refreshes loaded items`() {
        val repository = FakeSettingsManager(settings(enabled = true), reloadValue = settings(enabled = false))
        val refresh = RecordingRefreshView()
        val service = ManagementCommandService(repository, refresh)

        assertInstanceOf(ManagementCommandOutcome.Reloaded::class.java, service.reload())

        assertEquals(false, repository.settings().enabled)
        assertEquals(1, refresh.count)
    }

    private fun settings(enabled: Boolean): ItemDisplaySettings =
        ItemDisplaySettings(
            enabled = enabled,
            blockedWorlds = emptySet(),
            singleItemTemplate = validTemplate("%item_display_name%"),
            multipleItemTemplate = validTemplate("%item_display_name% x%amount%"),
        )

    private fun validTemplate(raw: String): DisplayTemplate = (DisplayTemplate.parse(raw) as DisplayTemplateParseResult.Valid).template

    private class FakeSettingsManager(
        initial: ItemDisplaySettings,
        private val failWrites: Boolean = false,
        private val reloadValue: ItemDisplaySettings = initial,
    ) : ItemDisplaySettingsManager {
        private var value = initial
        var writeCount = 0

        override fun settings(): ItemDisplaySettings = value

        override fun setEnabled(enabled: Boolean): ItemDisplaySettingsUpdateResult {
            writeCount++
            if (failWrites) return ItemDisplaySettingsUpdateResult.Failed("disk failure")
            value = value.copy(enabled = enabled)
            return ItemDisplaySettingsUpdateResult.Applied(value)
        }

        override fun reload(): ItemDisplaySettingsUpdateResult {
            value = reloadValue
            return ItemDisplaySettingsUpdateResult.Applied(value)
        }
    }

    private class RecordingRefreshView : LoadedItemRefreshView {
        var count = 0

        override fun refresh(settings: ItemDisplaySettings) {
            count++
        }
    }
}
