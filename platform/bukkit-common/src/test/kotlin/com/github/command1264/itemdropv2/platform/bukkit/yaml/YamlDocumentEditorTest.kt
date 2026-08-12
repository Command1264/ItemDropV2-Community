package com.github.command1264.itemdropv2.platform.bukkit.yaml

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import java.math.BigDecimal
import java.nio.charset.StandardCharsets

@Suppress("LargeClass")
class YamlDocumentEditorTest {
    private val editor: YamlDocumentEditor = StrictYamlDocumentEditor()

    @Test
    fun `ensure path copies a complete missing root subtree before the next template sibling`() {
        val original = "logging: verbose\n".utf8()
        val template =
            """
            # feature comment
            feature: # section inline
              flags:
                # enabled comment
                enabled: true # leaf inline
              mode: safe
            logging: quiet
            """.trimIndent().utf8()

        val result = editor.edit(request(original, template, ensure("feature.flags.enabled")))

        assertArrayEquals(
            (
                """
                # feature comment
                feature: # section inline
                  flags:
                    # enabled comment
                    enabled: true # leaf inline
                  mode: safe
                logging: verbose
                """.trimIndent() + "\n"
            ).utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path inserts a nested leaf after the nearest previous template sibling`() {
        val original =
            """
            materials:
              DIAMOND:
                duration: 45 # user value
                fallback: 12
            """.trimIndent().utf8()
        val template =
            """
            materials:
              DIAMOND:
                duration: 30
                # lifetime comment
                lifetime: 60 # ticks
                fallback: 10
            """.trimIndent().utf8()

        val result = editor.edit(request(original, template, ensure("materials.DIAMOND.lifetime")))

        assertArrayEquals(
            """
            materials:
              DIAMOND:
                duration: 45 # user value
                # lifetime comment
                lifetime: 60 # ticks
                fallback: 12
            """.trimIndent().utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path prefers a previous template sibling even when the original order differs`() {
        val original = "feature:\n  omega: user-next\n  alpha: user-previous\n".utf8()
        val template = "feature:\n  alpha: first\n  target: inserted\n  omega: last\n".utf8()

        val result = editor.edit(request(original, template, ensure("feature.target")))

        assertArrayEquals(
            "feature:\n  omega: user-next\n  alpha: user-previous\n  target: inserted\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path preserves an unsafe untouched sibling used for placement`() {
        val original = "legacy: { keep: true }\nnext: custom\n".utf8()
        val template = "legacy: default\ntarget: inserted\nnext: default\n".utf8()

        val result = editor.edit(request(original, template, ensure("target")))

        assertArrayEquals(
            "legacy: { keep: true }\ntarget: inserted\nnext: custom\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path inserts after a complete untouched block scalar sibling`() {
        val original = "legacy: |\n  keep this text\nnext: custom\n".utf8()
        val template = "legacy: default\ntarget: inserted\nnext: default\n".utf8()

        val result = editor.edit(request(original, template, ensure("target")))

        assertArrayEquals(
            "legacy: |\n  keep this text\ntarget: inserted\nnext: custom\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path inserts after a complete untouched sequence sibling`() {
        val original = "legacy:\n  - keep\n  - both\nnext: custom\n".utf8()
        val template = "legacy: default\ntarget: inserted\nnext: default\n".utf8()

        val result = editor.edit(request(original, template, ensure("target")))

        assertArrayEquals(
            "legacy:\n  - keep\n  - both\ntarget: inserted\nnext: custom\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path preserves an unrelated nested sequence in the original`() {
        val original = "feature:\n  legacy:\n    - keep\n    - both\n  next: custom\n".utf8()
        val template = "feature:\n  legacy: default\n  target: inserted\n  next: default\n".utf8()

        val result = editor.edit(request(original, template, ensure("feature.target")))

        assertArrayEquals(
            "feature:\n  legacy:\n    - keep\n    - both\n  target: inserted\n  next: custom\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path ignores an unrelated nested sequence in the template`() {
        val original = "feature:\n  legacy: custom\n  next: custom\n".utf8()
        val template = "feature:\n  legacy:\n    - default\n  target: inserted\n  next: default\n".utf8()

        val result = editor.edit(request(original, template, ensure("feature.target")))

        assertArrayEquals(
            "feature:\n  legacy: custom\n  target: inserted\n  next: custom\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path still rejects a target that overlaps a sequence`() {
        val original = "feature:\n  legacy:\n    - keep\n".utf8()
        val template = "feature:\n  legacy: default\n".utf8()

        assertRejected(
            editor.edit(request(original, template, ensure("feature.legacy"))),
            YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
        )
    }

    @Test
    fun `ensure path rejects an existing ancestor target that contains a sequence`() {
        val original = "feature:\n  legacy:\n    - keep\n".utf8()
        val template = "feature:\n  legacy: default\n".utf8()

        assertRejected(
            editor.edit(request(original, template, ensure("feature"))),
            YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
        )
    }

    @Test
    fun `ensure path rejects a template ancestor target that contains a sequence`() {
        val original = "feature:\n  legacy: custom\n".utf8()
        val template = "feature:\n  legacy:\n    - default\n".utf8()

        assertRejected(
            editor.edit(request(original, template, ensure("feature"))),
            YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
        )
    }

    @Test
    fun `ensure path inserts before an unrelated EOF footer`() {
        val original = "existing: custom\n\n# user footer\n".utf8()
        val template = "existing: default\ntarget: inserted\n".utf8()

        val result = editor.edit(request(original, template, ensure("target")))

        assertArrayEquals(
            "existing: custom\ntarget: inserted\n\n# user footer\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path preserves trailing blank block scalar content and EOF footer separation`() {
        val original = "existing: |+\n  keep this text\n\n# user footer\n".utf8()
        val template = "existing: default\ntarget: inserted\n".utf8()

        val result = editor.edit(request(original, template, ensure("target")))

        assertArrayEquals(
            "existing: |+\n  keep this text\n\ntarget: inserted\n\n# user footer\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path does not copy a following standalone comment`() {
        val original = "feature:\n  before: custom-before\n  after: custom-after\n".utf8()
        val template =
            "feature:\n  before: default-before\n  target: inserted\n\n  # standalone for following section\n  after: default-after\n"
                .utf8()

        val result = editor.edit(request(original, template, ensure("feature.target")))

        assertArrayEquals(
            "feature:\n  before: custom-before\n  target: inserted\n  after: custom-after\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path does not copy a trailing template footer`() {
        val original = "feature:\n  existing: custom\n".utf8()
        val template = "feature:\n  existing: default\n  target: inserted\n\n# template footer\n".utf8()

        val result = editor.edit(request(original, template, ensure("feature.target")))

        assertArrayEquals(
            "feature:\n  existing: custom\n  target: inserted\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path copies the smallest missing parent subtree`() {
        val original =
            """
            messages:
              greeting: custom
              farewell: later
            """.trimIndent().utf8()
        val template =
            """
            messages:
              greeting: hello
              pickup-denied:
                # title comment
                title: blocked # inline
                detail: wait
              farewell: bye
            """.trimIndent().utf8()

        val result = editor.edit(request(original, template, ensure("messages.pickup-denied.title")))

        assertArrayEquals(
            """
            messages:
              greeting: custom
              pickup-denied:
                # title comment
                title: blocked # inline
                detail: wait
              farewell: later
            """.trimIndent().utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path appends at the parent subtree end when no template sibling exists`() {
        val original =
            """
            feature:
              custom: keep
            logging: same
            """.trimIndent().utf8()
        val template =
            """
            feature:
              flags:
                enabled: true
            logging: quiet
            """.trimIndent().utf8()

        val result = editor.edit(request(original, template, ensure("feature.flags")))

        assertArrayEquals(
            """
            feature:
              custom: keep
              flags:
                enabled: true
            logging: same
            """.trimIndent().utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path preserves an existing path byte for byte`() {
        val original = "feature:\n  # user comment\n  flags: custom # retained\n".utf8()
        val template = "feature:\n  # template comment\n  flags: default # replaced\n".utf8()

        val result = editor.edit(request(original, template, ensure("feature.flags")))

        assertArrayEquals(original, assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes)
    }

    @Test
    fun `ensure path still requires an existing path to be present in the template`() {
        val result =
            editor.edit(
                request(
                    "feature:\n  flags: custom\n".utf8(),
                    "feature:\n  mode: default\n".utf8(),
                    ensure("feature.flags"),
                ),
            )

        assertRejected(result, YamlDocumentRejectionCategory.TEMPLATE_MISMATCH)
    }

    @Test
    fun `ensure path preserves BOM and converts only inserted template newlines`() {
        val original = "\uFEFFfeature:\r\n  existing: keep\r\n".utf8()
        val template = "feature:\n  # inserted\n  flags:\n    enabled: true\n".utf8()

        val result = editor.edit(request(original, template, ensure("feature.flags.enabled")))

        assertArrayEquals(
            "\uFEFFfeature:\r\n  existing: keep\r\n  # inserted\r\n  flags:\r\n    enabled: true\r\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path rescans after each insertion`() {
        val original = "feature:\n  first: custom\n".utf8()
        val template = "feature:\n  first: one\n  second: two\n  third: three\n".utf8()

        val result =
            editor.edit(
                request(
                    original,
                    template,
                    ensure("feature.second"),
                    ensure("feature.third"),
                ),
            )

        assertArrayEquals(
            "feature:\n  first: custom\n  second: two\n  third: three\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `ensure path rejects scalar parents and unsafe touched constructs`() {
        val template = "feature:\n  flags:\n    enabled: true\n".utf8()
        assertRejected(
            editor.edit(request("feature: disabled\n".utf8(), template, ensure("feature.flags.enabled"))),
            YamlDocumentRejectionCategory.VALIDATION,
        )
        assertRejected(
            editor.edit(request("feature:\n  flags: { enabled: false }\n".utf8(), template, ensure("feature.flags"))),
            YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
        )
    }

    @Test
    fun `ensure path rejects missing duplicate or unsafe template paths`() {
        assertRejected(
            editor.edit(request("feature:\n  existing: keep\n".utf8(), "feature:\n  other: value\n".utf8(), ensure("feature.missing"))),
            YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
        )
        assertRejected(
            editor.edit(
                request("feature:\n  existing: keep\n".utf8(), "feature:\n  flags: one\n  flags: two\n".utf8(), ensure("feature.flags")),
            ),
            YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
        )
        assertRejected(
            editor.edit(
                request(
                    "feature:\n  existing: keep\n".utf8(),
                    "feature:\n  flags: { enabled: true }\n".utf8(),
                    ensure("feature.flags"),
                ),
            ),
            YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
        )
    }

    @Test
    fun `ensure path rejects insertion when the original newline style is indeterminate`() {
        assertRejected(
            editor.edit(request("feature:".utf8(), "feature:\n  enabled: true\n".utf8(), ensure("feature.enabled"))),
            YamlDocumentRejectionCategory.NEWLINE,
        )
    }

    @Test
    fun `ensure path uses trusted template indentation when the parent has no children`() {
        val result =
            editor.edit(
                request(
                    "feature:\nlogging: keep\n".utf8(),
                    "feature:\n  # enabled comment\n  enabled: true\nlogging: default\n".utf8(),
                    ensure("feature.enabled"),
                ),
            )

        assertArrayEquals(
            "feature:\n  # enabled comment\n  enabled: true\nlogging: keep\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `move path relocates scalar value and owned comments to the template position`() {
        val original =
            "feature:\n" +
                "  keep: before\n" +
                "legacy:\n" +
                "  nested:\n" +
                "    # user explanation\n" +
                "    old: custom # old inline\n" +
                "tail: same\n"
        val template = "feature:\n  keep: default\n  canonical: fallback\ntail: default\n"

        val result = editor.edit(request(original.utf8(), template.utf8(), move("legacy.nested.old", "feature.canonical")))

        assertArrayEquals(
            "feature:\n  keep: before\n  # user explanation\n  canonical: custom # old inline\ntail: same\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `move path leaves one separator when blank lines surround the removed source`() {
        val original =
            "feature:\n" +
                "  before: keep\n" +
                "\n" +
                "  # legacy explanation\n" +
                "  old: custom # legacy inline\n" +
                "\n" +
                "  after: keep\n"
        val template =
            "feature:\n" +
                "  before: default\n" +
                "\n" +
                "  # template explanation\n" +
                "  target: fallback\n" +
                "\n" +
                "  after: default\n"

        val result = editor.edit(request(original.utf8(), template.utf8(), move("feature.old", "feature.target")))

        assertEquals(
            (
                "feature:\n" +
                    "  before: keep\n" +
                    "\n" +
                    "  # legacy explanation\n" +
                    "  target: custom # legacy inline\n" +
                    "\n" +
                    "  after: keep\n"
            ),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes.toString(StandardCharsets.UTF_8),
        )
    }

    @Test
    fun `move path relocates a subtree without deleting unknown siblings or standalone comments`() {
        val original =
            "legacy:\n" +
                "  # source comment\n" +
                "  old:\n" +
                "    child: custom # child inline\n" +
                "  unknown: keep\n" +
                "\n" +
                "  # standalone remains\n" +
                "tail: same\n"
        val template = "canonical:\n  child: default\ntail: default\n"

        val result = editor.edit(request(original.utf8(), template.utf8(), move("legacy.old", "canonical")))

        assertArrayEquals(
            (
                "legacy:\n" +
                    "  unknown: keep\n" +
                    "\n" +
                    "  # standalone remains\n" +
                    "# source comment\n" +
                    "canonical:\n" +
                    "  child: custom # child inline\n" +
                    "tail: same\n"
            ).utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `move path keeps canonical value and comments while preserving obsolete comments in one footer`() {
        val original =
            "# obsolete explanation\n" +
                "old: legacy # obsolete inline\n" +
                "# canonical explanation\n" +
                "new: canonical # canonical inline\n"
        val template = "new: default\n"

        val result = editor.edit(request(original.utf8(), template.utf8(), move("old", "new")))

        assertArrayEquals(
            (
                "# canonical explanation\n" +
                    "new: canonical # canonical inline\n" +
                    "\n" +
                    "# Legacy comments preserved during migration\n" +
                    "# obsolete explanation\n" +
                    "# obsolete inline\n"
            ).utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `move path retains an empty source parent when a standalone comment remains`() {
        val original = "legacy:\n  old: custom\n\n  # standalone stays\ntail: same\n"
        val template = "canonical: default\ntail: default\n"

        val result = editor.edit(request(original.utf8(), template.utf8(), move("legacy.old", "canonical")))

        assertArrayEquals(
            "legacy:\n\n  # standalone stays\ncanonical: custom\ntail: same\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `move path relocates only owned subtree comments and retains an interleaved standalone comment`() {
        val original =
            "legacy:\n" +
                "  old:\n" +
                "    first: one\n" +
                "\n" +
                "    # standalone stays at source\n" +
                "\n" +
                "    second: two\n" +
                "tail: same\n"
        val template = "canonical:\n  first: default\n  second: default\ntail: default\n"

        val result = editor.edit(request(original.utf8(), template.utf8(), move("legacy.old", "canonical")))

        assertArrayEquals(
            (
                "legacy:\n" +
                    "\n" +
                    "    # standalone stays at source\n" +
                    "\n" +
                    "canonical:\n" +
                    "  first: one\n" +
                    "  second: two\n" +
                    "tail: same\n"
            ).utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `move path rejects missing incompatible overlapping and unsafe inputs`() {
        assertRejected(
            editor.edit(request("old: value\n".utf8(), "new: default\n".utf8(), move("missing", "new"))),
            YamlDocumentRejectionCategory.VALIDATION,
        )
        assertRejected(
            editor.edit(request("old:\n  child: value\n".utf8(), "new: default\n".utf8(), move("old", "new"))),
            YamlDocumentRejectionCategory.VALIDATION,
        )
        assertRejected(
            editor.edit(
                request(
                    "old: value\nnew: scalar\n".utf8(),
                    "old: default\nnew:\n  child: default\n".utf8(),
                    move("old", "new.child"),
                ),
            ),
            YamlDocumentRejectionCategory.VALIDATION,
        )
        assertRejected(
            editor.edit(request("old:\n  child: value\n".utf8(), "old:\n  child: value\n".utf8(), move("old", "old.child"))),
            YamlDocumentRejectionCategory.OPERATION_CONFLICT,
        )
        assertRejected(
            editor.edit(
                request(
                    "old:\n  first: one\n  second: two\n".utf8(),
                    "new:\n  first: one\n  second: two\n".utf8(),
                    move("old.first", "new.first"),
                    move("old", "new"),
                ),
            ),
            YamlDocumentRejectionCategory.OPERATION_CONFLICT,
        )
        assertRejected(
            editor.edit(request("old: { child: value }\n".utf8(), "new: default\n".utf8(), move("old", "new"))),
            YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
        )
        assertRejected(
            editor.edit(request("old: value\n".utf8(), "new: { child: default }\n".utf8(), move("old", "new"))),
            YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
        )
    }

    @Test
    @Suppress("LongMethod")
    fun `migration renders typed values and routes comments without leaking legacy values`() {
        val original =
            "# mapped leading\n" +
                "old-enabled: no # mapped inline\n" +
                "# ignored comment\n" +
                "ignored: secret-value # ignored inline\n" +
                "\n" +
                "# standalone note\n" +
                "ambiguous:\n" +
                "  # ambiguous owned\n" +
                "  value: legacy-value\n"
        val template =
            "# template heading\n" +
                "feature:\n" +
                "  # enabled default\n" +
                "  enabled: false\n" +
                "  limit: 1\n" +
                "  ratio: 1.0\n" +
                "  label: default\n" +
                "  names:\n" +
                "    # sequence template comment\n" +
                "    - default\n"
        val migration =
            migration(
                original = original.utf8(),
                template = template.utf8(),
                canonicalValues =
                    linkedMapOf(
                        "feature.names" to
                            YamlNodeValue.ScalarSequence(
                                listOf(
                                    YamlScalar.StringValue("alpha"),
                                    YamlScalar.IntegerValue(2),
                                    YamlScalar.BooleanValue(false),
                                ),
                            ),
                        "feature.label" to YamlNodeValue.Scalar(YamlScalar.StringValue("label # text")),
                        "feature.enabled" to YamlNodeValue.Scalar(YamlScalar.BooleanValue(true)),
                        "feature.ratio" to YamlNodeValue.Scalar(YamlScalar.DecimalValue(BigDecimal("3.250"))),
                        "feature.limit" to YamlNodeValue.Scalar(YamlScalar.IntegerValue(42)),
                    ),
                commentMappings =
                    listOf(
                        mapping("old-enabled", "feature.enabled"),
                        mapping("ignored", null),
                        mapping("ambiguous.value", "feature.label"),
                        mapping("ambiguous.value", "feature.ratio"),
                    ),
            )

        val first = editor.migrate(migration)
        val firstBytes = assertInstanceOf<YamlDocumentEditResult.Candidate>(first).bytes

        assertArrayEquals(
            (
                "# template heading\n" +
                    "feature:\n" +
                    "  # mapped leading\n" +
                    "  # mapped inline\n" +
                    "  # enabled default\n" +
                    "  enabled: true\n" +
                    "  limit: 42\n" +
                    "  ratio: 3.250\n" +
                    "  label: 'label # text'\n" +
                    "  names:\n" +
                    "    # sequence template comment\n" +
                    "    - alpha\n" +
                    "    - 2\n" +
                    "    - false\n" +
                    "\n" +
                    "# Legacy comments preserved during migration\n" +
                    "# ignored comment\n" +
                    "# ignored inline\n" +
                    "# standalone note\n" +
                    "# ambiguous owned\n"
            ).utf8(),
            firstBytes,
        )

        val second = editor.migrate(migration.copy(original = firstBytes))
        assertArrayEquals(firstBytes, assertInstanceOf<YamlDocumentEditResult.Candidate>(second).bytes)
    }

    @Test
    fun `migration maps comments from a legacy scalar sequence without carrying its values`() {
        val request =
            migration(
                original = "# legacy list comment\nold-list:\n  - secret-old-value # legacy item comment\n".utf8(),
                template = "settings:\n  values:\n    - default # template item comment\n".utf8(),
                canonicalValues =
                    mapOf(
                        "settings.values" to
                            YamlNodeValue.ScalarSequence(listOf(YamlScalar.StringValue("canonical"))),
                    ),
                commentMappings = listOf(mapping("old-list", "settings.values")),
            )

        val result = editor.migrate(request)

        assertArrayEquals(
            (
                "settings:\n" +
                    "  # legacy list comment\n" +
                    "  # legacy item comment\n" +
                    "  values:\n" +
                    "    # template item comment\n" +
                    "    - canonical\n"
            ).utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
        val second = editor.migrate(request.copy(original = assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes))
        assertArrayEquals(
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
            assertInstanceOf<YamlDocumentEditResult.Candidate>(second).bytes,
        )
    }

    @Test
    fun `migration preserves extra canonical comments in the footer while a legacy source exists`() {
        val request =
            migration(
                original =
                    (
                        "# legacy mapped\n" +
                            "old: legacy\n" +
                            "# canonical extra\n" +
                            "canonical: previous\n"
                    ).utf8(),
                template = "canonical: default\n".utf8(),
                canonicalValues = mapOf("canonical" to YamlNodeValue.Scalar(YamlScalar.StringValue("migrated"))),
                commentMappings = listOf(mapping("old", "canonical")),
            )

        val first = editor.migrate(request)
        val firstBytes = assertInstanceOf<YamlDocumentEditResult.Candidate>(first).bytes

        assertArrayEquals(
            (
                "# legacy mapped\n" +
                    "canonical: migrated\n" +
                    "\n" +
                    "# Legacy comments preserved during migration\n" +
                    "# canonical extra\n"
            ).utf8(),
            firstBytes,
        )
        val second = editor.migrate(request.copy(original = firstBytes))
        assertArrayEquals(firstBytes, assertInstanceOf<YamlDocumentEditResult.Candidate>(second).bytes)
    }

    @Test
    fun `migration rejects a reserved legacy footer marker in the embedded template`() {
        val result =
            editor.migrate(
                migration(
                    original = "old: value\n".utf8(),
                    template = "canonical: default\n\n# Legacy comments preserved during migration\n".utf8(),
                    canonicalValues = mapOf("canonical" to YamlNodeValue.Scalar(YamlScalar.StringValue("value"))),
                ),
            )

        assertRejected(result, YamlDocumentRejectionCategory.TEMPLATE_MISMATCH)
    }

    @Test
    fun `migration rejects duplicate or nonterminal reserved legacy footer markers`() {
        val invalidOriginals =
            listOf(
                "old: value\n" +
                    "# Legacy comments preserved during migration\n" +
                    "# first\n" +
                    "# Legacy comments preserved during migration\n" +
                    "# second\n",
                "old: value\n# Legacy comments preserved during migration\n# preserved\ntrailing: value\n",
            )

        invalidOriginals.forEach { original ->
            assertRejected(
                editor.migrate(
                    migration(
                        original = original.utf8(),
                        template = "canonical: default\n".utf8(),
                        canonicalValues = mapOf("canonical" to YamlNodeValue.Scalar(YamlScalar.StringValue("value"))),
                    ),
                ),
                YamlDocumentRejectionCategory.STRUCTURE,
            )
        }
    }

    @Test
    fun `migration renders an empty scalar sequence without changing its second candidate`() {
        val request =
            migration(
                original = "old-list:\n  - secret-old-value\n".utf8(),
                template = "settings:\n  values:\n    # template list comment\n    - default\n".utf8(),
                canonicalValues = mapOf("settings.values" to YamlNodeValue.ScalarSequence(emptyList())),
            )

        val first = editor.migrate(request)
        val firstBytes = assertInstanceOf<YamlDocumentEditResult.Candidate>(first).bytes

        assertArrayEquals("settings:\n  # template list comment\n  values: []\n".utf8(), firstBytes)
        val second = editor.migrate(request.copy(original = firstBytes))
        assertArrayEquals(firstBytes, assertInstanceOf<YamlDocumentEditResult.Candidate>(second).bytes)
    }

    @Test
    fun `migration rejects unknown canonical and comment target paths`() {
        assertRejected(
            editor.migrate(
                migration(
                    original = "old: value\n".utf8(),
                    template = "known: default\n".utf8(),
                    canonicalValues = mapOf("unknown" to YamlNodeValue.Scalar(YamlScalar.StringValue("value"))),
                ),
            ),
            YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
        )
        assertRejected(
            editor.migrate(
                migration(
                    original = "# explanation\nold: value\n".utf8(),
                    template = "known: default\n".utf8(),
                    canonicalValues = emptyMap(),
                    commentMappings = listOf(mapping("old", "unknown")),
                ),
            ),
            YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
        )
        assertRejected(
            editor.migrate(
                migration(
                    original = "old: value\n".utf8(),
                    template = "known: default\n".utf8(),
                    canonicalValues = mapOf("known" to YamlNodeValue.ScalarSequence(listOf(YamlScalar.StringValue("value")))),
                ),
            ),
            YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
        )
        assertRejected(
            editor.migrate(
                migration(
                    original = "old: value\n".utf8(),
                    template = "known:\n  - name: default\n".utf8(),
                    canonicalValues = mapOf("known" to YamlNodeValue.ScalarSequence(listOf(YamlScalar.StringValue("value")))),
                ),
            ),
            YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
        )
    }

    @Test
    fun `migration rejects quoted sequence mappings but accepts quoted scalars containing colons`() {
        listOf(
            "known:\n  - \"name\": default\n",
            "known:\n  - 'name': default\n",
            "known:\n  - \"name: default\"#not-a-comment\n",
        ).forEach { template ->
            assertRejected(
                editor.migrate(
                    migration(
                        original = "old: value\n".utf8(),
                        template = template.utf8(),
                        canonicalValues =
                            mapOf("known" to YamlNodeValue.ScalarSequence(listOf(YamlScalar.StringValue("value")))),
                    ),
                ),
                YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
            )
        }

        val safe =
            editor.migrate(
                migration(
                    original = "old: value\n".utf8(),
                    template = "known:\n  - \"name: default\" # double\n  - 'other: value' # single\n".utf8(),
                    canonicalValues =
                        mapOf("known" to YamlNodeValue.ScalarSequence(listOf(YamlScalar.StringValue("canonical")))),
                ),
            )

        assertArrayEquals(
            "known:\n  # double\n  # single\n  - canonical\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(safe).bytes,
        )
    }

    @Test
    fun `set scalar changes only value span`() {
        val original = "general:\r\n  enabled :  true   # keep\r\nunknown: 'same'  \r\n".utf8()

        val result =
            editor.edit(
                request(
                    original,
                    YamlDocumentOperation.SetScalar(
                        YamlPath.parse("general.enabled"),
                        YamlScalar.BooleanValue(false),
                    ),
                ),
            )

        assertArrayEquals(
            "general:\r\n  enabled :  false   # keep\r\nunknown: 'same'  \r\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `set scalar preserves UTF-8 BOM and renders typed scalars`() {
        val original = "\uFEFFboolean: old\nminimum: old\nmaximum: old\ndecimal: old\nempty: old\nplain: old\n".utf8()

        val result =
            editor.edit(
                request(
                    original,
                    YamlDocumentOperation.SetScalar(YamlPath.parse("boolean"), YamlScalar.BooleanValue(true)),
                    YamlDocumentOperation.SetScalar(YamlPath.parse("minimum"), YamlScalar.IntegerValue(Long.MIN_VALUE)),
                    YamlDocumentOperation.SetScalar(YamlPath.parse("maximum"), YamlScalar.IntegerValue(Long.MAX_VALUE)),
                    YamlDocumentOperation.SetScalar(YamlPath.parse("decimal"), YamlScalar.DecimalValue(BigDecimal("42.500"))),
                    YamlDocumentOperation.SetScalar(YamlPath.parse("empty"), YamlScalar.StringValue("")),
                    YamlDocumentOperation.SetScalar(YamlPath.parse("plain"), YamlScalar.StringValue("safe_value-42")),
                ),
            )

        assertArrayEquals(
            "\uFEFFboolean: true\nminimum: -9223372036854775808\nmaximum: 9223372036854775807\ndecimal: 42.500\nempty: ''\nplain: safe_value-42\n"
                .utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `set scalar quotes YAML-sensitive strings`() {
        val original = "hash: old\ncolon: old\nquote: old\n".utf8()

        val result =
            editor.edit(
                request(
                    original,
                    YamlDocumentOperation.SetScalar(YamlPath.parse("hash"), YamlScalar.StringValue("keep # text")),
                    YamlDocumentOperation.SetScalar(YamlPath.parse("colon"), YamlScalar.StringValue("key: value")),
                    YamlDocumentOperation.SetScalar(YamlPath.parse("quote"), YamlScalar.StringValue("it's literal")),
                ),
            )

        assertArrayEquals(
            "hash: 'keep # text'\ncolon: 'key: value'\nquote: 'it''s literal'\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `set scalar quotes YAML 1 1 y and n boolean-like strings`() {
        val original = "lower-y: old\nupper-y: old\nlower-n: old\nupper-n: old\n".utf8()

        val result =
            editor.edit(
                request(
                    original,
                    YamlDocumentOperation.SetScalar(YamlPath.parse("lower-y"), YamlScalar.StringValue("y")),
                    YamlDocumentOperation.SetScalar(YamlPath.parse("upper-y"), YamlScalar.StringValue("Y")),
                    YamlDocumentOperation.SetScalar(YamlPath.parse("lower-n"), YamlScalar.StringValue("n")),
                    YamlDocumentOperation.SetScalar(YamlPath.parse("upper-n"), YamlScalar.StringValue("N")),
                ),
            )

        assertArrayEquals(
            "lower-y: 'y'\nupper-y: 'Y'\nlower-n: 'n'\nupper-n: 'N'\n".utf8(),
            assertInstanceOf<YamlDocumentEditResult.Candidate>(result).bytes,
        )
    }

    @Test
    fun `set scalar rejects string control characters`() {
        val result =
            editor.edit(
                request(
                    "value: old\n".utf8(),
                    YamlDocumentOperation.SetScalar(YamlPath.parse("value"), YamlScalar.StringValue("unsafe\u0000value")),
                ),
            )

        assertRejected(result, YamlDocumentRejectionCategory.VALIDATION)
    }

    @Test
    fun `set scalar rejects missing section and unsupported targets`() {
        assertRejected(
            editor.edit(request("value: old\n".utf8(), set("missing"))),
            YamlDocumentRejectionCategory.VALIDATION,
        )
        assertRejected(
            editor.edit(request("section:\n  value: old\n".utf8(), set("section"))),
            YamlDocumentRejectionCategory.VALIDATION,
        )
        assertRejected(
            editor.edit(request("value: { nested: old }\n".utf8(), set("value"))),
            YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
        )
        assertRejected(
            editor.edit(request("value: &base old\n".utf8(), set("value"))),
            YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
        )
        assertRejected(
            editor.edit(request("base: &base old\nvalue: *base\n".utf8(), set("value"))),
            YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
        )
        assertRejected(
            editor.edit(request("base: &base old\nsection:\n  <<: *base\n  value: old\n".utf8(), set("section.value"))),
            YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
        )
        assertRejected(
            editor.edit(request("value: |\n  block scalar\n".utf8(), set("value"))),
            YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
        )
        assertRejected(
            editor.edit(request("root:\n  \"foreign\":\n    enabled: keep\n".utf8(), set("root.enabled"))),
            YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
        )
    }

    @Test
    fun `set scalar rejects duplicate operations before patching`() {
        assertRejected(
            editor.edit(request("value: old\n".utf8(), set("value"), set("value"))),
            YamlDocumentRejectionCategory.OPERATION_CONFLICT,
        )
    }

    private fun request(
        original: ByteArray,
        vararg operations: YamlDocumentOperation,
    ): YamlDocumentEditRequest =
        YamlDocumentEditRequest(
            original = original,
            template = original,
            operations = operations.toList(),
        )

    private fun request(
        original: ByteArray,
        template: ByteArray,
        vararg operations: YamlDocumentOperation,
    ): YamlDocumentEditRequest =
        YamlDocumentEditRequest(
            original = original,
            template = template,
            operations = operations.toList(),
        )

    private fun ensure(path: String): YamlDocumentOperation.EnsurePath = YamlDocumentOperation.EnsurePath(YamlPath.parse(path))

    private fun set(path: String): YamlDocumentOperation.SetScalar =
        YamlDocumentOperation.SetScalar(YamlPath.parse(path), YamlScalar.StringValue("replacement"))

    private fun move(
        source: String,
        target: String,
    ): YamlDocumentOperation.MovePath = YamlDocumentOperation.MovePath(YamlPath.parse(source), YamlPath.parse(target))

    private fun mapping(
        source: String,
        target: String?,
    ): YamlCommentMapping = YamlCommentMapping(YamlPath.parse(source), target?.let(YamlPath::parse))

    private fun migration(
        original: ByteArray,
        template: ByteArray,
        canonicalValues: Map<String, YamlNodeValue>,
        commentMappings: List<YamlCommentMapping> = emptyList(),
    ): YamlDocumentMigrationRequest =
        YamlDocumentMigrationRequest(
            original = original,
            template = template,
            canonicalValues = canonicalValues.mapKeys { (path, _) -> YamlPath.parse(path) },
            commentMappings = commentMappings,
        )

    private fun assertRejected(
        result: YamlDocumentEditResult,
        category: YamlDocumentRejectionCategory,
    ) {
        assertEquals(category, assertInstanceOf<YamlDocumentEditResult.Rejected>(result).rejection.category)
    }

    private fun String.utf8(): ByteArray = toByteArray(StandardCharsets.UTF_8)
}
