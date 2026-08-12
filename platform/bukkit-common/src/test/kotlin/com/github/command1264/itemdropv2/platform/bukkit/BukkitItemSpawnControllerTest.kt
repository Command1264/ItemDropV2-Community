package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.DirectItemPresentationView
import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplayService
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemOwnerDisplay
import com.github.command1264.itemdropv2.core.ItemPresentation
import com.github.command1264.itemdropv2.core.ItemPresentationView
import com.github.command1264.itemdropv2.core.PresentationResult
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Item
import org.bukkit.event.entity.ItemSpawnEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitItemSpawnControllerTest {
    @Test
    fun `resolves current ownership state when scheduled presentation executes`() {
        val presentations = mutableListOf<ItemPresentation>()
        val tasks = mutableListOf<() -> Unit>()
        var displayState = ItemDisplayStateSnapshot()
        val item =
            item {
                object : ItemStack(Material.STONE, 1) {
                    override fun getItemMeta(): ItemMeta? = null
                }
            }
        val controller =
            BukkitItemSpawnController(
                service =
                    ItemDisplayService(
                        ItemDisplaySettingsRepository { settings() },
                        ItemPresentationView {
                            presentations += it
                            PresentationResult.Applied
                        },
                    ),
                taskExecutor = MainThreadTaskExecutor(tasks::add),
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                displayStateResolver = ItemDisplayStateResolver { displayState },
            )

        controller.onItemSpawn(ItemSpawnEvent(item))
        displayState =
            ItemDisplayStateSnapshot(
                owner = ItemOwnerDisplay("Command1", 0, OWNER_ID),
                protectionSecondsRemaining = 30,
            )
        tasks.single().invoke()

        assertEquals(1, presentations.size)
        assertEquals(ITEM_ID, presentations.single().entityId)
        assertEquals("&7[&aCommand1&7]&r Stone", presentations.single().text)
    }

    @Test
    fun `presents the retained spawn event item when UUID lookup is not published yet`() {
        val tasks = mutableListOf<() -> Unit>()
        val presentations = mutableListOf<Pair<Item, ItemPresentation>>()
        var canonicalAttempts = 0
        var leaseActive = false
        val eventItem =
            item {
                object : ItemStack(Material.STONE, 1) {
                    override fun getItemMeta(): ItemMeta? = null
                }
            }
        val directView =
            object : DirectItemPresentationView<Item> {
                override fun present(
                    target: Item,
                    presentation: ItemPresentation,
                ): PresentationResult {
                    assertEquals(true, leaseActive)
                    presentations += target to presentation
                    return PresentationResult.Applied
                }

                override fun clear(target: Item): PresentationResult = PresentationResult.Applied
            }
        val controller =
            BukkitItemSpawnController(
                service =
                    ItemDisplayService(
                        ItemDisplaySettingsRepository { settings() },
                        ItemPresentationView {
                            canonicalAttempts += 1
                            if (canonicalAttempts == 1) PresentationResult.MissingTarget else PresentationResult.Applied
                        },
                    ),
                taskExecutor = MainThreadTaskExecutor(tasks::add),
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                directPresentationView = directView,
                transientTargetLeaseFactory =
                    TransientItemTargetLeaseFactory {
                        leaseActive = true
                        TransientItemTargetLease { leaseActive = false }
                    },
            )

        controller.onItemSpawn(ItemSpawnEvent(eventItem))
        while (tasks.isNotEmpty()) tasks.removeFirst().invoke()

        assertSame(eventItem, presentations.single().first)
        assertEquals(2, canonicalAttempts)
        assertEquals(false, leaseActive)
    }

    @Test
    fun `ownership refresher resolves loaded item when direct Bukkit lookup is unavailable`() {
        val presentations = mutableListOf<ItemPresentation>()
        val loadedItem =
            item {
                object : ItemStack(Material.STONE, 1) {
                    override fun getItemMeta(): ItemMeta? = null
                }
            }
        val refresher =
            BukkitItemOwnershipDisplayRefresher(
                service =
                    ItemDisplayService(
                        ItemDisplaySettingsRepository { settings() },
                        ItemPresentationView {
                            presentations += it
                            PresentationResult.Applied
                        },
                    ),
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                displayStateResolver =
                    ItemDisplayStateResolver {
                        ItemDisplayStateSnapshot(
                            owner = ItemOwnerDisplay("Command1", 0, OWNER_ID),
                            protectionSecondsRemaining = 30,
                        )
                    },
                itemResolver = { entityId -> loadedItem.takeIf { it.uniqueId == entityId } },
            )

        refresher.refresh(ITEM_ID)

        assertEquals("&7[&aCommand1&7]&r Stone", presentations.single().text)
    }

    @Test
    fun `ownership refresher presents through the event item without UUID lookup`() {
        val presentations = mutableListOf<Pair<Item, ItemPresentation>>()
        val eventItem =
            item {
                object : ItemStack(Material.STONE, 1) {
                    override fun getItemMeta(): ItemMeta? = null
                }
            }
        val directView =
            object : DirectItemPresentationView<Item> {
                override fun present(
                    target: Item,
                    presentation: ItemPresentation,
                ): PresentationResult {
                    presentations += target to presentation
                    return PresentationResult.Applied
                }

                override fun clear(target: Item): PresentationResult = PresentationResult.Applied
            }
        val refresher =
            BukkitItemOwnershipDisplayRefresher(
                service =
                    ItemDisplayService(
                        ItemDisplaySettingsRepository { settings() },
                        ItemPresentationView { error("UUID presentation lookup must not be used") },
                    ),
                warningSink = DisplayWarningSink { error("unexpected warning: $it") },
                displayStateResolver =
                    ItemDisplayStateResolver {
                        ItemDisplayStateSnapshot(
                            owner = ItemOwnerDisplay("Command1", 0, OWNER_ID),
                            protectionSecondsRemaining = 30,
                        )
                    },
                itemResolver = { error("UUID item lookup must not be used") },
                directPresentationView = directView,
            )

        refresher.refresh(eventItem)

        assertSame(eventItem, presentations.single().first)
        assertEquals("&7[&aCommand1&7]&r Stone", presentations.single().second.text)
    }

    private fun settings(): ItemDisplaySettings {
        val template =
            (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return ItemDisplaySettings(
            enabled = true,
            blockedWorlds = emptySet(),
            singleItemTemplate = template,
            multipleItemTemplate = template,
            rarityDisplayEnabled = false,
        )
    }

    private fun item(stack: () -> ItemStack): Item {
        val world =
            proxy<World> { method ->
                if (method.name == "getName") "world" else defaultValue(method.returnType)
            }
        return proxy { method ->
            when (method.name) {
                "getItemStack" -> stack()
                "getUniqueId" -> ITEM_ID
                "getWorld" -> world
                "getPickupDelay" -> 0
                else -> defaultValue(method.returnType)
            }
        }
    }

    private inline fun <reified T> proxy(crossinline answer: (java.lang.reflect.Method) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method)
        } as T

    private companion object {
        private val ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")

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
