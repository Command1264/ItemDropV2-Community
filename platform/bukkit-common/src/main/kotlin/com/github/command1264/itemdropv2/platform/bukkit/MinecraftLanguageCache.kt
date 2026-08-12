package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.MinecraftLanguageCatalog
import com.github.command1264.itemdropv2.core.MinecraftLanguageCode
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

public class MinecraftLanguageCache(
    private val directory: Path,
    private val parser: MinecraftLanguageCatalogParser,
) {
    public fun read(
        minecraftVersion: String,
        language: MinecraftLanguageCode,
    ): MinecraftLanguageCacheReadResult {
        val target = target(minecraftVersion, language)
        if (!Files.isRegularFile(target)) return MinecraftLanguageCacheReadResult.Missing
        return try {
            val size = Files.size(target)
            if (size <= 0 || size > MinecraftLanguageCatalogParser.MAX_LANGUAGE_BYTES) {
                MinecraftLanguageCacheReadResult.Invalid("cached language data size is invalid")
            } else {
                when (val result = parser.parse(language, Files.readAllBytes(target))) {
                    is MinecraftLanguageParseResult.Loaded -> MinecraftLanguageCacheReadResult.Loaded(result.catalog)
                    is MinecraftLanguageParseResult.Invalid -> MinecraftLanguageCacheReadResult.Invalid(result.diagnostic)
                }
            }
        } catch (error: IOException) {
            MinecraftLanguageCacheReadResult.Invalid("cached language data could not be read (${error.javaClass.simpleName})")
        } catch (error: SecurityException) {
            MinecraftLanguageCacheReadResult.Invalid(
                "cached language data access was denied (${error.javaClass.simpleName})",
            )
        }
    }

    public fun write(
        minecraftVersion: String,
        language: MinecraftLanguageCode,
        bytes: ByteArray,
    ): MinecraftLanguageCacheWriteResult {
        if (parser.parse(language, bytes) is MinecraftLanguageParseResult.Invalid) {
            return MinecraftLanguageCacheWriteResult.Failed("refused to cache invalid language data")
        }
        val target = target(minecraftVersion, language)
        var temporary: Path? = null
        return try {
            Files.createDirectories(directory)
            temporary = Files.createTempFile(directory, ".itemdrop-language-", ".tmp")
            Files.write(temporary, bytes)
            moveAtomically(temporary, target)
            temporary = null
            MinecraftLanguageCacheWriteResult.Written(target)
        } catch (error: IOException) {
            MinecraftLanguageCacheWriteResult.Failed("language cache could not be written (${error.javaClass.simpleName})")
        } catch (error: SecurityException) {
            MinecraftLanguageCacheWriteResult.Failed("language cache access was denied (${error.javaClass.simpleName})")
        } finally {
            temporary?.let { path ->
                try {
                    Files.deleteIfExists(path)
                } catch (_: IOException) {
                    // The bounded temporary file is left for a later administrator cleanup.
                }
            }
        }
    }

    private fun target(
        minecraftVersion: String,
        language: MinecraftLanguageCode,
    ): Path {
        require(VERSION_ID.matches(minecraftVersion)) { "invalid Minecraft version for cache path" }
        return directory.resolve("minecraft-$minecraftVersion-${language.value}.json")
    }

    private fun moveAtomically(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        private val VERSION_ID = Regex("[0-9A-Za-z._-]{1,64}")
    }
}

public sealed interface MinecraftLanguageCacheReadResult {
    public data object Missing : MinecraftLanguageCacheReadResult

    public data class Loaded(
        public val catalog: MinecraftLanguageCatalog,
    ) : MinecraftLanguageCacheReadResult

    public data class Invalid(
        public val diagnostic: String,
    ) : MinecraftLanguageCacheReadResult
}

public sealed interface MinecraftLanguageCacheWriteResult {
    public data class Written(
        public val path: Path,
    ) : MinecraftLanguageCacheWriteResult

    public data class Failed(
        public val diagnostic: String,
    ) : MinecraftLanguageCacheWriteResult
}
