package com.github.command1264.itemdropv2.core

import java.util.Locale

@JvmInline
public value class MinecraftLanguageCode private constructor(
    public val value: String,
) {
    public companion object {
        public val EN_US: MinecraftLanguageCode = MinecraftLanguageCode("en_us")

        public fun parse(raw: String): Result<MinecraftLanguageCode> =
            runCatching {
                val normalized = raw.trim().lowercase(Locale.ROOT)
                require(LANGUAGE_CODE.matches(normalized)) {
                    "Minecraft language must match ${LANGUAGE_CODE.pattern}"
                }
                MinecraftLanguageCode(normalized)
            }

        private val LANGUAGE_CODE = Regex("[a-z0-9]{2,16}_[a-z0-9]{2,16}")
    }
}

public class MinecraftLanguageCatalog private constructor(
    public val language: MinecraftLanguageCode,
    translations: Map<String, String>,
) {
    private val values: Map<String, String> = translations.toMap()

    public fun translation(key: String): String? = values[key]

    public val size: Int
        get() = values.size

    public companion object {
        public fun create(
            language: MinecraftLanguageCode,
            translations: Map<String, String>,
        ): Result<MinecraftLanguageCatalog> =
            runCatching {
                require(translations.size <= MAX_TRANSLATIONS) { "too many Minecraft translations" }
                translations.forEach { (key, value) ->
                    require(TRANSLATION_KEY.matches(key)) { "invalid Minecraft translation key" }
                    require(value.isNotBlank()) { "Minecraft translation must not be blank" }
                    require(value.length <= MAX_TRANSLATION_LENGTH) { "Minecraft translation is too long" }
                    require(value.none(Char::isISOControl)) {
                        "Minecraft translation must not contain control characters"
                    }
                }
                MinecraftLanguageCatalog(language, translations)
            }

        private val TRANSLATION_KEY = Regex("(?:block|item)\\.minecraft\\.[a-z0-9_.-]{1,220}")
        private const val MAX_TRANSLATIONS = 100_000
        private const val MAX_TRANSLATION_LENGTH = 256
    }
}

public fun interface MinecraftLanguageRepository {
    public fun translation(key: String): String?
}

public data class ItemNameRequest(
    public val customName: String?,
    public val translationKey: String,
    public val fallbackName: String,
) {
    init {
        require(customName == null || customName.isNotBlank()) { "customName must be null or non-blank" }
        require(translationKey.isNotBlank()) { "translationKey must not be blank" }
        require(fallbackName.isNotBlank()) { "fallbackName must not be blank" }
    }
}

public class ItemNameService(
    private val languageRepository: MinecraftLanguageRepository,
) {
    public fun resolve(request: ItemNameRequest): String =
        request.customName
            ?: languageRepository.translation(request.translationKey)
            ?: request.fallbackName
}
