@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.github.command1264.itemdropv2.platform.bukkit.yaml

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

@Suppress("TooManyFunctions")
internal class StrictYamlDocumentScanner : YamlDocumentScanner {
    @Suppress("ReturnCount")
    override fun scan(bytes: ByteArray): YamlDocumentScanResult {
        val source = bytes.copyOf()
        val hasUtf8Bom = source.hasUtf8Bom()
        val contentStart = if (hasUtf8Bom) UTF8_BOM.size else 0
        if (!isValidUtf8(source, contentStart)) {
            return rejected(YamlDocumentRejectionCategory.ENCODING, "Document is not valid UTF-8.")
        }
        val split =
            splitLines(source, contentStart)
                ?: return rejected(YamlDocumentRejectionCategory.NEWLINE, "Document uses an unsupported newline sequence.")
        val newline = split.newline ?: YamlNewline.NONE
        if (split.hasMixedNewlines) {
            return rejected(YamlDocumentRejectionCategory.NEWLINE, "Document mixes LF and CRLF newline sequences.")
        }

        return scanLines(source, hasUtf8Bom, newline, split.lines)
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun scanLines(
        source: ByteArray,
        hasUtf8Bom: Boolean,
        newline: YamlNewline,
        rawLines: List<RawLine>,
    ): YamlDocumentScanResult {
        val lines = mutableListOf<MutableLine>()
        val entries = mutableListOf<MutableEntry>()
        val entriesByPath = mutableSetOf<YamlPath>()
        val containers = mutableListOf<Container>()
        val pendingComments = mutableListOf<PendingComment>()
        var blockScalarIndent: Int? = null
        var sequenceContext: SequenceContext? = null

        rawLines.forEachIndexed { lineIndex, rawLine ->
            val text = source.decodeRange(rawLine.contentStart, rawLine.contentEnd)
            val indentation =
                indentationOf(text)
                    ?: return rejected(YamlDocumentRejectionCategory.STRUCTURE, "Document uses tab indentation.")
            val content = text.substring(indentation)
            val line = MutableLine(rawLine, text, indentation)

            val activeBlockIndent = blockScalarIndent
            if (activeBlockIndent != null && content.isNotEmpty() && indentation <= activeBlockIndent) {
                blockScalarIndent = null
            }
            val currentBlockScalarIndent = blockScalarIndent
            if (currentBlockScalarIndent != null && content.isBlank()) {
                line.constructFlags += YamlConstructFlag.BLOCK_SCALAR_CONTENT
                pendingComments.clear()
                lines += line
                return@forEachIndexed
            }
            if (currentBlockScalarIndent != null && content.isNotEmpty() && indentation > currentBlockScalarIndent) {
                line.constructFlags += YamlConstructFlag.BLOCK_SCALAR_CONTENT
                lines += line
                return@forEachIndexed
            }

            val activeSequence = sequenceContext
            if (activeSequence != null && content.isNotBlank()) {
                when {
                    indentation > activeSequence.indent -> {
                        line.constructFlags += YamlConstructFlag.SEQUENCE
                        lines += line
                        return@forEachIndexed
                    }
                    indentation < activeSequence.indent || !isSequence(content) -> sequenceContext = null
                }
            }

            if (content.isBlank()) {
                pendingComments.clear()
                lines += line
                return@forEachIndexed
            }
            if (content.startsWith('#')) {
                pendingComments += PendingComment(indentation, lineIndex, text)
                lines += line
                return@forEachIndexed
            }

            if (isSequence(content)) {
                val continuingSequence = sequenceContext?.takeIf { it.indent == indentation }
                val owner =
                    continuingSequence
                        ?: sequenceOwner(entries, containers, lineIndex, indentation)
                if (owner == null) {
                    return rejected(YamlDocumentRejectionCategory.STRUCTURE, "Sequence indentation has no enclosing block mapping.")
                }
                line.constructFlags += YamlConstructFlag.SEQUENCE
                owner.ownerPath?.let { path -> entries.lastOrNull { it.path == path }?.constructFlags?.add(YamlConstructFlag.SEQUENCE) }
                sequenceContext = owner
                pendingComments.clear()
                lines += line
                return@forEachIndexed
            }

            val mapping = parseMapping(content)
            if (mapping == null) {
                pendingComments.clear()
                lines += line
                return@forEachIndexed
            }

            pendingComments.filterTo(mutableListOf()) { it.indent == indentation }.also { owned ->
                pendingComments.clear()
                val key = mapping.key

                while (containers.isNotEmpty() && containers.last().indent >= indentation) {
                    containers.removeAt(containers.lastIndex)
                }
                val parent = containers.lastOrNull()
                if (!acceptsChildIndentation(parent, indentation)) {
                    return rejected(YamlDocumentRejectionCategory.STRUCTURE, "Mapping indentation has no enclosing block mapping.")
                }
                val parentPath = parent?.path
                if (key == "<<") {
                    line.constructFlags += YamlConstructFlag.MERGE_KEY
                    markContainingMappings(entries, containers, indentation, YamlConstructFlag.MERGE_KEY)
                    lines += line
                    return@forEachIndexed
                }
                if (!isPlainPathKey(key)) {
                    line.constructFlags += unsupportedKeyFlags(key)
                    markContainingMappings(entries, containers, indentation, YamlConstructFlag.UNSUPPORTED_MAPPING_KEY)
                    if (mapping.isBlockMapping()) {
                        containers += Container(path = null, indent = indentation)
                    }
                    lines += line
                    return@forEachIndexed
                }
                if (parent != null && parentPath == null) {
                    line.constructFlags += YamlConstructFlag.UNSUPPORTED_MAPPING_KEY
                    markContainingMappings(entries, containers, indentation, YamlConstructFlag.UNSUPPORTED_MAPPING_KEY)
                    if (mapping.isBlockMapping()) {
                        containers += Container(path = null, indent = indentation)
                    }
                    lines += line
                    return@forEachIndexed
                }
                val path =
                    parentPath?.let { path ->
                        YamlPath.of(*(path.segments + key).toTypedArray())
                    } ?: YamlPath.of(key)
                if (!entriesByPath.add(path)) {
                    return rejected(YamlDocumentRejectionCategory.DUPLICATE_PATH, "Document contains a duplicate mapping path.")
                }

                val absoluteContentStart = rawLine.contentStart + utf8Length(text.substring(0, indentation))
                val keySpan = YamlByteSpan(absoluteContentStart, absoluteContentStart + utf8Length(key))
                val valueStart = rawLine.contentStart + utf8Length(text.substring(0, indentation + mapping.colonIndex + 1))
                val value = mapping.value?.let { parseValue(it, valueStart) }
                val isBlockMapping = mapping.value == null || value?.text.isNullOrEmpty()
                val flags = value?.flags.orEmpty().toMutableSet()
                line.mappingPath = path
                line.keySpan = keySpan
                line.valueSpan = value?.span
                line.inlineComment = value?.inlineComment
                line.constructFlags += flags
                val entry =
                    MutableEntry(
                        path = path,
                        lineIndex = lineIndex,
                        indent = indentation,
                        keySpan = keySpan,
                        valueSpan = value?.span,
                        valueText = value?.text,
                        ownedLeadingComments = owned.map(PendingComment::text),
                        inlineComment = value?.inlineComment,
                        ownedStart = owned.firstOrNull()?.let { rawLines[it.lineIndex].contentStart } ?: rawLine.contentStart,
                        constructFlags = flags,
                    )
                entries += entry
                lines += line

                if (isBlockMapping) {
                    containers += Container(path, indentation)
                }
                if (value?.flags?.contains(YamlConstructFlag.BLOCK_SCALAR) == true) {
                    blockScalarIndent = indentation
                }
            }
        }

        val immutableEntries =
            entries.map { entry ->
                val nextSibling =
                    entries
                        .asSequence()
                        .filter { candidate -> candidate.lineIndex > entry.lineIndex && candidate.indent <= entry.indent }
                        .minByOrNull(MutableEntry::lineIndex)
                val end = nextSibling?.ownedStart ?: source.size
                YamlMappingEntry(
                    path = entry.path,
                    lineIndex = entry.lineIndex,
                    indent = entry.indent,
                    keySpan = entry.keySpan,
                    valueSpan = entry.valueSpan,
                    valueText = entry.valueText,
                    ownedLeadingComments = entry.ownedLeadingComments,
                    inlineComment = entry.inlineComment,
                    subtreeSpan = YamlByteSpan(entry.ownedStart, end),
                    constructFlags = entry.constructFlags,
                )
            }
        val immutableLines =
            lines.map { line ->
                YamlDocumentLine(
                    rawBytes = source.copyOfRange(line.raw.contentStart, line.raw.contentEnd),
                    text = line.text,
                    newlineBytes = source.copyOfRange(line.raw.contentEnd, line.raw.endExclusive),
                    byteSpan = YamlByteSpan(line.raw.contentStart, line.raw.endExclusive),
                    indent = line.indent,
                    mappingPath = line.mappingPath,
                    keySpan = line.keySpan,
                    valueSpan = line.valueSpan,
                    inlineComment = line.inlineComment,
                    flags = line.constructFlags,
                )
            }
        return YamlDocumentScanResult.Scanned(ScannedYamlDocument(hasUtf8Bom, newline, immutableLines, immutableEntries))
    }

    private fun parseMapping(content: String): Mapping? {
        val colonIndex =
            content.indices.firstOrNull { index ->
                content[index] == ':' && (index + 1 == content.length || content[index + 1].isWhitespace() || content[index + 1] == '#')
            } ?: return null
        // YAML permits whitespace between a plain key token and its mapping colon. The token span
        // intentionally excludes that formatting whitespace while the colon index remains raw.
        val key = content.substring(0, colonIndex).trimEnd()
        return Mapping(key, colonIndex, content.substring(colonIndex + 1).takeIf { it.isNotEmpty() })
    }

    private fun parseValue(
        value: String,
        absoluteStart: Int,
    ): Value {
        val leadingWhitespace = value.indexOfFirst { !it.isWhitespace() }.let { if (it == -1) value.length else it }
        val content = value.substring(leadingWhitespace)
        val inlineIndex =
            when {
                content.startsWith('#') && leadingWhitespace > 0 -> 0
                else -> inlineCommentIndex(content)
            }
        val beforeComment = if (inlineIndex == null) content else content.substring(0, inlineIndex)
        val text = beforeComment.trimEnd()
        val start = absoluteStart + utf8Length(value.substring(0, leadingWhitespace))
        val end = start + utf8Length(text)
        val comment =
            inlineIndex?.let { index ->
                val commentText = content.substring(index)
                val commentStart = start + utf8Length(content.substring(0, index))
                YamlInlineComment(commentText, YamlByteSpan(commentStart, commentStart + utf8Length(commentText)))
            }
        return Value(text, YamlByteSpan(start, end), comment, constructFlags(text))
    }

    private fun constructFlags(value: String): Set<YamlConstructFlag> =
        buildSet {
            val leading = value.trimStart()
            if (leading.startsWith('|') || leading.startsWith('>')) add(YamlConstructFlag.BLOCK_SCALAR)
            if (leading.startsWith('{') || leading.startsWith('[')) add(YamlConstructFlag.FLOW_COLLECTION)
            if (leading.startsWith('&')) add(YamlConstructFlag.ANCHOR)
            if (leading.startsWith('*')) add(YamlConstructFlag.ALIAS)
        }

    private fun unsupportedKeyFlags(key: String): Set<YamlConstructFlag> =
        buildSet {
            add(YamlConstructFlag.UNSUPPORTED_MAPPING_KEY)
            if (key == "<<") add(YamlConstructFlag.MERGE_KEY)
        }

    private fun isPlainPathKey(key: String): Boolean =
        key.isNotEmpty() &&
            key.none(Char::isWhitespace) &&
            key.none { it in "[]{} ,&*!?|>'\"%@`" } &&
            runCatching { YamlPath.of(key) }.isSuccess

    private fun isSequence(content: String): Boolean = content == "-" || content.startsWith("- ")

    private fun Mapping.isBlockMapping(): Boolean = value == null || value.isBlank() || value.trimStart().startsWith('#')

    private fun acceptsChildIndentation(
        parent: Container?,
        indentation: Int,
    ): Boolean =
        when {
            parent == null -> indentation == 0
            indentation <= parent.indent -> false
            parent.childIndent == null -> {
                parent.childIndent = indentation
                true
            }
            else -> parent.childIndent == indentation
        }

    private fun markContainingMappings(
        entries: List<MutableEntry>,
        containers: List<Container>,
        indentation: Int,
        flag: YamlConstructFlag,
    ) {
        containers
            .asSequence()
            .filter { it.indent < indentation }
            .forEach { container ->
                container.path?.let { path -> entries.lastOrNull { it.path == path }?.constructFlags?.add(flag) }
            }
    }

    private fun sequenceOwner(
        entries: List<MutableEntry>,
        containers: List<Container>,
        lineIndex: Int,
        indentation: Int,
    ): SequenceContext? =
        containers.lastOrNull().let { parent ->
            when {
                parent != null && acceptsChildIndentation(parent, indentation) ->
                    SequenceContext(indentation, parent.path)
                else ->
                    entries
                        .lastOrNull { entry ->
                            entry.lineIndex == lineIndex - 1 &&
                                entry.indent == indentation &&
                                entry.valueText.isNullOrEmpty() &&
                                parent?.path == entry.path &&
                                parent.indent == indentation
                        }?.let { immediateOwner -> SequenceContext(indentation, immediateOwner.path) }
            }
        }

    private fun indentationOf(text: String): Int? {
        val firstContent = text.indexOfFirst { it != ' ' }
        return when {
            firstContent == -1 -> text.length
            text[firstContent] == '\t' -> null
            else -> firstContent
        }
    }

    private fun inlineCommentIndex(value: String): Int? {
        var singleQuoted = false
        var doubleQuoted = false
        var escaped = false
        value.forEachIndexed { index, character ->
            when {
                escaped -> escaped = false
                doubleQuoted && character == '\\' -> escaped = true
                !doubleQuoted && character == '\'' -> singleQuoted = !singleQuoted
                !singleQuoted && character == '\"' -> doubleQuoted = !doubleQuoted
                !singleQuoted && !doubleQuoted && character == '#' && index > 0 && value[index - 1].isWhitespace() -> return index
            }
        }
        return null
    }

    private fun isValidUtf8(
        source: ByteArray,
        start: Int,
    ): Boolean =
        try {
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(source, start, source.size - start))
            true
        } catch (_: CharacterCodingException) {
            false
        }

