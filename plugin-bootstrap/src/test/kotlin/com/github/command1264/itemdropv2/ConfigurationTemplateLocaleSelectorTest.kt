package com.github.command1264.itemdropv2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ConfigurationTemplateLocaleSelectorTest {
    @Test
    fun `Taiwan and China select Traditional Chinese templates case insensitively`() {
        assertEquals(ConfigurationTemplateLocale.ZH_TW, ConfigurationTemplateLocaleSelector.select("TW"))
        assertEquals(ConfigurationTemplateLocale.ZH_TW, ConfigurationTemplateLocaleSelector.select("cn"))
        assertEquals(ConfigurationTemplateLocale.ZH_TW, ConfigurationTemplateLocaleSelector.select(" tw "))
    }

    @Test
    fun `other unknown and missing countries select English templates`() {
        assertEquals(ConfigurationTemplateLocale.EN_US, ConfigurationTemplateLocaleSelector.select("US"))
        assertEquals(ConfigurationTemplateLocale.EN_US, ConfigurationTemplateLocaleSelector.select("HK"))
        assertEquals(ConfigurationTemplateLocale.EN_US, ConfigurationTemplateLocaleSelector.select(""))
        assertEquals(ConfigurationTemplateLocale.EN_US, ConfigurationTemplateLocaleSelector.select(null))
    }
}
