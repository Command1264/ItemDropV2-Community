package com.github.command1264.itemdropv2.platform.bukkit.yaml

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.nio.charset.StandardCharsets

class YamlMappingTreeSpacingPolicyTest {
    private val policy = MappingTreeSpacingPolicy()

    @Test
    fun `separates sibling mapping subtrees without separating parent from first child`() {
        val original =
            (
                "\n\n" +
                    """
                    key1:
                        # key1_1
                        key1_1: true


                        # key1_2
                        key1_2: true
                    key2:
                        key2_1: true
                        key2_2: true
                    """.trimIndent()
            ).utf8()

        val result = assertInstanceOf<YamlDocumentEditResult.Candidate>(policy.apply(original))

        assertArrayEquals(
            (
                """
                key1:
                  # key1_1
                  key1_1: true

                  # key1_2
                  key1_2: true

                key2:
                  key2_1: true

                  key2_2: true
                """.trimIndent() + "\n"
            ).utf8(),
            result.bytes,
        )
        assertArrayEquals(
            result.bytes,
            assertInstanceOf<YamlDocumentEditResult.Candidate>(policy.apply(result.bytes)).bytes,
        )
    }

    @Test
    fun `removes blank lines between nested parents comments and their first children`() {
        val original =
            "items:\n\n" +
                "  # processing comment\n" +
                "  processing:\n\n" +
                "    # maximum comment\n" +
                "    maximum-items-per-tick: 256\n\n" +
                "  next: true\n"

        val result = assertInstanceOf<YamlDocumentEditResult.Candidate>(policy.apply(original.utf8()))

        assertArrayEquals(
            (
                "items:\n" +
                    "  # processing comment\n" +
                    "  processing:\n" +
                    "    # maximum comment\n" +
                    "    maximum-items-per-tick: 256\n\n" +
                    "  next: true\n"
            ).utf8(),
            result.bytes,
        )
        assertArrayEquals(
            result.bytes,
            assertInstanceOf<YamlDocumentEditResult.Candidate>(policy.apply(result.bytes)).bytes,
        )
    }

    @Test
    fun `removes blank lines between nested parents and first children without comments`() {
        val original = "items:\n\n  processing:\n\n    maximum-items-per-tick: 256\n"

        val result = assertInstanceOf<YamlDocumentEditResult.Candidate>(policy.apply(original.utf8()))

        assertArrayEquals(
            "items:\n  processing:\n    maximum-items-per-tick: 256\n".utf8(),
            result.bytes,
        )
    }

    @Test
    fun `preserves the blank that keeps a first-child section comment standalone`() {
        val original = "items:\n  # standalone section note\n\n  processing:\n    maximum-items-per-tick: 256\n"

        val result = assertInstanceOf<YamlDocumentEditResult.Candidate>(policy.apply(original.utf8()))

        assertArrayEquals(original.utf8(), result.bytes)
    }

    @Test
    fun `adds exactly one terminal newline to a single line document`() {
        val result = assertInstanceOf<YamlDocumentEditResult.Candidate>(policy.apply("enabled: true".utf8()))

        assertArrayEquals("enabled: true\n".utf8(), result.bytes)
    }

    @Test
    fun `preserves trailing blank block scalar content`() {
        val original = "text: |\n  value\n\n".utf8()

        val result = assertInstanceOf<YamlDocumentEditResult.Candidate>(policy.apply(original))

        assertArrayEquals(original, result.bytes)
    }

    @Test
    fun `preserves crlf bom sequences and block scalar content and is idempotent`() {
        val original =
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "root:\r\n  values:\r\n    - one\r\n    - two\r\n  text: |\r\n    first\r\n\r\n    third\r\nother: true\r\n".utf8()

        val first = assertInstanceOf<YamlDocumentEditResult.Candidate>(policy.apply(original)).bytes
        val second = assertInstanceOf<YamlDocumentEditResult.Candidate>(policy.apply(first)).bytes

        val expected =
            byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
                "root:\r\n  values:\r\n    - one\r\n    - two\r\n\r\n  text: |\r\n    first\r\n\r\n    third\r\nother: true\r\n".utf8()
        assertArrayEquals(expected, first, first.toString(StandardCharsets.UTF_8).replace("\r", "<CR>").replace("\n", "<LF>\n"))
        assertArrayEquals(first, second)
    }

    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
}
