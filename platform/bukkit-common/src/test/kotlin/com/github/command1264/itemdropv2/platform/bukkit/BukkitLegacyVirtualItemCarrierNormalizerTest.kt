package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import com.github.command1264.itemdropv2.core.VirtualItemAmount
import com.github.command1264.itemdropv2.core.VirtualItemStackingSettings
import org.bukkit.Material
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitLegacyVirtualItemCarrierNormalizerTest {
    @Test
    fun `ordinary managed item remains non virtual`() {
        val repository = RecordingRepository(ItemState(null, 0, 300))
        val fixture = item(ItemStack(Material.STONE, 32))

        val outcome = normalizer(repository).normalize(fixture.item)

        assertEquals(VirtualItemCarrierNormalizationOutcome.Unmanaged, outcome)
        assertEquals(0, repository.saveCalls)
        assertEquals(32, fixture.stack.amount)
    }

    @Test
    fun `existing virtual carrier remains one entity without increasing count`() {
        val repository = RecordingRepository(ItemState(null, 0, 300, VirtualItemAmount.of(1_728)))
        val fixture = item(ItemStack(Material.STONE, 1))

        val outcome = normalizer(repository).normalize(fixture.item)

        val normalized = assertInstanceOf(VirtualItemCarrierNormalizationOutcome.Normalized::class.java, outcome)
        assertEquals(1_728L, normalized.virtualAmount.value)
        assertEquals(false, normalized.migrated)
        assertEquals(0, repository.saveCalls)
        assertEquals(1, fixture.stack.amount)
    }

    @Test
    fun `legacy normalizer rejects invalid state without writing`() {
        val repository = RecordingRepository(ItemStateLoadResult.Invalid(listOf("virtual amount malformed")))
        val fixture = item(ItemStack(Material.STONE, 1))

        val outcome = normalizer(repository).normalize(fixture.item)

        assertEquals(VirtualItemCarrierNormalizationOutcome.Rejected("InvalidState"), outcome)
        assertEquals(0, repository.saveCalls)
    }

    @Test
    fun `legacy normalizer rejects bukkit access away from main thread`() {
        val repository = RecordingRepository(ItemState(null, 0, 300, VirtualItemAmount.of(128)))
        val fixture = item(ItemStack(Material.STONE, 1))

        val outcome = BukkitLegacyVirtualItemCarrierNormalizer(repository, settings(), { false }).normalize(fixture.item)

        assertEquals(VirtualItemCarrierNormalizationOutcome.Failed("AsyncBukkitAccess"), outcome)
        assertEquals(0, repository.saveCalls)
    }

    private fun normalizer(repository: ItemStateRepository): BukkitLegacyVirtualItemCarrierNormalizer =
        BukkitLegacyVirtualItemCarrierNormalizer(repository, settings(), { true })

    private fun settings(): ItemDisplaySettingsRepository {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return ItemDisplaySettingsRepository {
            ItemDisplaySettings(
                enabled = true,
                blockedWorlds = emptySet(),
                singleItemTemplate = template,
                multipleItemTemplate = template,
                virtualStacking =
                    VirtualItemStackingSettings(
                        enabled = true,
                        maximumAmountPerEntity = VirtualItemAmount.of(8_192),
                    ),
            )
        }
    }

    private fun item(initial: ItemStack): ItemFixture {
        var stack = initial
        val item =
            Proxy.newProxyInstance(Item::class.java.classLoader, arrayOf(Item::class.java)) { _, method, arguments ->
                when (method.name) {
                    "getUniqueId" -> ENTITY_ID
                    "getItemStack" -> stack
                    "setItemStack" -> {
                        stack = arguments.orEmpty().single() as ItemStack
                        null
                    }
                    else -> null
                }
            } as Item
        return ItemFixture(item) { stack }
    }

    private class ItemFixture(
        val item: Item,
        private val stackProvider: () -> ItemStack,
    ) {
        val stack: ItemStack
            get() = stackProvider()
    }

    private class RecordingRepository(
        private val loadResult: ItemStateLoadResult,
    ) : ItemStateRepository {
        constructor(state: ItemState) : this(ItemStateLoadResult.Loaded(state))

        var saveCalls: Int = 0
            private set

        override fun load(entityId: UUID): ItemStateLoadResult = loadResult

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult {
            saveCalls++
            return ItemStateWriteResult.Applied
        }
    }

    private companion object {
        private val ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000061")
    }
}
