package com.github.command1264.itemdropv2.platform.bukkit.yaml

import java.math.BigDecimal
import java.util.Collections

internal class YamlPath private constructor(
    segments: List<String>,
) {
    val segments: List<String> = immutableList(segments)
    val dotted: String = this.segments.joinToString(".")

    fun parent(): YamlPath? =
        segments
            .dropLast(1)
            .takeIf { it.isNotEmpty() }
            ?.let(::YamlPath)

    override fun equals(other: Any?): Boolean = other is YamlPath && segments == other.segments

    override fun hashCode(): Int = segments.hashCode()

    override fun toString(): String = dotted

    companion object {
        fun parse(dotted: String): YamlPath {
            require(dotted.isNotEmpty()) { "YAML path must not be empty." }
            return YamlPath(dotted.split('.').also(::validateSegments))
        }

        fun of(vararg segments: String): YamlPath = YamlPath(segments.toList().also(::validateSegments))

        private fun validateSegments(segments: List<String>) {
            require(segments.isNotEmpty()) { "YAML path must contain at least one segment." }
            segments.forEach { segment ->
                require(segment.isNotEmpty()) { "YAML path segments must not be empty." }
                require(segment != "." && !segment.contains('.')) { "YAML path segments must not contain '.'." }
                require(segment.none(Char::isISOControl)) { "YAML path segments must not contain control characters." }
            }
        }
    }
}

internal sealed interface YamlNodeValue {
    data class Scalar(
        val value: YamlScalar,
    ) : YamlNodeValue

    class ScalarSequence(
        values: List<YamlScalar>,
    ) : YamlNodeValue {
        val values: List<YamlScalar> = immutableList(values)

        override fun equals(other: Any?): Boolean = other is ScalarSequence && values == other.values

        override fun hashCode(): Int = values.hashCode()
    }
}

internal sealed interface YamlScalar {
    data class BooleanValue(
        val value: Boolean,
    ) : YamlScalar

    data class IntegerValue(
        val value: Long,
    ) : YamlScalar

    data class DecimalValue(
        val value: BigDecimal,
    ) : YamlScalar

    data class StringValue(
        val value: String,
    ) : YamlScalar
}

internal sealed interface YamlDocumentOperation {
    data class EnsurePath(
        val path: YamlPath,
    ) : YamlDocumentOperation

    data class MovePath(
        val source: YamlPath,
        val target: YamlPath,
    ) : YamlDocumentOperation

    data class SetScalar(
        val path: YamlPath,
        val value: YamlScalar,
    ) : YamlDocumentOperation

    data class SetValue(
        val path: YamlPath,
        val value: YamlNodeValue,
    ) : YamlDocumentOperation
}

internal data class YamlDocumentEditRequest(
    val original: ByteArray,
    val template: ByteArray,
    val operations: List<YamlDocumentOperation>,
)

internal enum class UnmappedCommentPolicy {
    PRESERVE_IN_LEGACY_FOOTER,
}

internal data class YamlCommentMapping(
    val source: YamlPath,
    val target: YamlPath?,
    val unmappedPolicy: UnmappedCommentPolicy = UnmappedCommentPolicy.PRESERVE_IN_LEGACY_FOOTER,
)

internal data class YamlDocumentMigrationRequest(
    val original: ByteArray,
    val template: ByteArray,
    val canonicalValues: Map<YamlPath, YamlNodeValue>,
    val commentMappings: List<YamlCommentMapping>,
)

internal sealed interface YamlDocumentEditResult {
    data class Candidate(
        val bytes: ByteArray,
    ) : YamlDocumentEditResult

    data class Rejected(
        val rejection: YamlDocumentRejection,
    ) : YamlDocumentEditResult
}

internal interface YamlDocumentEditor {
    fun edit(request: YamlDocumentEditRequest): YamlDocumentEditResult

    fun migrate(request: YamlDocumentMigrationRequest): YamlDocumentEditResult
}

