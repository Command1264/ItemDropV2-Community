package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.MinecraftLanguageCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class MinecraftLanguageCatalogParserTest {
    @Test
    fun `keeps only safe item and block translations`() {
        val result =
            MinecraftLanguageCatalogParser().parse(
                MinecraftLanguageCode.parse("zh_tw").getOrThrow(),
                """{"block.minecraft.stone":"石頭","item.minecraft.apple":"蘋果","menu.play":"開始"}"""
                    .toByteArray(Charsets.UTF_8),
            )
        val loaded = assertInstanceOf(MinecraftLanguageParseResult.Loaded::class.java, result)

        assertEquals("石頭", loaded.catalog.translation("block.minecraft.stone"))
        assertEquals("蘋果", loaded.catalog.translation("item.minecraft.apple"))
        assertEquals(null, loaded.catalog.translation("menu.play"))
    }

    @Test
    fun `rejects malformed or non-string language data`() {
        assertInstanceOf(
            MinecraftLanguageParseResult.Invalid::class.java,
            MinecraftLanguageCatalogParser().parse(
                MinecraftLanguageCode.parse("en_us").getOrThrow(),
                """{"item.minecraft.apple":42}""".toByteArray(Charsets.UTF_8),
            ),
        )
    }
}
