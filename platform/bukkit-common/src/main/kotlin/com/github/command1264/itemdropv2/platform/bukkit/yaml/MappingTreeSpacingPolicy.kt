package com.github.command1264.itemdropv2.platform.bukkit.yaml

import java.io.ByteArrayOutputStream

/**
 * Normalizes only mapping-sibling boundaries. Parent/first-child adjacency and sequence or
 * block-scalar contents remain untouched, so callers can apply this after a real repair without
 * turning a reload into an unrelated whole-document formatter.
 */
internal class MappingTreeSpacingPolicy(
    private val scanner: YamlDocumentScanner = StrictYamlDocumentScanner(),
) {
    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun apply(bytes: ByteArray): YamlDocumentEditResult {
        val document =
            when (val scanned = scanner.scan(bytes)) {
                is YamlDocumentScanResult.Scanned -> scanned.document
                is YamlDocumentScanResult.Rejected -> return YamlDocumentEditResult.Rejected(scanned.rejection)
            }
        val firstChildStarts = firstChildEntryStarts(document)
        val siblingStarts = siblingEntryStarts(document)
        val normalizedIndents = normalizedMappingIndents(document)
        val output = mutableListOf<RenderedLine>()
        document.lines.forEachIndexed { index, line ->
            if (index == document.lines.lastIndex && line.rawBytes.isEmpty() && line.newlineBytes.isEmpty()) {
                return@forEachIndexed
            }
            if (index in firstChildStarts) {
                while (output.lastOrNull()?.isRemovableBlank == true) output.removeAt(output.lastIndex)
            } else if (index in siblingStarts) {
                val previousContent = output.lastOrNull { rendered -> rendered.bytes.isNotEmpty() }
                if (previousContent?.isBlockScalarContent != true) {
                    while (output.lastOrNull()?.isRemovableBlank == true) output.removeAt(output.lastIndex)
                    output += RenderedLine(byteArrayOf(), isRemovableBlank = true, isBlockScalarContent = false)
                }
            }
            output +=
                RenderedLine(
                    bytes = normalizedLineBytes(line, normalizedIndents[index]),
                    isRemovableBlank =
                        line.text.isBlank() &&
                            YamlConstructFlag.BLOCK_SCALAR_CONTENT !in line.constructFlags,
                    isBlockScalarContent = YamlConstructFlag.BLOCK_SCALAR_CONTENT in line.constructFlags,
                )
        }
        while (output.firstOrNull()?.isRemovableBlank == true) output.removeAt(0)
        while (output.lastOrNull()?.isRemovableBlank == true) output.removeAt(output.lastIndex)

        val newline = if (document.newline == YamlNewline.CRLF) CRLF else LF
        val rendered = ByteArrayOutputStream(bytes.size + output.size * newline.size)
        if (document.hasUtf8Bom) rendered.write(UTF8_BOM)
        if (output.isEmpty()) {
            rendered.write(newline)
        } else {
            output.forEach { line ->
                rendered.write(line.bytes)
                rendered.write(newline)
            }
        }
        return YamlDocumentEditResult.Candidate(rendered.toByteArray())
    }

    private fun firstChildEntryStarts(document: ScannedYamlDocument): Set<Int> =
        document.entries
            .groupBy { entry -> entry.path.parent() }
            .mapNotNull { (parentPath, children) ->
                val parent = parentPath?.let(document::entryOrNull) ?: return@mapNotNull null
                val firstChild = children.minBy(YamlMappingEntry::lineIndex)
                val firstChildStart = firstChild.lineIndex - firstChild.ownedLeadingComments.size
                val boundaryContainsOnlyBlankLines =
                    (parent.lineIndex + 1 until firstChildStart).all { lineIndex ->
                        document.lines[lineIndex].text.isBlank()
                    }
                firstChildStart.takeIf { boundaryContainsOnlyBlankLines }
            }.toSet()

    private fun siblingEntryStarts(document: ScannedYamlDocument): Set<Int> =
        document.entries
            .groupBy { entry -> entry.path.parent() }
            .values
            .flatMap { siblings ->
                siblings
                    .sortedBy(YamlMappingEntry::lineIndex)
                    .drop(1)
                    .map { entry -> entry.lineIndex - entry.ownedLeadingComments.size }
            }.toSet()

    private fun normalizedMappingIndents(document: ScannedYamlDocument): Map<Int, Int> =
        buildMap {
            document.entries.forEach { entry ->
                val normalizedIndent = (entry.path.segments.size - 1) * SPACES_PER_MAPPING_DEPTH
                put(entry.lineIndex, normalizedIndent)
                repeat(entry.ownedLeadingComments.size) { offset ->
                    put(entry.lineIndex - offset - 1, normalizedIndent)
                }
            }
        }

    private fun normalizedLineBytes(
        line: YamlDocumentLine,
        normalizedIndent: Int?,
    ): ByteArray {
        if (normalizedIndent == null || line.indent == normalizedIndent) return line.rawBytes
        val content = line.rawBytes.copyOfRange(line.indent, line.rawBytes.size)
        return ByteArray(normalizedIndent) { SPACE } + content
    }

    private data class RenderedLine(
        val bytes: ByteArray,
        val isRemovableBlank: Boolean,
        val isBlockScalarContent: Boolean,
    )

    private companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val LF = byteArrayOf(0x0A)
        val CRLF = byteArrayOf(0x0D, 0x0A)
        const val SPACES_PER_MAPPING_DEPTH = 2
        const val SPACE: Byte = 0x20
    }
}