    private fun splitLines(
        source: ByteArray,
        start: Int,
    ): SplitLines? {
        val lines = mutableListOf<RawLine>()
        var cursor = start
        var lineStart = start
        var newline: YamlNewline? = null
        var mixed = false
        while (cursor < source.size) {
            when (source[cursor]) {
                LF -> {
                    val style = if (cursor > lineStart && source[cursor - 1] == CR) YamlNewline.CRLF else YamlNewline.LF
                    val contentEnd = if (style == YamlNewline.CRLF) cursor - 1 else cursor
                    if (newline != null && newline != style) mixed = true
                    newline = newline ?: style
                    lines += RawLine(lineStart, contentEnd, cursor + 1)
                    lineStart = cursor + 1
                }
                CR -> if (cursor + 1 == source.size || source[cursor + 1] != LF) return null
            }
            cursor++
        }
        if (lineStart < source.size) lines += RawLine(lineStart, source.size, source.size)
        return SplitLines(lines, newline, mixed)
    }

    private fun ByteArray.hasUtf8Bom(): Boolean = size >= UTF8_BOM.size && UTF8_BOM.indices.all { this[it] == UTF8_BOM[it] }

    private fun ByteArray.decodeRange(
        start: Int,
        endExclusive: Int,
    ): String = String(this, start, endExclusive - start, StandardCharsets.UTF_8)

