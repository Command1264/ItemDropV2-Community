package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

class BukkitItemLifetimeSettingsFileTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `repairs missing and invalid known values with comments spacing and backup`() {
        val template = template()
        val file = directory.resolve("item-lifetime.yml").toFile()
        file.writeText(
            """
            schema-version: 1
            # custom default explanation
            default-seconds: invalid # keep inline
            materials:
              DIAMOND: -1
            custom-root: keep
            """.trimIndent(),
        )

        val result = store(file, template, "2026-08-11-18-30-45-UTC+08-00").loadAndRepair()

        val loaded = assertInstanceOf(LifetimeFileLoadResult.Loaded::class.java, result)
        val repaired = file.readText()
        assertTrue(repaired.contains("# custom default explanation\ndefault-seconds: 300 # keep inline"))
        assertTrue(repaired.contains("DIAMOND: -1\n\n  # vanilla special lifetime\n  NETHER_STAR: 600"))
        assertTrue(repaired.contains("\n\ncustom-root: keep\n"))
        assertEquals(1, Files.list(directory).use { files -> files.filter { it.fileName.toString().contains("parse-recovery") }.count() })
        assertTrue(directory.resolve("item-lifetime.yml.parse-recovery-2026-08-11-18-30-45-UTC+08-00.bak").toFile().isFile)
        assertEquals("item-lifetime.yml", loaded.recoveryReport?.fileName)
        assertEquals(listOf("default-seconds"), loaded.recoveryReport?.repairedPaths)
    }

    @Test
    fun `backs up malformed lifetime yaml and recreates the raw commented template`() {
        val template = template()
        val file = directory.resolve("item-lifetime.yml").toFile().apply { writeText("materials: [broken") }

        val result = store(file, template).loadAndRepair()

        val loaded = assertInstanceOf(LifetimeFileLoadResult.Loaded::class.java, result)
        assertTrue(file.readText().contains("# default lifetime"))
        assertEquals(1, Files.list(directory).use { files -> files.filter { it.fileName.toString().contains("parse-recovery") }.count() })
        assertEquals(true, loaded.recoveryReport?.documentRecreated)
    }

    @Test
    fun `invalid dynamic material name fails closed without backup or overwrite`() {
        val original = "schema-version: 1\ndefault-seconds: 300\nmaterials:\n  bad-name!: 10\n"
        val file = directory.resolve("item-lifetime.yml").toFile().apply { writeText(original) }

        val result = store(file, template()).loadAndRepair()

        assertInstanceOf(LifetimeFileLoadResult.Failed::class.java, result)
        assertEquals(original, file.readText())
        assertEquals(0, Files.list(directory).use { files -> files.filter { it.fileName.toString().endsWith(".bak") }.count() })
    }

    @Test
    fun `valid lifetime yaml is not rewritten for spacing alone`() {
        val original = "schema-version: 1\ndefault-seconds: 300\nmaterials:\n    NETHER_STAR: 600"
        val file = directory.resolve("item-lifetime.yml").toFile().apply { writeText(original) }

        val result = store(file, template()).loadAndRepair()

        assertInstanceOf(LifetimeFileLoadResult.Loaded::class.java, result)
        assertEquals(original, file.readText())
    }

    @Test
    fun `repairs the only missing material child from its trusted template`() {
        val original =
            "schema-version: 1\ndefault-seconds: 300\nmaterials:\n"
        val file = directory.resolve("item-lifetime.yml").toFile().apply { writeText(original) }

        val result = store(file, template()).loadAndRepair()

        assertInstanceOf(LifetimeFileLoadResult.Loaded::class.java, result)
        assertTrue(file.readText().contains("materials:\n  # vanilla special lifetime\n  NETHER_STAR: 600"))
    }

    @Test
    fun `invalid material lifetime uses the current integer default seconds`() {
        listOf("invalid", "-2").forEachIndexed { index, invalid ->
            val file = directory.resolve("item-lifetime-$index.yml").toFile()
            file.writeText("schema-version: 1\ndefault-seconds: 120\nmaterials:\n  NETHER_STAR: $invalid\n")

            val result = store(file, template()).loadAndRepair()

            assertInstanceOf(LifetimeFileLoadResult.Loaded::class.java, result)
            assertTrue(file.readText().contains("NETHER_STAR: 120"))
        }
    }

    @Test
    fun `invalid default and material lifetimes consistently use the embedded default`() {
        val file = directory.resolve("item-lifetime.yml").toFile()
        file.writeText("schema-version: 1\ndefault-seconds: invalid\nmaterials:\n  NETHER_STAR: invalid\n")

        val result = store(file, template()).loadAndRepair()

        assertInstanceOf(LifetimeFileLoadResult.Loaded::class.java, result)
        assertTrue(file.readText().contains("default-seconds: 300"))
        assertTrue(file.readText().contains("NETHER_STAR: 300"))
    }

    @Test
    fun `unsafe lifetime repair reports its rejection category and detail`() {
        val file = directory.resolve("item-lifetime.yml").toFile()
        file.writeText("schema-version: 1\ndefault-seconds: [300]\nmaterials:\n  NETHER_STAR: 600\n")

        val result = store(file, template()).loadAndRepair()

        val failed = assertInstanceOf(LifetimeFileLoadResult.Failed::class.java, result)
        assertTrue(failed.reason.contains("unsupported_construct"))
        assertTrue(failed.reason.contains("unsupported construct"))
    }

    private fun store(
        file: java.io.File,
        template: String,
        timestamp: String = "2026-08-11-18-30-45-UTC+08-00",
    ): BukkitItemLifetimeSettingsFile =
        BukkitItemLifetimeSettingsFile(
            file = file,
            defaults = YamlConfiguration().apply { loadFromString(template) },
            defaultDocumentBytes = template.toByteArray(StandardCharsets.UTF_8),
            loader = BukkitItemLifetimeSettingsLoader(),
            backupTimestamp = { timestamp },
        )

    private fun template(): String =
        """
        schema-version: 1

        # default lifetime
        default-seconds: 300

        materials:
          # vanilla special lifetime
          NETHER_STAR: 600
        """.trimIndent() + "\n"
}
