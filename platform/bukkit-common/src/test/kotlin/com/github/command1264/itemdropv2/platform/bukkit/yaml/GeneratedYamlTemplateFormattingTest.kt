package com.github.command1264.itemdropv2.platform.bukkit.yaml

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class GeneratedYamlTemplateFormattingTest {
    @Test
    fun `all administrator-facing yaml templates follow the mapping tree policy`() {
        val root = repositoryRoot()
        GENERATED_YAML_TEMPLATES.forEach { relative ->
            val original = Files.readAllBytes(root.resolve(relative))
            val candidate = (MappingTreeSpacingPolicy().apply(original) as YamlDocumentEditResult.Candidate).bytes
            assertArrayEquals(original, candidate, "$relative must remain byte-idempotent under the mapping tree policy")
        }
    }

    private fun repositoryRoot(): Path {
        var current = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.isDirectory(current.resolve("plugin-bootstrap"))) {
            current = requireNotNull(current.parent) { "repository root not found" }
        }
        return current
    }

    private companion object {
        val GENERATED_YAML_TEMPLATES =
            listOf(
                "plugin-bootstrap/src/main/resources/config/config.yml",
                "plugin-bootstrap/src/main/resources/config/config.en_us.yml",
                "plugin-bootstrap/src/main/resources/config/item-lifetime.yml",
                "plugin-bootstrap/src/main/resources/config/item-lifetime.en_us.yml",
                "plugin-bootstrap/src/main/resources/config/languages/en_us.yml",
                "plugin-bootstrap/src/main/resources/config/languages/zh_tw.yml",
            )
    }
}