    private fun utf8Length(text: String): Int = text.toByteArray(StandardCharsets.UTF_8).size

    private fun rejected(
        category: YamlDocumentRejectionCategory,
        detail: String,
    ): YamlDocumentScanResult.Rejected =
        YamlDocumentScanResult.Rejected(YamlDocumentRejection(category = category, detail = detail.take(MAX_REJECTION_DETAIL_LENGTH)))

    private data class RawLine(
        val contentStart: Int,
        val contentEnd: Int,
        val endExclusive: Int,
    )

    private data class SplitLines(
        val lines: List<RawLine>,
        val newline: YamlNewline?,
        val hasMixedNewlines: Boolean,
    )

    private data class Mapping(
        val key: String,
        val colonIndex: Int,
        val value: String?,
    )

    private data class Value(
        val text: String,
        val span: YamlByteSpan,
        val inlineComment: YamlInlineComment?,
        val flags: Set<YamlConstructFlag>,
    )

    private data class SequenceContext(
        val indent: Int,
        val ownerPath: YamlPath?,
    )

    private class Container(
        val path: YamlPath?,
        val indent: Int,
        var childIndent: Int? = null,
    )

    private data class PendingComment(
        val indent: Int,
        val lineIndex: Int,
        val text: String,
    )

    private class MutableLine(
        val raw: RawLine,
        val text: String,
        val indent: Int,
        var mappingPath: YamlPath? = null,
        var keySpan: YamlByteSpan? = null,
        var valueSpan: YamlByteSpan? = null,
        var inlineComment: YamlInlineComment? = null,
        val constructFlags: MutableSet<YamlConstructFlag> = linkedSetOf(),
    )

    private class MutableEntry(
        val path: YamlPath,
        val lineIndex: Int,
        val indent: Int,
        val keySpan: YamlByteSpan,
        val valueSpan: YamlByteSpan?,
        val valueText: String?,
        val ownedLeadingComments: List<String>,
        val inlineComment: YamlInlineComment?,
        val ownedStart: Int,
        val constructFlags: MutableSet<YamlConstructFlag>,
    )

    private companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        const val LF: Byte = 0x0A
        const val CR: Byte = 0x0D
        const val MAX_REJECTION_DETAIL_LENGTH = 160
    }
}
