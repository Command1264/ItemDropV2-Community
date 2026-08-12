package com.github.command1264.itemdropv2.platform.bukkit.yaml

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.nio.charset.StandardCharsets

class YamlDocumentScannerTest {
    private val scanner: YamlDocumentScanner = StrictYamlDocumentScanner()

    @Test
    fun `path accepts root and nested plain segments`() {
        val root = YamlPath.parse("general")
        val nested = YamlPath.of("general", "enabled")

        assertEquals(listOf("general"), root.segments)
        assertEquals("general.enabled", nested.dotted)
        assertEquals(root, nested.parent())
    }

    @Test
    fun `path rejects empty dot and control character segments`() {
        listOf("", ".", "general..enabled", "general.\u0007enabled").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { YamlPath.parse(path) }
        }
        assertThrows(IllegalArgumentException::class.java) { YamlPath.of("general.enabled") }
    }

    @Test
    fun `scanner preserves bom newline spans and comment ownership`() {
        val source =
            "\uFEFF# heading\r\ngeneral:\r\n" +
                "  # owned\r\n" +
                "  enabled: true # inline\r\n" +
                "\r\n" +
                "  # owned after section separator\r\n" +
                "  language: zh_TW\r\n" +
                "  # standalone\r\n" +
                "\r\n" +
                "  locale: en_US\r\n"

        val result = scanner.scan(source.toByteArray(StandardCharsets.UTF_8))

        val document = assertInstanceOf<YamlDocumentScanResult.Scanned>(result).document
        assertTrue(document.hasUtf8Bom)
        assertEquals(YamlNewline.CRLF, document.newline)
        assertEquals(
            listOf("  # owned"),
            document.entry(YamlPath.parse("general.enabled")).ownedLeadingComments,
        )
        assertNotNull(document.entry(YamlPath.parse("general.enabled")).inlineComment)
        assertEquals(
            listOf("  # owned after section separator"),
            document.entry(YamlPath.parse("general.language")).ownedLeadingComments,
        )
        assertTrue(document.entry(YamlPath.parse("general.locale")).ownedLeadingComments.isEmpty())
        assertEquals(
            "\r\n".toByteArray(StandardCharsets.UTF_8).toList(),
            document.lines
                .first()
                .newlineBytes
                .toList(),
        )
    }

    @Test
    fun `scanner indexes root and nested mappings with lf and no bom`() {
        val document = scanned("general:\n  enabled: true\n  language: zh_TW  \n")

        assertFalse(document.hasUtf8Bom)
        assertEquals(YamlNewline.LF, document.newline)
        assertEquals(listOf("general", "general.enabled", "general.language"), document.entries.map { it.path.dotted })
        assertEquals("zh_TW", document.entry(YamlPath.parse("general.language")).valueText)
        assertEquals("  language: zh_TW  ", document.lines.last().text)
    }

    @Test
    fun `scanner indexes a plain key with formatting whitespace before its colon`() {
        val document = scanned("enabled :  true # explanation\n")
        val entry = document.entry(YamlPath.parse("enabled"))

        assertEquals("true", entry.valueText)
        assertEquals(YamlByteSpan(0, 7), entry.keySpan)
        assertEquals(YamlByteSpan(11, 15), entry.valueSpan)
    }

    @Test
    fun `scanner excludes empty and unsupported key subtrees from the editable path index`() {
        val emptyKeyDocument = scanned(": value\n")
        val unsupportedKeyDocument = scanned("root:\n  \"foreign\":\n    enabled: keep\n")

        assertTrue(emptyKeyDocument.entries.isEmpty())
        assertTrue(
            emptyKeyDocument.lines
                .single()
                .constructFlags
                .contains(YamlConstructFlag.UNSUPPORTED_MAPPING_KEY),
        )
        assertEquals(null, unsupportedKeyDocument.entryOrNull(YamlPath.parse("root.enabled")))
        assertTrue(
            unsupportedKeyDocument.entry(YamlPath.parse("root")).constructFlags.contains(YamlConstructFlag.UNSUPPORTED_MAPPING_KEY),
        )
    }

    @Test
    fun `scanner accepts a block mapping header followed by an inline comment`() {
        val document = scanned("section: # explanation\n  child: true\n")

        assertEquals("true", document.entry(YamlPath.parse("section.child")).valueText)
    }

    @Test
    fun `scanner records sequence and block scalar ranges without treating contents as mappings`() {
        val document =
            scanned(
                "items:\n" +
                    "  values:\n" +
                    "    - one\n" +
                    "    - two\n" +
                    "  description: |\n" +
                    "    key-looking: text\n",
            )

        assertTrue(document.entry(YamlPath.parse("items.values")).constructFlags.contains(YamlConstructFlag.SEQUENCE))
        assertTrue(document.entry(YamlPath.parse("items.description")).constructFlags.contains(YamlConstructFlag.BLOCK_SCALAR))
        assertTrue(document.lines.any { it.constructFlags.contains(YamlConstructFlag.BLOCK_SCALAR_CONTENT) })
        assertTrue(document.entry(YamlPath.parse("items.description")).subtreeSpan.endExclusive > 0)
        assertEquals(null, document.entryOrNull(YamlPath.parse("items.description.key-looking")))
    }

    @Test
    fun `scanner marks trailing blank block scalar lines as scalar content`() {
        val document = scanned("existing: |+\n  keep this text\n\n# footer\n")

        assertTrue(document.lines[2].constructFlags.contains(YamlConstructFlag.BLOCK_SCALAR_CONTENT))
    }

    @Test
    fun `scanner assigns sequence flags only to containing mappings`() {
        val document = scanned("first: scalar\nitems:\n  - one\n")

        assertFalse(document.entry(YamlPath.parse("first")).constructFlags.contains(YamlConstructFlag.SEQUENCE))
        assertTrue(document.entry(YamlPath.parse("items")).constructFlags.contains(YamlConstructFlag.SEQUENCE))
    }

    @Test
    fun `scanner assigns nested sequence flags only to the nearest containing mapping`() {
        val document = scanned("feature:\n  legacy:\n    - one\n  target: keep\n")

        assertFalse(document.entry(YamlPath.parse("feature")).constructFlags.contains(YamlConstructFlag.SEQUENCE))
        assertTrue(document.entry(YamlPath.parse("feature.legacy")).constructFlags.contains(YamlConstructFlag.SEQUENCE))
        assertFalse(document.entry(YamlPath.parse("feature.target")).constructFlags.contains(YamlConstructFlag.SEQUENCE))
    }

    @Test
    fun `scanner keeps mapping shaped sequence elements out of the editable path index`() {
        val document = scanned("items:\n  - name: apple\n    amount: 2\n")

        assertEquals(listOf("items"), document.entries.map { it.path.dotted })
        assertEquals(null, document.entryOrNull(YamlPath.parse("items.name")))
        assertEquals(null, document.entryOrNull(YamlPath.parse("items.amount")))
        assertTrue(document.entry(YamlPath.parse("items")).constructFlags.contains(YamlConstructFlag.SEQUENCE))
        assertTrue(document.lines[1].constructFlags.contains(YamlConstructFlag.SEQUENCE))
        assertTrue(document.lines[2].constructFlags.contains(YamlConstructFlag.SEQUENCE))
    }

    @Test
    fun `scanner assigns indentless scalar sequences to the immediately preceding mapping`() {
        val document = scanned("general:\n  blocked-worlds:\n  - first\n  - second\n  enabled: true\n")

        assertEquals(
            listOf("general", "general.blocked-worlds", "general.enabled"),
            document.entries.map { it.path.dotted },
        )
        assertTrue(document.entry(YamlPath.parse("general.blocked-worlds")).constructFlags.contains(YamlConstructFlag.SEQUENCE))
        assertTrue(document.lines[2].constructFlags.contains(YamlConstructFlag.SEQUENCE))
        assertTrue(document.lines[3].constructFlags.contains(YamlConstructFlag.SEQUENCE))
    }

    @Test
    fun `scanner contains indentless mapping sequence elements and nested content outside the path index`() {
        val document = scanned("items:\n- name: apple\n  metadata:\n    quality: 2\n- name: pear\nnext: keep\n")

        assertEquals(listOf("items", "next"), document.entries.map { it.path.dotted })
        assertEquals(null, document.entryOrNull(YamlPath.parse("items.name")))
        assertEquals(null, document.entryOrNull(YamlPath.parse("items.metadata.quality")))
        assertTrue(document.lines.slice(1..4).all { it.constructFlags.contains(YamlConstructFlag.SEQUENCE) })
    }

    @Test
    fun `scanner rejects an indentless sequence without an immediately preceding mapping owner`() {
        assertRejected("- orphan\n".toByteArray(StandardCharsets.UTF_8), YamlDocumentRejectionCategory.STRUCTURE)
    }

    @Test
    fun `scanner marks merge keys as unsupported instead of indexing them`() {
        val document = scanned("defaults: &base\nfeature:\n  <<: *base\n")

        assertEquals(null, document.entryOrNull(YamlPath.parse("feature.<<")))
        assertTrue(
            document.lines
                .last()
                .constructFlags
                .contains(YamlConstructFlag.MERGE_KEY),
        )
    }

    @Test
    fun `scanner rejects invalid bytes and unsafe document structures without echoing content`() {
        assertRejected(byteArrayOf(0xC3.toByte()), YamlDocumentRejectionCategory.ENCODING)
        assertRejected(
            "one: 1\ntwo: 2\r\n".toByteArray(StandardCharsets.UTF_8),
            YamlDocumentRejectionCategory.NEWLINE,
        )
        assertRejected(
            "one:\n\tchild: secret-content\n".toByteArray(StandardCharsets.UTF_8),
            YamlDocumentRejectionCategory.STRUCTURE,
        )
        assertRejected(
            "one: 1\nother:\n  one: 2\none: 3\n".toByteArray(StandardCharsets.UTF_8),
            YamlDocumentRejectionCategory.DUPLICATE_PATH,
        )
        assertRejected(
            "section:\n  value: one\n   invalid: two\n".toByteArray(StandardCharsets.UTF_8),
            YamlDocumentRejectionCategory.STRUCTURE,
        )
    }

    private fun scanned(source: String): ScannedYamlDocument {
        val result = scanner.scan(source.toByteArray(StandardCharsets.UTF_8))
        return assertInstanceOf<YamlDocumentScanResult.Scanned>(result).document
    }

    private fun assertRejected(
        bytes: ByteArray,
        category: YamlDocumentRejectionCategory,
    ) {
        val result = scanner.scan(bytes)
        val rejection = assertInstanceOf<YamlDocumentScanResult.Rejected>(result).rejection

        assertEquals(category, rejection.category)
        assertTrue(rejection.detail.length <= 160)
        assertFalse(rejection.detail.contains("secret-content"))
    }
}
