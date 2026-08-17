package com.github.command1264.itemdropv2

import java.util.Locale

internal enum class ConfigurationTemplateLocale(
    val configResource: String,
    val lifetimeResource: String,
) {
    ZH_TW(
        configResource = "config/config.yml",
        lifetimeResource = "config/item-lifetime.yml",
    ),
    EN_US(
        configResource = "config/config.en_us.yml",
        lifetimeResource = "config/item-lifetime.en_us.yml",
    ),
    ;

    fun localizeFragment(resource: String): String =
        when (this) {
            ZH_TW -> resource
            EN_US -> {
                require(resource.endsWith(YAML_FRAGMENT_SUFFIX)) {
                    "configuration fragment must end with $YAML_FRAGMENT_SUFFIX"
                }
                resource.removeSuffix(YAML_FRAGMENT_SUFFIX) + ENGLISH_YAML_FRAGMENT_SUFFIX
            }
        }

    private companion object {
        private const val YAML_FRAGMENT_SUFFIX = ".yml.fragment"
        private const val ENGLISH_YAML_FRAGMENT_SUFFIX = ".en_us.yml.fragment"
    }
}

internal object ConfigurationTemplateLocaleSelector {
    fun select(countryCode: String?): ConfigurationTemplateLocale =
        when (countryCode?.trim()?.uppercase(Locale.ROOT)) {
            "TW", "CN" -> ConfigurationTemplateLocale.ZH_TW
            else -> ConfigurationTemplateLocale.EN_US
        }
}