internal enum class YamlDocumentRejectionCategory {
    ENCODING,
    NEWLINE,
    STRUCTURE,
    DUPLICATE_PATH,
    UNSUPPORTED_CONSTRUCT,
    OPERATION_CONFLICT,
    TEMPLATE_MISMATCH,
    VALIDATION,
    IO,
}

internal data class YamlDocumentRejection(
    val category: YamlDocumentRejectionCategory,
    val path: YamlPath? = null,
    val detail: String,
) {
    init {
        require(detail.length <= MAX_DETAIL_LENGTH) { "YAML document rejection detail exceeds $MAX_DETAIL_LENGTH characters." }
    }

    private companion object {
        const val MAX_DETAIL_LENGTH = 160
    }
}

internal sealed interface YamlDocumentScanResult {
    data class Scanned(
        val document: ScannedYamlDocument,
    ) : YamlDocumentScanResult

    data class Rejected(
        val rejection: YamlDocumentRejection,
    ) : YamlDocumentScanResult
}

internal fun interface YamlDocumentScanner {
    fun scan(bytes: ByteArray): YamlDocumentScanResult
}

internal enum class YamlNewline {
    LF,
    CRLF,
    NONE,
}

internal data class YamlByteSpan(
    val start: Int,
    val endExclusive: Int,
) {
    init {
        require(start >= 0) { "YAML byte spans must not start before the document." }
        require(endExclusive >= start) { "YAML byte spans must not end before they start." }
    }
}

internal enum class YamlConstructFlag {
    SEQUENCE,
    BLOCK_SCALAR,
    BLOCK_SCALAR_CONTENT,
    FLOW_COLLECTION,
    ANCHOR,
    ALIAS,
    MERGE_KEY,
    UNSUPPORTED_MAPPING_KEY,
}

internal class YamlInlineComment(
    val text: String,
    val span: YamlByteSpan,
)

internal class YamlDocumentLine(
    rawBytes: ByteArray,
    val text: String,
    newlineBytes: ByteArray,
    val byteSpan: YamlByteSpan,
    val indent: Int,
    val mappingPath: YamlPath?,
    val keySpan: YamlByteSpan?,
    val valueSpan: YamlByteSpan?,
    val inlineComment: YamlInlineComment?,
    flags: Set<YamlConstructFlag>,
) {
    val rawBytes: ByteArray
        get() = raw.copyOf()
    val newlineBytes: ByteArray
        get() = newline.copyOf()
    val constructFlags: Set<YamlConstructFlag> = immutableSet(flags)

    private val raw = rawBytes.copyOf()
    private val newline = newlineBytes.copyOf()
}

internal class YamlMappingEntry(
    val path: YamlPath,
    val lineIndex: Int,
    val indent: Int,
    val keySpan: YamlByteSpan,
    val valueSpan: YamlByteSpan?,
    val valueText: String?,
    ownedLeadingComments: List<String>,
    val inlineComment: YamlInlineComment?,
    val subtreeSpan: YamlByteSpan,
    constructFlags: Set<YamlConstructFlag>,
) {
    val ownedLeadingComments: List<String> = immutableList(ownedLeadingComments)
    val constructFlags: Set<YamlConstructFlag> = immutableSet(constructFlags)
}

internal class ScannedYamlDocument(
    val hasUtf8Bom: Boolean,
    val newline: YamlNewline,
    lines: List<YamlDocumentLine>,
    entries: List<YamlMappingEntry>,
) {
    val lines: List<YamlDocumentLine> = immutableList(lines)
    val entries: List<YamlMappingEntry> = immutableList(entries)

    private val entriesByPath: Map<YamlPath, YamlMappingEntry> =
        Collections.unmodifiableMap(LinkedHashMap(entries.associateBy(YamlMappingEntry::path)))

    fun entry(path: YamlPath): YamlMappingEntry = requireNotNull(entryOrNull(path)) { "No YAML mapping entry exists for path '$path'." }

    fun entryOrNull(path: YamlPath): YamlMappingEntry? = entriesByPath[path]
}

private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(values.toList())

private fun <T> immutableSet(values: Set<T>): Set<T> = Collections.unmodifiableSet(values.toSet())
