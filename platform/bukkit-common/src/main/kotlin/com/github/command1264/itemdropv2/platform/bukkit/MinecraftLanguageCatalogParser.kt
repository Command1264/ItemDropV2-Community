package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.MinecraftLanguageCatalog
import com.github.command1264.itemdropv2.core.MinecraftLanguageCode
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets

public class MinecraftLanguageCatalogParser {
    @Suppress("ReturnCount")
    public fun parse(
        language: MinecraftLanguageCode,
        bytes: ByteArray,
    ): MinecraftLanguageParseResult {
        if (bytes.isEmpty() || bytes.size > MAX_LANGUAGE_BYTES) {
            return MinecraftLanguageParseResult.Invalid("language data size is outside the allowed range")
        }
        val root =
            try {
                JsonParser.parseString(String(bytes, StandardCharsets.UTF_8))
            } catch (_: JsonParseException) {
                return MinecraftLanguageParseResult.Invalid("language data is not valid JSON")
            } catch (_: RuntimeException) {
                return MinecraftLanguageParseResult.Invalid("language data could not be parsed")
            }
        if (!root.isJsonObject) {
            return MinecraftLanguageParseResult.Invalid("language data must be a JSON object")
        }
        return parseObject(language, root.asJsonObject)
    }

    private fun parseObject(
        language: MinecraftLanguageCode,
        root: JsonObject,
    ): MinecraftLanguageParseResult {
        val translations = linkedMapOf<String, String>()
        for ((key, element) in root.entrySet()) {
            if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
                return MinecraftLanguageParseResult.Invalid("language entry '$key' must be a string")
            }
            if (key.startsWith("item.minecraft.") || key.startsWith("block.minecraft.")) {
                translations[key] = element.asString
            }
        }
        return MinecraftLanguageCatalog.create(language, translations).fold(
            onSuccess = MinecraftLanguageParseResult::Loaded,
            onFailure = { MinecraftLanguageParseResult.Invalid(it.message ?: "invalid language catalog") },
        )
    }

    public companion object {
        public const val MAX_LANGUAGE_BYTES: Int = 2 * 1024 * 1024
    }
}

public sealed interface MinecraftLanguageParseResult {
    public data class Loaded(
        public val catalog: MinecraftLanguageCatalog,
    ) : MinecraftLanguageParseResult

    public data class Invalid(
        public val diagnostic: String,
    ) : MinecraftLanguageParseResult
}
