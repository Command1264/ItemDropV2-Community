package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ItemNameServiceTest {
    @Test
    fun `validates Minecraft language codes`() {
        assertEquals("zh_tw", MinecraftLanguageCode.parse("ZH_tw").getOrThrow().value)
        assertTrue(MinecraftLanguageCode.parse("../../secret").isFailure)
        assertTrue(MinecraftLanguageCode.parse("zh-tw").isFailure)
    }

    @Test
    fun `prefers custom name then official translation then fallback`() {
        val repository =
            MinecraftLanguageRepository { key ->
                if (key == "block.minecraft.stone") "石頭" else null
            }
        val service = ItemNameService(repository)

        assertEquals("玩家命名", service.resolve(ItemNameRequest("玩家命名", "block.minecraft.stone", "Stone")))
        assertEquals("石頭", service.resolve(ItemNameRequest(null, "block.minecraft.stone", "Stone")))
        assertEquals("Diamond Sword", service.resolve(ItemNameRequest(null, "item.minecraft.diamond_sword", "Diamond Sword")))
    }

    @Test
    fun `catalog rejects malformed entries and exposes immutable translations`() {
        val catalog =
            MinecraftLanguageCatalog
                .create(
                    MinecraftLanguageCode.parse("zh_tw").getOrThrow(),
                    mapOf("block.minecraft.stone" to "石頭"),
                ).getOrThrow()

        assertEquals("石頭", catalog.translation("block.minecraft.stone"))
        assertNull(catalog.translation("item.minecraft.missing"))
        assertTrue(
            MinecraftLanguageCatalog
                .create(
                    MinecraftLanguageCode.parse("zh_tw").getOrThrow(),
                    mapOf("../bad" to "值"),
                ).isFailure,
        )
    }
}
