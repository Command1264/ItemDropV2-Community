package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.core.ItemDisplaySettingsUpdateResult
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class RuntimeEditionConfigurationFactoryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `Community defaults omit Pro virtual stacking settings`() {
        val runtime =
            RuntimeConfigurationFactory(
                javaClass.classLoader,
                directory.toFile(),
                virtualStackingSettingSupported = false,
                countryCodeProvider = { "US" },
            ).create()

        val applied = assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, runtime.settingsManager.reload())
        val generated = Files.readAllBytes(directory.resolve("config.yml")).toString(StandardCharsets.UTF_8)

        assertEquals(false, applied.settings.virtualStacking.enabled)
        assertTrue(!generated.contains("virtual-stacking:"), generated)
    }

    @Test
    fun `Community preserves and ignores malformed Pro virtual stacking settings`() {
        val base = embeddedConfigBytes().toString(StandardCharsets.UTF_8)
        val original =
            base
                .replace(
                    "items:\n",
                    "items:\n" +
                        "  virtual-stacking:\n" +
                        "    enabled: definitely-not-a-boolean\n" +
                        "    maximum-native-stacks-per-entity: broken\n\n",
                ).toByteArray(StandardCharsets.UTF_8)
        Files.write(directory.resolve("config.yml"), original)
        val runtime =
            RuntimeConfigurationFactory(
                javaClass.classLoader,
                directory.toFile(),
                virtualStackingSettingSupported = false,
                countryCodeProvider = { "TW" },
            ).create()

        val applied = assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, runtime.settingsManager.reload())

        assertEquals(false, applied.settings.virtualStacking.enabled)
        assertArrayEquals(original, Files.readAllBytes(directory.resolve("config.yml")))
    }

    @Test
    fun `Pro defaults compose localized presentation and virtual stacking fragments`() {
        val loader =
            OverrideResourcesClassLoader(
                mapOf(
                    ENGLISH_PRO_FRAGMENT_RESOURCE to ENGLISH_PRO_FRAGMENT.toByteArray(StandardCharsets.UTF_8),
                    ENGLISH_VIRTUAL_STACKING_FRAGMENT_RESOURCE to
                        ENGLISH_VIRTUAL_STACKING_FRAGMENT.toByteArray(StandardCharsets.UTF_8),
                ),
            )
        val runtime =
            RuntimeConfigurationFactory(
                loader,
                directory.toFile(),
                paperClientSideTranslationSettingSupported = true,
                virtualStackingSettingSupported = true,
                configurationFragmentResources =
                    listOf(PRO_FRAGMENT_RESOURCE, VIRTUAL_STACKING_FRAGMENT_RESOURCE),
                countryCodeProvider = { "US" },
            ).create()

        val applied = assertInstanceOf(ItemDisplaySettingsUpdateResult.Applied::class.java, runtime.settingsManager.reload())
        val generated = Files.readAllBytes(directory.resolve("config.yml")).toString(StandardCharsets.UTF_8)

        assertEquals(false, applied.settings.virtualStacking.enabled)
        assertTrue(generated.contains("Whether Paper 1.16.5+ lets each client"), generated)
        assertTrue(generated.contains("Pro Virtual Stacking settings."), generated)
        assertTrue(generated.contains("virtual-stacking:"), generated)
    }

    private fun embeddedConfigBytes(): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(CONFIG_RESOURCE))
            .use(InputStream::readBytes)

    private class OverrideResourcesClassLoader(
        resources: Map<String, ByteArray>,
    ) : ClassLoader(RuntimeEditionConfigurationFactoryTest::class.java.classLoader) {
        private val resources = resources.mapValues { (_, bytes) -> bytes.copyOf() }

        override fun getResourceAsStream(name: String): InputStream? =
            resources[name]?.let(::ByteArrayInputStream) ?: super.getResourceAsStream(name)
    }

    private companion object {
        const val CONFIG_RESOURCE = "config/config.yml"
        const val PRO_FRAGMENT_RESOURCE = "config/pro-paper-client-side-translation.yml.fragment"
        const val ENGLISH_PRO_FRAGMENT_RESOURCE = "config/pro-paper-client-side-translation.en_us.yml.fragment"
        const val VIRTUAL_STACKING_FRAGMENT_RESOURCE = "config/pro-virtual-stacking.yml.fragment"
        const val ENGLISH_VIRTUAL_STACKING_FRAGMENT_RESOURCE = "config/pro-virtual-stacking.en_us.yml.fragment"
        const val ENGLISH_PRO_FRAGMENT =
            "  display:\n" +
                "    # Whether Paper 1.16.5+ lets each client translate vanilla item names.\n" +
                "    paper-client-side-translation: true\n"
        const val ENGLISH_VIRTUAL_STACKING_FRAGMENT =
            "  virtual-stacking:\n" +
                "    # Pro Virtual Stacking settings.\n" +
                "    enabled: false\n" +
                "    carrier-amount-mode: proportional\n" +
                "    maximum-native-stacks-per-entity: 128\n" +
                "    unstackable-items:\n" +
                "      enabled: false\n" +
                "    merge:\n" +
                "      comparisons-per-tick: 256\n" +
                "      events-per-tick: 64\n" +
                "      retry-backoff-seconds: 5\n"
    }
}
