package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemNameService
import com.github.command1264.itemdropv2.core.ItemOwnership
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateRepository
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import com.github.command1264.itemdropv2.core.MinecraftItemRarity
import com.github.command1264.itemdropv2.core.MinecraftLanguageRepository
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitItemDisplayRequestTest {
    @Test
    fun `air and non-positive item stacks are silent lifecycle ends`() {
        val warnings = mutableListOf<String>()
        val zeroAmountStack =
            object : ItemStack(Material.STONE) {
                override fun getAmount(): Int = 0

                override fun getItemMeta(): ItemMeta? = null
            }
        val airStack =
            object : ItemStack(Material.AIR) {
                override fun getItemMeta(): ItemMeta? = null
            }

        val requests =
            listOf(zeroAmountStack, airStack).map { stack ->
                createItemDisplayRequest(item(stack), warnings::add)
            }

        assertEquals(listOf(null, null), requests)
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `never pickup transient item does not receive a persistent custom name`() {
        val stack =
            object : ItemStack(Material.STONE) {
                override fun getItemMeta(): ItemMeta? = null
            }

        val request =
            createItemDisplayRequest(
                item(stack, pickupDelay = Short.MAX_VALUE.toInt()),
                DisplayWarningSink { error("unexpected warning: $it") },
            )

        assertNull(request)
    }

    @Test
    fun `uses virtual long amount instead of physical carrier amount`() {
        val request =
            requireNotNull(
                createItemDisplayRequest(
                    item(
                        object : ItemStack(Material.STONE, 1) {
                            override fun getItemMeta(): ItemMeta? = null
                        },
                    ),
                    DisplayWarningSink { error("unexpected warning: $it") },
                    displayStateResolver =
                        ItemDisplayStateResolver {
                            ItemDisplayStateSnapshot(virtualAmount = Long.MAX_VALUE)
                        },
                ),
            )

        assertEquals(Long.MAX_VALUE, request.amount)
    }

    @Test
    fun `uses official translation for unnamed item and preserves custom name priority`() {
        val service = ItemNameService(MinecraftLanguageRepository { "石頭" })
        val warningSink = DisplayWarningSink { error("unexpected warning: $it") }
        val plainStack =
            object : ItemStack(Material.STONE) {
                override fun getItemMeta(): ItemMeta? = null
            }
        val customMeta =
            proxy<ItemMeta> { method ->
                when (method.name) {
                    "hasDisplayName" -> true
                    "getDisplayName" -> "玩家命名"
                    else -> defaultValue(method.returnType)
                }
            }
        val customStack =
            object : ItemStack(Material.STONE) {
                override fun getItemMeta(): ItemMeta = customMeta
            }
        val plain = createItemDisplayRequest(item(plainStack), warningSink, service)
        val custom = createItemDisplayRequest(item(customStack), warningSink, service)

        assertEquals("石頭", plain?.itemName)
        assertEquals("block.minecraft.stone", plain?.translationKey)
        assertEquals("玩家命名", custom?.itemName)
        assertEquals(null, custom?.translationKey)
    }

    @Test
    fun `carries resolved rarity and detects custom legacy color priority`() {
        val coloredMeta =
            proxy<ItemMeta> { method ->
                when (method.name) {
                    "hasDisplayName" -> true
                    "getDisplayName" -> "§a玩家命名"
                    else -> defaultValue(method.returnType)
                }
            }
        val stack =
            object : ItemStack(Material.STONE) {
                override fun getItemMeta(): ItemMeta = coloredMeta
            }

        val request =
            createItemDisplayRequest(
                item(stack),
                DisplayWarningSink { error("unexpected warning: $it") },
                rarityResolver = BukkitItemRarityResolver { MinecraftItemRarity.EPIC },
            )

        assertEquals(MinecraftItemRarity.EPIC, request?.rarity)
        assertEquals(true, request?.customNameHasColor)
    }

    @Test
    fun `detects custom hex color while leaving an uncolored custom name eligible for rarity`() {
        assertEquals(true, hasExplicitItemNameColor("§x§1§2§3§4§5§6彩色"))
        assertEquals(true, hasExplicitItemNameColor("&b彩色"))
        assertEquals(false, hasExplicitItemNameColor("玩家命名"))
    }

    @Test
    fun `resolves persisted owner UUID into the current player name`() {
        val ownerId = UUID.randomUUID()
        val repository =
            object : ItemStateRepository {
                override fun load(entityId: UUID): ItemStateLoadResult =
                    ItemStateLoadResult.Loaded(
                        ItemState(ItemOwnership(ownerId, 40_000), remainingLifetimeSeconds = null),
                    )

                override fun save(
                    entityId: UUID,
                    state: ItemState,
                ): ItemStateWriteResult = error("save must not be called")
            }
        val warningSink = DisplayWarningSink { error("unexpected warning: $it") }
        val resolver = BukkitItemDisplayStateResolver(repository, settingsRepository(), { "Steve" }, warningSink)
        val stack =
            object : ItemStack(Material.STONE) {
                override fun getItemMeta(): ItemMeta? = null
            }

        val request =
            createItemDisplayRequest(
                item(stack),
                warningSink,
                displayStateResolver = resolver,
            )

        assertEquals("Steve", request?.ownerName)
        assertEquals(ownerId, request?.placeholderPlayerId)
        assertEquals(40_000, request?.protectionSecondsRemaining)
        assertEquals(null, request?.lifetimeSecondsRemaining)
    }

    @Test
    fun `rotates through three shared owners and skips unresolved names`() {
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        val third = UUID.randomUUID()
        var protectionSecondsRemaining = 30L
        val repository =
            object : ItemStateRepository {
                override fun load(entityId: UUID): ItemStateLoadResult =
                    ItemStateLoadResult.Loaded(
                        ItemState(
                            ItemOwnership(first, protectionSecondsRemaining, listOf(first, second, third)),
                            remainingLifetimeSeconds = 295,
                        ),
                    )

                override fun save(
                    entityId: UUID,
                    state: ItemState,
                ): ItemStateWriteResult = error("save must not be called")
            }
        val resolver =
            BukkitItemDisplayStateResolver(
                repository,
                settingsRepository(),
                { uuid ->
                    when (uuid) {
                        first -> "Steve"
                        second -> null
                        third -> "Sunny"
                        else -> null
                    }
                },
                DisplayWarningSink { error("unexpected warning: $it") },
            )

        val firstDisplay = resolver.resolve(UUID.randomUUID())
        protectionSecondsRemaining = 25
        val secondDisplay = resolver.resolve(UUID.randomUUID())
        protectionSecondsRemaining = 20
        val thirdDisplay = resolver.resolve(UUID.randomUUID())

        assertEquals("Steve", firstDisplay.owner?.playerName)
        assertEquals(first, firstDisplay.owner?.playerId)
        assertEquals("Sunny", secondDisplay.owner?.playerName)
        assertEquals(third, secondDisplay.owner?.playerId)
        assertEquals("Sunny", thirdDisplay.owner?.playerName)
        assertEquals(third, thirdDisplay.owner?.playerId)
        assertEquals(2, thirdDisplay.owner?.additionalOwnerCount)
    }

    @Test
    fun `resolves protection and remaining lifetime from persisted plugin state`() {
        val ownerId = UUID.randomUUID()
        val repository =
            object : ItemStateRepository {
                override fun load(entityId: UUID): ItemStateLoadResult =
                    ItemStateLoadResult.Loaded(
                        ItemState(
                            ItemOwnership(ownerId, 17),
                            elapsedLifetimeSeconds = 17,
                            originalLifetimeSeconds = 300,
                        ),
                    )

                override fun save(
                    entityId: UUID,
                    state: ItemState,
                ): ItemStateWriteResult = error("save must not be called")
            }
        val resolver =
            BukkitItemDisplayStateResolver(
                repository,
                settingsRepository(),
                { "Steve" },
                DisplayWarningSink { error("unexpected warning: $it") },
            )

        val resolved = resolver.resolve(UUID.randomUUID())

        assertEquals("Steve", resolved.owner?.playerName)
        assertEquals(17, resolved.protectionSecondsRemaining)
        assertEquals(17, resolved.lifetimeSecondsElapsed)
        assertEquals(283, resolved.lifetimeSecondsRemaining)
    }

    private fun settingsRepository() =
        ImmutableItemDisplaySettingsRepository(
            ItemDisplaySettings(
                true,
                emptySet(),
                (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template,
                (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template,
            ),
        )

    private fun item(
        stack: ItemStack,
        pickupDelay: Int = 0,
    ): Item {
        val world = proxy<World> { method -> if (method.name == "getName") "world" else defaultValue(method.returnType) }
        val id = UUID.randomUUID()
        return proxy { method ->
            when (method.name) {
                "getItemStack" -> stack
                "getUniqueId" -> id
                "getWorld" -> world
                "getPickupDelay" -> pickupDelay
                else -> defaultValue(method.returnType)
            }
        }
    }

    private inline fun <reified T> proxy(crossinline answer: (java.lang.reflect.Method) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method)
        } as T

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
