package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class ItemDisplayServiceTest {
    private val entityId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `renders single and multiple item formats through the view port`() {
        val view = RecordingView()
        val service = service(view = view)

        service.display(request(amount = 1))
        service.display(request(amount = 4))

        assertEquals(
            listOf(
                ItemPresentation(entityId, "Stone", true),
                ItemPresentation(entityId, "Stone x4", true),
            ),
            view.presentations,
        )
    }

    @Test
    fun `preserves a fallback string and a marked client translation presentation`() {
        val view = RecordingView()
        val service = service(view = view, rarityDisplayEnabled = true)

        service.display(
            request(
                amount = 2,
                rarity = MinecraftItemRarity.RARE,
                translationKey = "block.minecraft.stone",
            ),
        )

        assertEquals(
            ItemPresentation(
                entityId = entityId,
                text = "&bStone x2",
                visible = true,
                clientTranslation =
                    ItemClientTranslation(
                        translationKey = "block.minecraft.stone",
                        markedText = "&b${ItemClientTranslation.MARKER} x2",
                    ),
            ),
            view.presentations.single(),
        )
    }

    @Test
    fun `uses Minecraft rarity color when rarity display is enabled`() {
        val view = RecordingView()
        val service = service(view = view, rarityDisplayEnabled = true)

        service.display(request(rarity = MinecraftItemRarity.EPIC))

        assertEquals(ItemPresentation(entityId, "&dStone", true), view.presentations.single())
    }

    @Test
    fun `does not use rarity color when rarity display is disabled`() {
        val view = RecordingView()
        val service = service(view = view, rarityDisplayEnabled = false)

        service.display(request(rarity = MinecraftItemRarity.RARE))

        assertEquals(ItemPresentation(entityId, "Stone", true), view.presentations.single())
    }

    @Test
    fun `custom item name color takes priority over rarity color`() {
        val view = RecordingView()
        val service = service(view = view, rarityDisplayEnabled = true)

        service.display(
            request(
                itemName = "§a玩家命名",
                rarity = MinecraftItemRarity.EPIC,
                customNameHasColor = true,
            ),
        )

        assertEquals(ItemPresentation(entityId, "§a玩家命名", true), view.presentations.single())
    }

    @Test
    fun `prepends configured owner name without changing base item templates`() {
        val view = RecordingView()
        val service = service(view = view)

        service.display(request(ownerName = "Steve"))

        assertEquals(ItemPresentation(entityId, "[Steve] Stone", true), view.presentations.single())
    }

    @Test
    fun `shows additional eligible owner count with the shared prefix`() {
        val view = RecordingView()
        val service = service(view = view)

        service.display(request(ownerName = "Alex", additionalOwnerCount = 2))

        assertEquals(ItemPresentation(entityId, "[Alex]+2 Stone", true), view.presentations.single())
    }

    @Test
    fun `renders formatted protection remaining in single and multiple owner prefixes`() {
        val view = RecordingView()
        val service =
            service(
                view = view,
                singleOwnerRaw = "[%protection_remaining%][%player_name%] ",
                multipleOwnerRaw =
                    "[%protection_remaining%][%player_name%]+%additional_owner_count% ",
            )

        service.display(request(ownerName = "Steve", protectionSecondsRemaining = 65))
        service.display(
            request(
                ownerName = "Alex",
                additionalOwnerCount = 2,
                protectionSecondsRemaining = 65,
            ),
        )

        assertEquals(
            listOf(
                ItemPresentation(entityId, "[1:05][Steve] Stone", true),
                ItemPresentation(entityId, "[1:05][Alex]+2 Stone", true),
            ),
            view.presentations,
        )
    }

    @Test
    fun `renders owner protection and finite lifetime placeholders before external expansion`() {
        val view = RecordingView()
        val expander =
            ItemDisplayTextExpander { _, text ->
                assertEquals("[Steve]+2 Stone Steve 3 2 1:05 1 01:01:01 1-01 01:01:01", text)
                "$text expanded"
            }
        val service =
            service(
                view = view,
                textExpander = expander,
                singleRaw =
                    "%item_display_name% %owner% %owner_count% %other_owner_count% " +
                        "%protection_remaining% %lifetime_elapsed% %lifetime_remaining%",
            )

        service.display(
            request(
                ownerName = "Steve",
                additionalOwnerCount = 2,
                protectionSecondsRemaining = 65,
                lifetimeSecondsElapsed = 90_061,
                lifetimeSecondsRemaining = 2_682_061,
            ),
        )

        assertEquals(
            ItemPresentation(
                entityId,
                "[Steve]+2 Stone Steve 3 2 1:05 1 01:01:01 1-01 01:01:01 expanded",
                true,
            ),
            view.presentations.single(),
        )
    }

    @Test
    fun `uses configured labels for absent owner and non-finite lifetime states`() {
        val unknownView = RecordingView()
        val permanentView = RecordingView()
        val raw = "%item_display_name% %owner% %owner_count% %protection_remaining% %lifetime_remaining%"

        service(view = unknownView, singleRaw = raw)
            .display(request(lifetimeSecondsRemaining = null))
        service(view = permanentView, singleRaw = raw)
            .display(request(lifetimeSecondsRemaining = ItemLifetimeSettings.NEVER_EXPIRES))

        assertEquals(ItemPresentation(entityId, "Stone 無 0 0 未知", true), unknownView.presentations.single())
        assertEquals(ItemPresentation(entityId, "Stone 無 0 0 永久", true), permanentView.presentations.single())
    }

    @Test
    fun `expands external placeholders after internal rendering with owner context`() {
        val ownerId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val view = RecordingView()
        val expander =
            ItemDisplayTextExpander { playerId, text ->
                assertEquals(ownerId, playerId)
                text.replace("%player_world%", "world")
            }
        val service = service(view = view, textExpander = expander, singleRaw = "%item_display_name% %player_world%")

        service.display(request(ownerName = "Steve", placeholderPlayerId = ownerId))

        assertEquals(ItemPresentation(entityId, "[Steve] Stone world", true), view.presentations.single())
    }

    @Test
    fun `rejects unsafe text produced by an external placeholder expander`() {
        val service =
            service(
                textExpander = ItemDisplayTextExpander { _, _ -> "unsafe\ntext" },
            )

        val outcome = service.display(request())

        assertEquals(
            "expanded presentation must not contain control characters",
            assertInstanceOf(ItemDisplayOutcome.Rejected::class.java, outcome).reason,
        )
    }

    @Test
    fun `rejects overlong text produced by an external placeholder expander`() {
        val service =
            service(
                textExpander = ItemDisplayTextExpander { _, _ -> "x".repeat(DisplayTemplate.MAX_LENGTH + 1) },
            )

        val outcome = service.display(request())

        assertEquals(
            "expanded presentation exceeds 256 characters",
            assertInstanceOf(ItemDisplayOutcome.Rejected::class.java, outcome).reason,
        )
    }

    @Test
    fun `clears plugin presentation when display is disabled or world is blocked`() {
        val disabledView = RecordingView()
        val blockedView = RecordingView()

        val disabled = service(enabled = false, view = disabledView).display(request())
        val blocked =
            service(blockedWorlds = setOf("world_nether"), view = blockedView)
                .display(request(worldName = "world_nether"))

        assertEquals(ItemDisplayIgnoredReason.DISABLED, assertCleared(disabled).reason)
        assertEquals(ItemDisplayIgnoredReason.BLOCKED_WORLD, assertCleared(blocked).reason)
        assertEquals(listOf(entityId), disabledView.clearedEntityIds)
        assertEquals(listOf(entityId), blockedView.clearedEntityIds)
    }

    @Test
    fun `returns the explicit view result`() {
        val view = RecordingView(PresentationResult.MissingTarget)

        val outcome = service(view = view).display(request())

        assertEquals(
            PresentationResult.MissingTarget,
            assertInstanceOf(ItemDisplayOutcome.Presented::class.java, outcome).result,
        )
    }

    @Test
    fun `validates request boundaries`() {
        assertThrows(IllegalArgumentException::class.java) { request(amount = 0) }
        assertThrows(IllegalArgumentException::class.java) { request(worldName = " ") }
        assertThrows(IllegalArgumentException::class.java) { request(itemName = "") }
    }

    private fun service(
        enabled: Boolean = true,
        blockedWorlds: Set<String> = emptySet(),
        view: RecordingView = RecordingView(),
        textExpander: ItemDisplayTextExpander = ItemDisplayTextExpander { _, text -> text },
        singleRaw: String = "%item_display_name%",
        rarityDisplayEnabled: Boolean = false,
        singleOwnerRaw: String = "[%player_name%] ",
        multipleOwnerRaw: String = "[%player_name%]+%additional_owner_count% ",
    ): ItemDisplayService {
        val single = validTemplate(singleRaw)
        val multiple = validTemplate("%item_display_name% x%amount%")
        val ownerPrefix =
            assertInstanceOf(
                OwnerDisplayTemplateParseResult.Valid::class.java,
                OwnerDisplayTemplate.parse(singleOwnerRaw),
            ).template
        val settings =
            ItemDisplaySettings(
                enabled,
                blockedWorlds,
                single,
                multiple,
                rarityDisplayEnabled = rarityDisplayEnabled,
                ownership =
                    ItemOwnershipSettings(
                        display =
                            ItemOwnershipDisplaySettings(
                                singleOwnerPrefixTemplate = ownerPrefix,
                                multipleOwnersPrefixTemplate = ownerTemplate(multipleOwnerRaw),
                            ),
                    ),
                placeholders =
                    ItemDisplayPlaceholderSettings(
                        noOwner = "無",
                        lifetimePermanent = "永久",
                        lifetimeUnknown = "未知",
                    ),
            )
        return ItemDisplayService(ItemDisplaySettingsRepository { settings }, view, textExpander)
    }

    private fun request(
        worldName: String = "world",
        itemName: String = "Stone",
        amount: Long = 1,
        ownerName: String? = null,
        additionalOwnerCount: Int = 0,
        placeholderPlayerId: UUID? = null,
        rarity: MinecraftItemRarity = MinecraftItemRarity.COMMON,
        customNameHasColor: Boolean = false,
        protectionSecondsRemaining: Long = 0,
        lifetimeSecondsElapsed: Long? = null,
        lifetimeSecondsRemaining: Long? = null,
        translationKey: String? = null,
    ): ItemDisplayRequest =
        ItemDisplayRequest(
            entityId = entityId,
            worldName = worldName,
            itemName = itemName,
            amount = amount,
            ownerName = ownerName,
            additionalOwnerCount = additionalOwnerCount,
            placeholderPlayerId = placeholderPlayerId,
            rarity = rarity,
            customNameHasColor = customNameHasColor,
            protectionSecondsRemaining = protectionSecondsRemaining,
            lifetimeSecondsRemaining = lifetimeSecondsRemaining,
            translationKey = translationKey,
            lifetimeSecondsElapsed = lifetimeSecondsElapsed,
        )

    private fun ownerTemplate(raw: String): OwnerDisplayTemplate =
        assertInstanceOf(OwnerDisplayTemplateParseResult.Valid::class.java, OwnerDisplayTemplate.parse(raw)).template

    private fun validTemplate(raw: String): DisplayTemplate =
        assertInstanceOf(DisplayTemplateParseResult.Valid::class.java, DisplayTemplate.parse(raw)).template

    private fun assertCleared(outcome: ItemDisplayOutcome): ItemDisplayOutcome.Cleared =
        assertInstanceOf(ItemDisplayOutcome.Cleared::class.java, outcome)

    private class RecordingView(
        private val result: PresentationResult = PresentationResult.Applied,
    ) : ItemPresentationView {
        val presentations = mutableListOf<ItemPresentation>()
        val clearedEntityIds = mutableListOf<UUID>()

        override fun present(presentation: ItemPresentation): PresentationResult {
            presentations += presentation
            return result
        }

        override fun clear(entityId: UUID): PresentationResult {
            clearedEntityIds += entityId
            return result
        }
    }
}
