package com.github.command1264.itemdropv2

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.jar.Attributes
import java.util.jar.Manifest

class PluginBuildMetadataTest {
    @Test
    fun `loads traceable clean build metadata`() {
        val configuration =
            YamlConfiguration().apply {
                set("build.git-commit", "f87da43c")
                set("build.git-dirty", "false")
            }

        val metadata =
            PluginBuildMetadata.from(
                configuration,
                manifest(
                    fullCommit = "f87da43c288938b2b2f7f1183421d2a59ead399d",
                    shortCommit = "f87da43c",
                    dirty = "false",
                ),
            )

        assertEquals("f87da43c", metadata.shortCommit)
        assertEquals("f87da43c288938b2b2f7f1183421d2a59ead399d", metadata.fullCommit)
        assertEquals(false, metadata.dirty)
        assertEquals(
            mapOf(
                "plugin.git-commit" to "f87da43c288938b2b2f7f1183421d2a59ead399d",
                "plugin.git-commit-short" to "f87da43c",
                "plugin.git-dirty" to "false",
            ),
            metadata.diagnosticFields(),
        )
        assertEquals("1.0.0-SNAPSHOT (git f87da43c)", metadata.displayVersion("1.0.0-SNAPSHOT"))
    }

    @Test
    fun `marks dirty build in human readable version`() {
        val configuration =
            YamlConfiguration().apply {
                set("build.git-commit", "7123cbe0")
                set("build.git-dirty", "true")
            }

        val metadata =
            PluginBuildMetadata.from(
                configuration,
                manifest(
                    fullCommit = "7123cbe04af822653aa5dcd97faf36286cfcbd4b",
                    shortCommit = "7123cbe0",
                    dirty = "true",
                ),
            )

        assertEquals(true, metadata.dirty)
        assertEquals("1.0.0-SNAPSHOT (git 7123cbe0-dirty)", metadata.displayVersion("1.0.0-SNAPSHOT"))
    }

    @Test
    fun `fails safely when git metadata is unavailable or malformed`() {
        val unavailable =
            YamlConfiguration().apply {
                set("build.git-commit", "unknown")
                set("build.git-dirty", "unknown")
            }
        val malformed =
            YamlConfiguration().apply {
                set("build.git-commit", "../secret")
                set("build.git-dirty", "yes")
            }

        assertEquals(PluginBuildMetadata.UNKNOWN, PluginBuildMetadata.from(unavailable, Manifest()))
        assertEquals(
            PluginBuildMetadata.UNKNOWN,
            PluginBuildMetadata.from(
                malformed,
                manifest(
                    fullCommit = "not-a-commit",
                    shortCommit = "../secret",
                    dirty = "yes",
                ),
            ),
        )
        assertEquals(
            "1.0.0-SNAPSHOT (git unknown)",
            PluginBuildMetadata.from(malformed, Manifest()).displayVersion("1.0.0-SNAPSHOT"),
        )
    }

    @Test
    fun `rejects metadata when public descriptor and private manifest disagree`() {
        val configuration =
            YamlConfiguration().apply {
                set("build.git-commit", "f87da43c")
                set("build.git-dirty", "false")
            }

        val metadata =
            PluginBuildMetadata.from(
                configuration,
                manifest(
                    fullCommit = "7123cbe04af822653aa5dcd97faf36286cfcbd4b",
                    shortCommit = "7123cbe0",
                    dirty = "true",
                ),
            )

        assertEquals(PluginBuildMetadata.UNKNOWN, metadata)
    }

    private fun manifest(
        fullCommit: String,
        shortCommit: String,
        dirty: String,
    ): Manifest =
        Manifest().apply {
            mainAttributes[Attributes.Name("Git-Commit")] = fullCommit
            mainAttributes[Attributes.Name("Git-Commit-Short")] = shortCommit
            mainAttributes[Attributes.Name("Git-Dirty")] = dirty
        }
}
