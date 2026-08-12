package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.MinecraftLanguageCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class MinecraftLanguageCacheTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `writes atomically and reads version-scoped language cache`() {
        val cache = MinecraftLanguageCache(directory, MinecraftLanguageCatalogParser())
        val language = MinecraftLanguageCode.parse("zh_tw").getOrThrow()
        val bytes = """{"block.minecraft.stone":"石頭"}""".toByteArray(Charsets.UTF_8)

        assertInstanceOf(MinecraftLanguageCacheWriteResult.Written::class.java, cache.write("26.2", language, bytes))
        val loaded = assertInstanceOf(MinecraftLanguageCacheReadResult.Loaded::class.java, cache.read("26.2", language))

        assertEquals("石頭", loaded.catalog.translation("block.minecraft.stone"))
        assertInstanceOf(
            MinecraftLanguageCacheReadResult.Missing::class.java,
            cache.read("1.14.4", language),
        )
    }
}
