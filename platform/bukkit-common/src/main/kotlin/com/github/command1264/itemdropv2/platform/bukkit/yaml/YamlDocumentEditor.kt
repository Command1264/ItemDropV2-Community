@file:Suppress("MatchingDeclarationName", "ktlint:standard:filename")

package com.github.command1264.itemdropv2.platform.bukkit.yaml

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.util.Locale

@Suppress("LargeClass", "TooManyFunctions")
internal class StrictYamlDocumentEditor(
    private val scanner: YamlDocumentScanner = StrictYamlDocumentScanner(),
) : YamlDocumentEditor {
    @Suppress("ReturnCount")
    override fun edit(request: YamlDocumentEditRequest): YamlDocumentEditResult {
        val original =
            when (val scan = scanner.scan(request.original)) {
                is YamlDocumentScanResult.Scanned -> scan.document
                is YamlDocumentScanResult.Rejected -> return YamlDocumentEditResult.Rejected(scan.rejection)
            }
        validateLegacyFooter(original, template = false)?.let { rejection ->
            return YamlDocumentEditResult.Rejected(rejection)
        }
        val template =
            when (val scan = scanner.scan(request.template)) {
                is YamlDocumentScanResult.Scanned -> scan.document
                is YamlDocumentScanResult.Rejected ->
                    return reject(
                        YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
                        "Template cannot be safely scanned for YAML edits.",
                    )
            }
        validateLegacyFooter(template, template = true)?.let { rejection ->
            return YamlDocumentEditResult.Rejected(rejection)
        }

        val operationRejection = validateOperationConflicts(request.operations)
        if (operationRejection != null) return YamlDocumentEditResult.Rejected(operationRejection)

        var candidate = request.original.copyOf()
        request.operations.forEach { operation ->
            when (val step = applyOperation(candidate, request.template, template, operation)) {
                is EditStep.Candidate -> candidate = step.bytes
                is EditStep.Rejected -> return YamlDocumentEditResult.Rejected(step.rejection)
            }
        }

        return YamlDocumentEditResult.Candidate(candidate)
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    override fun migrate(request: YamlDocumentMigrationRequest): YamlDocumentEditResult {
        val original =
            when (val scan = scanner.scan(request.original)) {
                is YamlDocumentScanResult.Scanned -> scan.document
                is YamlDocumentScanResult.Rejected -> return YamlDocumentEditResult.Rejected(scan.rejection)
            }
        val template =
            when (val scan = scanner.scan(request.template)) {
                is YamlDocumentScanResult.Scanned -> scan.document
                is YamlDocumentScanResult.Rejected ->
                    return reject(
                        YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
                        "Template cannot be safely scanned for YAML migration.",
                    )
            }
        validateLegacyFooter(original, template = false)?.let { rejection ->
            return YamlDocumentEditResult.Rejected(rejection)
        }
        validateLegacyFooter(template, template = true)?.let { rejection ->
            return YamlDocumentEditResult.Rejected(rejection)
        }
        val validation = validateMigrationRequest(template, request)
        if (validation != null) return YamlDocumentEditResult.Rejected(validation)

        var candidate = request.template.copyOf()
        template.entries
            .asSequence()
            .mapNotNull { entry -> request.canonicalValues[entry.path]?.let { value -> entry.path to value } }
            .forEach { (path, value) ->
                when (val step = applyCanonicalValue(candidate, path, value)) {
                    is EditStep.Candidate -> candidate = step.bytes
                    is EditStep.Rejected -> return YamlDocumentEditResult.Rejected(step.rejection)
                }
            }

        val comments = migrationComments(original, template, request.commentMappings)
        when (val inserted = insertMappedComments(candidate, comments.mapped)) {
            is EditStep.Candidate -> candidate = inserted.bytes
            is EditStep.Rejected -> return YamlDocumentEditResult.Rejected(inserted.rejection)
        }
        when (val footer = appendLegacyFooter(candidate, comments.footer.map(CommentOccurrence::text))) {
            is EditStep.Candidate -> candidate = footer.bytes
            is EditStep.Rejected -> return YamlDocumentEditResult.Rejected(footer.rejection)
        }
        return when (val scan = scanner.scan(candidate)) {
            is YamlDocumentScanResult.Scanned -> YamlDocumentEditResult.Candidate(candidate)
            is YamlDocumentScanResult.Rejected -> YamlDocumentEditResult.Rejected(scan.rejection)
        }
    }

    private fun applyOperation(
        candidate: ByteArray,
        templateBytes: ByteArray,
        template: ScannedYamlDocument,
        operation: YamlDocumentOperation,
    ): EditStep =
        when (val scan = scanner.scan(candidate)) {
            is YamlDocumentScanResult.Rejected -> EditStep.Rejected(scan.rejection)
            is YamlDocumentScanResult.Scanned -> applyOperation(candidate, templateBytes, scan.document, template, operation)
        }

    @Suppress("ReturnCount")
    private fun applyOperation(
        candidate: ByteArray,
        templateBytes: ByteArray,
        document: ScannedYamlDocument,
        template: ScannedYamlDocument,
        operation: YamlDocumentOperation,
    ): EditStep {
        if (operation is YamlDocumentOperation.MovePath) {
            return movePath(candidate, templateBytes, document, template, operation)
        }
        if (operation is YamlDocumentOperation.SetValue) {
            return applyCanonicalValue(candidate, operation.path, operation.value)
        }
        val outcome =
            when (operation) {
                is YamlDocumentOperation.SetScalar -> setScalarPatch(document, operation)
                is YamlDocumentOperation.EnsurePath -> ensurePathPatch(candidate, templateBytes, document, template, operation)
                is YamlDocumentOperation.MovePath -> error("MovePath is handled before scalar patch selection.")
                is YamlDocumentOperation.SetValue -> error("SetValue is handled before patch selection.")
            }
        val nextCandidate =
            when (outcome) {
                is YamlPatchOutcome.Patch -> applyPatch(candidate, outcome.patch)
                YamlPatchOutcome.Unchanged -> candidate
                is YamlPatchOutcome.Rejected -> return EditStep.Rejected(outcome.rejection)
            }
        return when (val scan = scanner.scan(nextCandidate)) {
            is YamlDocumentScanResult.Scanned -> EditStep.Candidate(nextCandidate)
            is YamlDocumentScanResult.Rejected -> EditStep.Rejected(scan.rejection)
        }
    }

    private fun validateOperationConflicts(operations: List<YamlDocumentOperation>): YamlDocumentRejection? {
        val claimedPaths = operations.flatMap { operation -> operation.claimedPaths() }
        val conflict =
            claimedPaths.indices.any { index ->
                claimedPaths.drop(index + 1).any { other -> claimedPaths[index].overlaps(other) }
            }
        return if (conflict) {
            YamlDocumentRejection(
                category = YamlDocumentRejectionCategory.OPERATION_CONFLICT,
                detail = "YAML operations target the same or overlapping paths.",
            )
        } else {
            null
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ReturnCount")
    private fun movePath(
        candidate: ByteArray,
        templateBytes: ByteArray,
        document: ScannedYamlDocument,
        template: ScannedYamlDocument,
        operation: YamlDocumentOperation.MovePath,
    ): EditStep {
        val source =
            document.entryOrNull(operation.source)
                ?: return rejectedStep(
                    YamlDocumentRejectionCategory.VALIDATION,
                    operation.source,
                    "YAML move source does not exist.",
                )
        val templateTarget =
            template.entryOrNull(operation.target)
                ?: return rejectedStep(
                    YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
                    operation.target,
                    "Template does not contain the YAML move target.",
                )
        val validation = validateMoveEntries(document, template, source, templateTarget, operation)
        if (validation != null) return EditStep.Rejected(validation)

        val existingTarget = document.entryOrNull(operation.target)
        val movedBytes = renderMovedSubtree(document, source, templateTarget)
        val obsoleteComments =
            if (existingTarget == null) {
                emptyList()
            } else {
                extractComments(document)
                    .filter { occurrence -> occurrence.owner?.isAtOrBelow(operation.source) == true }
                    .map(CommentOccurrence::text)
            }
        var updated = candidate

        if (existingTarget == null) {
            updated =
                when (
                    val ensured =
                        ensurePathPatch(updated, templateBytes, document, template, YamlDocumentOperation.EnsurePath(operation.target))
                ) {
                    is YamlPatchOutcome.Patch -> applyPatch(updated, ensured.patch)
                    is YamlPatchOutcome.Rejected -> return EditStep.Rejected(ensured.rejection)
                    YamlPatchOutcome.Unchanged -> updated
                }
            val ensuredDocument =
                when (val scan = scanner.scan(updated)) {
                    is YamlDocumentScanResult.Scanned -> scan.document
                    is YamlDocumentScanResult.Rejected -> return EditStep.Rejected(scan.rejection)
                }
            val insertedTarget =
                ensuredDocument.entryOrNull(operation.target)
                    ?: return rejectedStep(
                        YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
                        operation.target,
                        "Template insertion did not create the YAML move target.",
                    )
            updated = applyPatch(updated, YamlPatch(ensuredDocument.copySpanFor(insertedTarget), movedBytes))
        }

        val beforeRemoval =
            when (val scan = scanner.scan(updated)) {
                is YamlDocumentScanResult.Scanned -> scan.document
                is YamlDocumentScanResult.Rejected -> return EditStep.Rejected(scan.rejection)
            }
        val currentSource =
            beforeRemoval.entryOrNull(operation.source)
                ?: return rejectedStep(
                    YamlDocumentRejectionCategory.VALIDATION,
                    operation.source,
                    "YAML move source disappeared before removal.",
                )
        val sourceSpan = beforeRemoval.copySpanFor(currentSource)
        val sourceReplacement = beforeRemoval.standaloneBytesIn(sourceSpan)
        val removalSpan = beforeRemoval.moveSourceRemovalSpan(sourceSpan)
        updated = applyPatch(updated, YamlPatch(removalSpan, sourceReplacement))
        when (val cleaned = removeSafelyEmptyParents(updated, operation.source)) {
            is EditStep.Candidate -> updated = cleaned.bytes
            is EditStep.Rejected -> return cleaned
        }
        when (val footer = appendLegacyFooter(updated, obsoleteComments)) {
            is EditStep.Candidate -> updated = footer.bytes
            is EditStep.Rejected -> return footer
        }
        return when (val scan = scanner.scan(updated)) {
            is YamlDocumentScanResult.Scanned -> EditStep.Candidate(updated)
            is YamlDocumentScanResult.Rejected -> EditStep.Rejected(scan.rejection)
        }
    }

    @Suppress("ReturnCount")
    private fun validateMoveEntries(
        document: ScannedYamlDocument,
        template: ScannedYamlDocument,
        source: YamlMappingEntry,
        templateTarget: YamlMappingEntry,
        operation: YamlDocumentOperation.MovePath,
    ): YamlDocumentRejection? {
        if (document.unsupportedConstructFor(operation.source) != null || document.hasUnsupportedConstructIn(source.subtreeSpan)) {
            return YamlDocumentRejection(
                YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
                operation.source,
                "YAML move source or its subtree uses an unsupported construct.",
            )
        }
        if (template.unsupportedConstructFor(operation.target) != null || template.hasUnsupportedConstructIn(templateTarget.subtreeSpan)) {
            return templateMismatchRejection(operation.target, "Template YAML move target uses an unsupported construct.")
        }
        if (source.isMappingSection() != templateTarget.isMappingSection()) {
            return YamlDocumentRejection(
                YamlDocumentRejectionCategory.VALIDATION,
                operation.target,
                "YAML move source and template target have incompatible node types.",
            )
        }
        val existingTarget = document.entryOrNull(operation.target) ?: return null
        if (
            document.unsupportedConstructFor(operation.target) != null ||
            document.hasUnsupportedConstructIn(existingTarget.subtreeSpan)
        ) {
            return YamlDocumentRejection(
                YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
                operation.target,
                "Existing YAML move target uses an unsupported construct.",
            )
        }
        return if (existingTarget.isMappingSection() != templateTarget.isMappingSection()) {
            YamlDocumentRejection(
                YamlDocumentRejectionCategory.VALIDATION,
                operation.target,
                "Existing YAML move target has an incompatible node type.",
            )
        } else {
            null
        }
    }

    private fun renderMovedSubtree(
        document: ScannedYamlDocument,
        source: YamlMappingEntry,
        target: YamlMappingEntry,
    ): ByteArray {
        val span = document.copySpanFor(source)
        val indentDelta = target.indent - source.indent
        val ownedCommentStarts =
            extractComments(document)
                .asSequence()
                .filter { occurrence -> occurrence.owner?.isAtOrBelow(source.path) == true }
                .map(CommentOccurrence::lineStart)
                .toSet()
        val targetKey =
            target.path.segments
                .last()
                .toByteArray(StandardCharsets.UTF_8)
        return document.lines
            .asSequence()
            .filter { line -> line.byteSpan.start >= span.start && line.byteSpan.start < span.endExclusive }
            .filter { line ->
                line.mappingPath?.isAtOrBelow(source.path) == true || line.byteSpan.start in ownedCommentStarts
            }.map { line ->
                val raw =
                    if (line.mappingPath == source.path) {
                        val keyStart = source.keySpan.start - line.byteSpan.start
                        val keyEnd = source.keySpan.endExclusive - line.byteSpan.start
                        line.rawBytes.copyOfRange(0, keyStart) + targetKey + line.rawBytes.copyOfRange(keyEnd, line.rawBytes.size)
                    } else {
                        line.rawBytes
                    }
                reindent(raw, indentDelta) + line.newlineBytes
            }.fold(EMPTY_BYTES) { rendered, line -> rendered + line }
    }

    private fun reindent(
        line: ByteArray,
        indentDelta: Int,
    ): ByteArray =
        when {
            line.isEmpty() || indentDelta == 0 -> line
            indentDelta > 0 -> ByteArray(indentDelta) { SPACE } + line
            else -> {
                val removable = minOf(-indentDelta, line.takeWhile { byte -> byte == SPACE }.size)
                line.copyOfRange(removable, line.size)
            }
        }

    @Suppress("ReturnCount")
    private fun removeSafelyEmptyParents(
        candidate: ByteArray,
        source: YamlPath,
    ): EditStep {
        var updated = candidate
        var parentPath = source.parent()
        while (parentPath != null) {
            val document =
                when (val scan = scanner.scan(updated)) {
                    is YamlDocumentScanResult.Scanned -> scan.document
                    is YamlDocumentScanResult.Rejected -> return EditStep.Rejected(scan.rejection)
                }
            val parent = document.entryOrNull(parentPath) ?: return EditStep.Candidate(updated)
            if (!parent.isMappingSection() || document.directChildren(parentPath).isNotEmpty() || !document.canRemoveEmptyParent(parent)) {
                return EditStep.Candidate(updated)
            }
            updated = applyPatch(updated, YamlPatch(document.copySpanFor(parent), EMPTY_BYTES))
            parentPath = parentPath.parent()
        }
        return EditStep.Candidate(updated)
    }

    private fun ScannedYamlDocument.canRemoveEmptyParent(parent: YamlMappingEntry): Boolean {
        if (parent.ownedLeadingComments.isNotEmpty() || parent.inlineComment != null || parent.constructFlags.isNotEmpty()) return false
        val nextBoundary =
            entries
                .asSequence()
                .filter { candidate -> candidate.lineIndex > parent.lineIndex && candidate.indent <= parent.indent }
                .minByOrNull(YamlMappingEntry::lineIndex)
                ?.let { entry -> lines[entry.lineIndex].byteSpan.start }
                ?: lines
                    .lastOrNull()
                    ?.byteSpan
                    ?.endExclusive
                    .orZero()
        return lines
            .asSequence()
            .filter { line -> line.lineStartsAfter(parent, this) && line.byteSpan.start < nextBoundary }
            .all { line -> line.text.isBlank() }
    }

    private fun YamlDocumentLine.lineStartsAfter(
        entry: YamlMappingEntry,
        document: ScannedYamlDocument,
    ): Boolean = byteSpan.start >= document.lines[entry.lineIndex].byteSpan.endExclusive

    private fun ScannedYamlDocument.standaloneBytesIn(span: YamlByteSpan): ByteArray {
        val standaloneStarts =
            extractComments(this)
                .filter { occurrence -> occurrence.kind == CommentKind.STANDALONE }
                .map(CommentOccurrence::lineStart)
                .toSet()
        return lines
            .asSequence()
            .filter { line -> line.byteSpan.start >= span.start && line.byteSpan.start < span.endExclusive }
            .filter { line -> line.text.isBlank() || line.byteSpan.start in standaloneStarts }
            .map { line -> line.rawBytes + line.newlineBytes }
            .fold(EMPTY_BYTES) { preserved, line -> preserved + line }
    }

    private fun ScannedYamlDocument.moveSourceRemovalSpan(sourceSpan: YamlByteSpan): YamlByteSpan {
        val previous = lines.lastOrNull { line -> line.byteSpan.endExclusive <= sourceSpan.start }
        val next = lines.firstOrNull { line -> line.byteSpan.start >= sourceSpan.endExclusive }
        val deletionWouldJoinBlankLines =
            previous?.byteSpan?.endExclusive == sourceSpan.start &&
                previous.text.isBlank() &&
                previous.constructFlags.isEmpty() &&
                next?.byteSpan?.start == sourceSpan.endExclusive &&
                next.text.isBlank() &&
                next.constructFlags.isEmpty()
        return if (deletionWouldJoinBlankLines) {
            YamlByteSpan(previous.byteSpan.start, sourceSpan.endExclusive)
        } else {
            sourceSpan
        }
    }

    @Suppress("ReturnCount")
    private fun setScalarPatch(
        document: ScannedYamlDocument,
        operation: YamlDocumentOperation.SetScalar,
    ): YamlPatchOutcome {
        if (document.unsupportedConstructFor(operation.path) != null) {
            return rejectedPatch(
                YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
                operation.path,
                "YAML scalar target or an ancestor uses an unsupported construct.",
            )
        }
        val target =
            document.entryOrNull(operation.path)
                ?: return rejectedPatch(YamlDocumentRejectionCategory.VALIDATION, operation.path, "YAML scalar target does not exist.")
        if (target.valueSpan == null || target.valueText.isNullOrEmpty()) {
            return rejectedPatch(YamlDocumentRejectionCategory.VALIDATION, operation.path, "YAML scalar target is a mapping section.")
        }
        return when (val rendered = renderScalar(operation.value)) {
            is ScalarRender.Rendered ->
                YamlPatchOutcome.Patch(
                    YamlPatch(target.valueSpan, rendered.value.toByteArray(StandardCharsets.UTF_8)),
                )
            is ScalarRender.Rejected -> YamlPatchOutcome.Rejected(rendered.rejection)
        }
    }

    private fun ensurePathPatch(
        candidate: ByteArray,
        templateBytes: ByteArray,
        document: ScannedYamlDocument,
        template: ScannedYamlDocument,
        operation: YamlDocumentOperation.EnsurePath,
    ): YamlPatchOutcome =
        when (val selection = selectEnsureSubtree(document, template, operation.path)) {
            is EnsureSelection.Selected ->
                ensureInsertionPatch(
                    candidate,
                    templateBytes,
                    document,
                    template,
                    operation.path,
                    selection.entry,
                    selection.copySpan,
                )
            EnsureSelection.Unchanged -> YamlPatchOutcome.Unchanged
            is EnsureSelection.Rejected -> YamlPatchOutcome.Rejected(selection.rejection)
        }

    @Suppress("ReturnCount")
    private fun selectEnsureSubtree(
        document: ScannedYamlDocument,
        template: ScannedYamlDocument,
        path: YamlPath,
    ): EnsureSelection {
        if (document.unsupportedConstructFor(path) != null) {
            return rejectedEnsureSelection(
                YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
                path,
                "YAML ensure target or an ancestor uses an unsupported construct.",
            )
        }
        if (template.unsupportedConstructFor(path) != null) {
            return templateMismatchSelection(path, "Template ensure target or an ancestor is unsafe to copy.")
        }
        val templateTarget =
            template.entryOrNull(path)
                ?: return templateMismatchSelection(path, "Template does not contain the required YAML path.")
        if (template.hasUnsupportedConstructIn(templateTarget.subtreeSpan)) {
            return templateMismatchSelection(path, "Template ensure target subtree contains an unsupported construct.")
        }
        val existingTarget = document.entryOrNull(path)
        if (existingTarget != null) {
            return if (document.hasUnsupportedConstructIn(existingTarget.subtreeSpan)) {
                rejectedEnsureSelection(
                    YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
                    path,
                    "Existing YAML ensure target subtree contains an unsupported construct.",
                )
            } else {
                EnsureSelection.Unchanged
            }
        }

        val missingRootPath =
            path.prefixes().firstOrNull { document.entryOrNull(it) == null }
                ?: return EnsureSelection.Unchanged
        val missingRoot =
            template.entryOrNull(missingRootPath)
                ?: return templateMismatchSelection(path, "Template cannot provide the complete missing YAML subtree.")
        val copySpan = template.templateCopySpanFor(missingRoot)
        if (template.hasUnsupportedConstructIn(copySpan)) {
            return templateMismatchSelection(path, "Template YAML subtree contains an unsupported construct.")
        }
        if (document.newline == YamlNewline.NONE) {
            return rejectedEnsureSelection(
                YamlDocumentRejectionCategory.NEWLINE,
                path,
                "Original newline style cannot be determined for YAML insertion.",
            )
        }
        val parentRejection = insertionParentRejection(document, path, missingRoot)
        if (parentRejection != null) return EnsureSelection.Rejected(parentRejection)
        return EnsureSelection.Selected(missingRoot, copySpan)
    }

    private fun ensureInsertionPatch(
        candidate: ByteArray,
        templateBytes: ByteArray,
        document: ScannedYamlDocument,
        template: ScannedYamlDocument,
        path: YamlPath,
        missingRoot: YamlMappingEntry,
        copySpan: YamlByteSpan,
    ): YamlPatchOutcome {
        val insertionOffset =
            insertionOffset(
                candidateSize = candidate.size,
                document = document,
                template = template,
                templateEntry = missingRoot,
            ) ?: return rejectedPatch(
                YamlDocumentRejectionCategory.UNSUPPORTED_CONSTRUCT,
                path,
                "YAML insertion sibling uses an unsupported construct.",
            )
        val rawTemplateSpan = templateBytes.copyOfRange(copySpan.start, copySpan.endExclusive)
        val converted = convertNewlines(rawTemplateSpan, template.newline, document.newline)
        val insertion = addRequiredBoundaryNewlines(candidate, insertionOffset, converted, document.newline)
        return YamlPatchOutcome.Patch(YamlPatch(YamlByteSpan(insertionOffset, insertionOffset), insertion))
    }

    @Suppress("ReturnCount")
    private fun insertionParentRejection(
        document: ScannedYamlDocument,
        path: YamlPath,
        missingRoot: YamlMappingEntry,
    ): YamlDocumentRejection? {
        val parentPath = missingRoot.path.parent() ?: return null
        val parent =
            document.entryOrNull(parentPath)
                ?: return templateMismatchRejection(path, "Template cannot create the required YAML parent chain.")
        return when {
            !parent.isMappingSection() ->
                YamlDocumentRejection(
                    YamlDocumentRejectionCategory.VALIDATION,
                    parentPath,
                    "Existing YAML parent is a scalar and cannot contain the required path.",
                )
            !hasCompatibleInsertionIndent(document, parent, missingRoot) ->
                templateMismatchRejection(path, "Template indentation does not match the original YAML parent.")
            else -> null
        }
    }

    private fun insertionOffset(
        candidateSize: Int,
        document: ScannedYamlDocument,
        template: ScannedYamlDocument,
        templateEntry: YamlMappingEntry,
    ): Int? {
        val siblings = template.directChildren(templateEntry.path.parent())
        val templateIndex = siblings.indexOfFirst { it.path == templateEntry.path }
        val previous =
            siblings
                .take(templateIndex)
                .asReversed()
                .firstNotNullOfOrNull { sibling -> document.entryOrNull(sibling.path) }
        val next =
            siblings
                .drop(templateIndex + 1)
                .firstNotNullOfOrNull { sibling -> document.entryOrNull(sibling.path) }
        return when {
            previous != null -> document.structuralEndFor(previous)
            next != null -> next.subtreeSpan.start
            else -> templateEntry.path.parent()?.let { document.structuralEndFor(document.entry(it)) } ?: candidateSize
        }
    }

    private fun hasCompatibleInsertionIndent(
        document: ScannedYamlDocument,
        parent: YamlMappingEntry?,
        templateEntry: YamlMappingEntry,
    ): Boolean {
        if (parent == null) return templateEntry.indent == 0
        val existingChildIndent = document.directChildren(parent.path).firstOrNull()?.indent
        return existingChildIndent?.let { templateEntry.indent == it }
            ?: (templateEntry.indent > parent.indent)
    }

    private fun addRequiredBoundaryNewlines(
        candidate: ByteArray,
        insertionOffset: Int,
        inserted: ByteArray,
        newline: YamlNewline,
    ): ByteArray {
        val separator = newline.bytes()
        val needsPrefix = insertionOffset > bomLength(candidate) && candidate[insertionOffset - 1] != LF
        val needsSuffix = insertionOffset < candidate.size && (inserted.isEmpty() || inserted.last() != LF)
        val needsFooterSeparator =
            insertionOffset < candidate.size &&
                candidate.previousLineIsBlank(insertionOffset) &&
                candidate.nextLineIsComment(insertionOffset)
        return (if (needsPrefix) separator else EMPTY_BYTES) +
            inserted +
            (if (needsSuffix) separator else EMPTY_BYTES) +
            (if (needsFooterSeparator) separator else EMPTY_BYTES)
    }

    private fun ByteArray.previousLineIsBlank(offset: Int): Boolean {
        var contentEnd = offset
        if (contentEnd > 0 && this[contentEnd - 1] == LF) contentEnd--
        if (contentEnd > 0 && this[contentEnd - 1] == CR) contentEnd--
        var contentStart = contentEnd
        while (contentStart > 0 && this[contentStart - 1] != LF) contentStart--
        return (contentStart until contentEnd).all { index -> this[index] == SPACE }
    }

    private fun ByteArray.nextLineIsComment(offset: Int): Boolean {
        var cursor = offset
        while (cursor < size && this[cursor] == SPACE) cursor++
        return cursor < size && this[cursor] == HASH
    }

    private fun convertNewlines(
        bytes: ByteArray,
        source: YamlNewline,
        target: YamlNewline,
    ): ByteArray {
        if (source == target || source == YamlNewline.NONE) return bytes.copyOf()
        val asLf =
            if (source == YamlNewline.CRLF) {
                String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n")
            } else {
                String(bytes, StandardCharsets.UTF_8)
            }
        return when (target) {
            YamlNewline.CRLF -> asLf.replace("\n", "\r\n").toByteArray(StandardCharsets.UTF_8)
            YamlNewline.LF -> asLf.toByteArray(StandardCharsets.UTF_8)
            YamlNewline.NONE -> bytes.copyOf()
        }
    }

    private fun YamlNewline.bytes(): ByteArray =
        when (this) {
            YamlNewline.LF -> byteArrayOf(LF)
            YamlNewline.CRLF -> byteArrayOf(CR, LF)
            YamlNewline.NONE -> EMPTY_BYTES
        }

    private fun bomLength(bytes: ByteArray): Int =
        if (bytes.size >= UTF8_BOM.size && UTF8_BOM.indices.all { bytes[it] == UTF8_BOM[it] }) UTF8_BOM.size else 0

    private fun YamlPath.prefixes(): List<YamlPath> =
        segments.indices.map { lastIndex -> YamlPath.of(*segments.take(lastIndex + 1).toTypedArray()) }

    private fun ScannedYamlDocument.directChildren(parent: YamlPath?): List<YamlMappingEntry> =
        entries.filter { entry -> entry.path.parent() == parent }

    private fun ScannedYamlDocument.hasUnsupportedConstructIn(span: YamlByteSpan): Boolean =
        lines.any { line ->
            line.byteSpan.start >= span.start && line.byteSpan.start < span.endExclusive && line.constructFlags.isNotEmpty()
        }

    private fun ScannedYamlDocument.copySpanFor(entry: YamlMappingEntry): YamlByteSpan {
        val lastLineIndex =
            entries
                .asSequence()
                .filter { candidate -> candidate.path.isAtOrBelow(entry.path) }
                .maxOf(YamlMappingEntry::lineIndex)
        return YamlByteSpan(entry.subtreeSpan.start, lines[lastLineIndex].byteSpan.endExclusive)
    }

    private fun ScannedYamlDocument.templateCopySpanFor(entry: YamlMappingEntry): YamlByteSpan {
        val structuralSpan = copySpanFor(entry)
        var lineIndex = entry.lineIndex - 1
        while (lineIndex >= 0 && lines[lineIndex].isSameIndentComment(entry.indent)) {
            lineIndex--
        }
        return if (lineIndex == entry.lineIndex - 1 || lineIndex < 0 || lines[lineIndex].text.isNotBlank()) {
            structuralSpan
        } else {
            while (lineIndex > 0 && lines[lineIndex - 1].text.isBlank()) {
                lineIndex--
            }
            YamlByteSpan(lines[lineIndex].byteSpan.start, structuralSpan.endExclusive)
        }
    }

    private fun YamlDocumentLine.isSameIndentComment(expectedIndent: Int): Boolean =
        indent == expectedIndent && text.drop(expectedIndent).startsWith('#')

    private fun ScannedYamlDocument.structuralEndFor(entry: YamlMappingEntry): Int =
        lines
            .asSequence()
            .filter { line -> line.byteSpan.start >= entry.subtreeSpan.start && line.byteSpan.start < entry.subtreeSpan.endExclusive }
            .filter { line -> line.mappingPath?.isAtOrBelow(entry.path) == true || line.constructFlags.isNotEmpty() }
            .maxOf { line -> line.byteSpan.endExclusive }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun validateMigrationRequest(
        template: ScannedYamlDocument,
        request: YamlDocumentMigrationRequest,
    ): YamlDocumentRejection? {
        request.commentMappings.forEach { mapping ->
            val target = mapping.target
            if (target != null && template.entryOrNull(target) == null) {
                return templateMismatchRejection(target, "Template does not contain a mapped YAML comment target.")
            }
        }
        request.canonicalValues.forEach { (path, value) ->
            val entry =
                template.entryOrNull(path)
                    ?: return templateMismatchRejection(path, "Template does not contain a canonical YAML value path.")
            when (value) {
                is YamlNodeValue.Scalar -> {
                    if (entry.valueSpan == null || entry.valueText.isNullOrEmpty() || entry.constructFlags.isNotEmpty()) {
                        return templateMismatchRejection(path, "Template canonical path is not a supported scalar.")
                    }
                    val rendered = renderScalar(value.value)
                    if (rendered is ScalarRender.Rejected) return rendered.rejection
                }
                is YamlNodeValue.ScalarSequence -> {
                    if (!template.isScalarSequence(entry)) {
                        return templateMismatchRejection(path, "Template canonical path is not a supported scalar sequence.")
                    }
                    value.values.forEach { scalar ->
                        val rendered = renderScalar(scalar)
                        if (rendered is ScalarRender.Rejected) return rendered.rejection
                    }
                }
            }
        }
        return null
    }

    private fun ScannedYamlDocument.isScalarSequence(entry: YamlMappingEntry): Boolean {
        if (entry.constructFlags != setOf(YamlConstructFlag.SEQUENCE)) return false
        val structuralEnd = structuralEndFor(entry)
        return lines
            .asSequence()
            .filter { line -> line.byteSpan.start > lines[entry.lineIndex].byteSpan.start && line.byteSpan.start < structuralEnd }
            .filterNot { line -> line.text.isBlank() || line.text.trimStart().startsWith('#') }
            .all { line ->
                val content = line.text.trimStart()
                line.constructFlags == setOf(YamlConstructFlag.SEQUENCE) &&
                    (content == "-" || content.startsWith("- ")) &&
                    content.removePrefix("-").trimStart().let { scalar ->
                        scalar.isEmpty() || scalar.isSupportedSequenceScalar()
                    }
            }
    }

    private fun String.isSupportedSequenceScalar(): Boolean {
        val hasMappingColon = indices.any { index -> this[index] == ':' && (index == lastIndex || this[index + 1].isWhitespace()) }
        return when {
            first() in "[{&*!|>?" || this == "-" || startsWith("- ") -> false
            first() == '\'' -> hasValidQuotedScalar('\'')
            first() == '"' -> hasValidQuotedScalar('"')
            else -> !hasMappingColon
        }
    }

    private fun String.hasValidQuotedScalar(quote: Char): Boolean {
        var index = 1
        while (index < length) {
            when {
                quote == '"' && this[index] == '\\' -> index += 2
                this[index] != quote -> index++
                quote == '\'' && index + 1 < length && this[index + 1] == quote -> index += 2
                else -> {
                    val remainder = substring(index + 1)
                    return remainder.isBlank() ||
                        (remainder.firstOrNull()?.isWhitespace() == true && remainder.trimStart().startsWith('#'))
                }
            }
        }
        return false
    }

    @Suppress("ReturnCount")
    private fun applyCanonicalValue(
        candidate: ByteArray,
        path: YamlPath,
        value: YamlNodeValue,
    ): EditStep {
        val document =
            when (val scan = scanner.scan(candidate)) {
                is YamlDocumentScanResult.Scanned -> scan.document
                is YamlDocumentScanResult.Rejected -> return EditStep.Rejected(scan.rejection)
            }
        val outcome =
            when (value) {
                is YamlNodeValue.Scalar -> setScalarPatch(document, YamlDocumentOperation.SetScalar(path, value.value))
                is YamlNodeValue.ScalarSequence -> scalarSequencePatch(document, path, value)
            }
        val updated =
            when (outcome) {
                is YamlPatchOutcome.Patch -> applyPatch(candidate, outcome.patch)
                is YamlPatchOutcome.Rejected -> return EditStep.Rejected(outcome.rejection)
                YamlPatchOutcome.Unchanged -> candidate
            }
        return when (val scan = scanner.scan(updated)) {
            is YamlDocumentScanResult.Scanned -> EditStep.Candidate(updated)
            is YamlDocumentScanResult.Rejected -> EditStep.Rejected(scan.rejection)
        }
    }

    @Suppress("ReturnCount")
    private fun scalarSequencePatch(
        document: ScannedYamlDocument,
        path: YamlPath,
        value: YamlNodeValue.ScalarSequence,
    ): YamlPatchOutcome {
        val entry =
            document.entryOrNull(path)
                ?: return rejectedPatch(
                    YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
                    path,
                    "Canonical YAML sequence path disappeared during rendering.",
                )
        val header = document.lines[entry.lineIndex]
        if (document.newline == YamlNewline.NONE || header.newlineBytes.isEmpty()) {
            return rejectedPatch(
                YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
                path,
                "Template scalar sequence has no usable line boundary.",
            )
        }
        val itemIndent =
            document.lines
                .asSequence()
                .filter { line ->
                    line.byteSpan.start >= header.byteSpan.endExclusive &&
                        line.byteSpan.start < document.structuralEndFor(entry) &&
                        line.constructFlags.contains(YamlConstructFlag.SEQUENCE)
                }.map(YamlDocumentLine::indent)
                .firstOrNull()
                ?: return rejectedPatch(
                    YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
                    path,
                    "Template scalar sequence indentation cannot be determined.",
                )
        val sequenceComments = document.sequenceCommentTexts(header.byteSpan.endExclusive, document.structuralEndFor(entry))
        if (value.values.isEmpty()) {
            return emptyScalarSequencePatch(document, entry, header, sequenceComments)
        }
        val renderedLines =
            sequenceComments
                .map { comment -> " ".repeat(itemIndent) + comment }
                .toMutableList()
        value.values.forEach { scalar ->
            when (val rendered = renderScalar(scalar)) {
                is ScalarRender.Rendered -> renderedLines += " ".repeat(itemIndent) + "- " + rendered.value
                is ScalarRender.Rejected -> return YamlPatchOutcome.Rejected(rendered.rejection)
            }
        }
        val renderedItems =
            renderedLines
                .joinToString(separator = document.newline.text(), postfix = document.newline.text())
                .toByteArray(StandardCharsets.UTF_8)
        return YamlPatchOutcome.Patch(
            YamlPatch(
                YamlByteSpan(header.byteSpan.endExclusive, document.structuralEndFor(entry)),
                renderedItems,
            ),
        )
    }

    private fun emptyScalarSequencePatch(
        document: ScannedYamlDocument,
        entry: YamlMappingEntry,
        header: YamlDocumentLine,
        comments: List<String>,
    ): YamlPatchOutcome {
        val rawHeader = header.rawBytes
        val keyEnd = entry.keySpan.endExclusive - header.byteSpan.start
        val colonIndex = (keyEnd until rawHeader.size).first { index -> rawHeader[index] == COLON }
        val inlineComment =
            entry.inlineComment
                ?.text
                ?.let { comment -> " $comment" }
                .orEmpty()
        val renderedComments =
            comments.joinToString(separator = document.newline.text(), postfix = document.newline.text()) { comment ->
                " ".repeat(entry.indent) + comment
            }
        val renderedHeader =
            rawHeader.copyOfRange(0, colonIndex + 1) +
                (" []" + inlineComment + document.newline.text()).toByteArray(StandardCharsets.UTF_8)
        return YamlPatchOutcome.Patch(
            YamlPatch(
                YamlByteSpan(header.byteSpan.start, document.structuralEndFor(entry)),
                renderedComments.toByteArray(StandardCharsets.UTF_8) + renderedHeader,
            ),
        )
    }

    private fun ScannedYamlDocument.sequenceCommentTexts(
        start: Int,
        endExclusive: Int,
    ): List<String> =
        lines
            .asSequence()
            .filter { line -> line.byteSpan.start >= start && line.byteSpan.start < endExclusive }
            .mapNotNull { line ->
                line.text
                    .trimStart()
                    .takeIf { text -> text.startsWith('#') }
                    ?: line.text.yamlInlineCommentText()
            }.toList()

    private fun migrationComments(
        original: ScannedYamlDocument,
        template: ScannedYamlDocument,
        mappings: List<YamlCommentMapping>,
    ): RoutedComments {
        val templateCounts =
            extractComments(template)
                .groupingBy { occurrence -> occurrence.fingerprint() }
                .eachCount()
                .toMutableMap()
        val nonTemplateComments =
            extractComments(original).filter { occurrence ->
                if (occurrence.kind == CommentKind.FOOTER) return@filter true
                val fingerprint = occurrence.fingerprint()
                val remaining = templateCounts[fingerprint].orZero()
                if (remaining == 0) {
                    true
                } else {
                    templateCounts[fingerprint] = remaining - 1
                    false
                }
            }
        val mappingsBySource = mappings.groupBy(YamlCommentMapping::source)
        val mappedTargets = mappings.mapNotNull(YamlCommentMapping::target).toSet()
        val targetsWithPresentSources =
            mappings
                .asSequence()
                .filter { mapping -> original.entryOrNull(mapping.source) != null }
                .mapNotNull(YamlCommentMapping::target)
                .toSet()
        val mapped = mutableListOf<MappedComment>()
        val footer = mutableListOf<CommentOccurrence>()
        nonTemplateComments.forEach { occurrence ->
            val owner = occurrence.owner
            val sourceMappings = owner?.let(mappingsBySource::get)
            val target =
                when {
                    occurrence.kind == CommentKind.FOOTER -> null
                    sourceMappings?.size == 1 -> sourceMappings.single().target
                    sourceMappings != null -> null
                    owner in mappedTargets && owner !in targetsWithPresentSources -> owner
                    else -> null
                }
            if (target == null) {
                footer += occurrence
            } else {
                mapped += MappedComment(target, occurrence)
            }
        }
        return RoutedComments(mapped, footer)
    }

    private fun extractComments(document: ScannedYamlDocument): List<CommentOccurrence> {
        val comments = mutableListOf<CommentOccurrence>()
        var inLegacyFooter = false
        document.lines.forEach { line ->
            val trimmed = line.text.trimStart()
            if (line.isLegacyFooterTitle()) {
                inLegacyFooter = true
                return@forEach
            }
            if (inLegacyFooter) {
                if (trimmed.startsWith('#')) {
                    comments += CommentOccurrence(line.byteSpan.start, null, trimmed, CommentKind.FOOTER)
                }
                return@forEach
            }
            if (trimmed.startsWith('#') && !line.constructFlags.contains(YamlConstructFlag.BLOCK_SCALAR_CONTENT)) {
                val owner = document.ownerOfLeadingComment(line) ?: document.ownerOfSequenceComment(line)
                comments +=
                    CommentOccurrence(
                        lineStart = line.byteSpan.start,
                        owner = owner,
                        text = trimmed,
                        kind = if (owner == null) CommentKind.STANDALONE else CommentKind.LEADING,
                    )
            } else {
                val inlineComment =
                    if (line.constructFlags.contains(YamlConstructFlag.BLOCK_SCALAR_CONTENT)) {
                        null
                    } else {
                        line.inlineComment?.text ?: line.text.yamlInlineCommentText()
                    }
                if (inlineComment != null) {
                    val owner = line.mappingPath ?: document.ownerOfSequenceComment(line)
                    comments += CommentOccurrence(line.byteSpan.start, owner, inlineComment, CommentKind.INLINE)
                }
            }
        }
        return comments
    }

    @Suppress("ReturnCount")
    private fun validateLegacyFooter(
        document: ScannedYamlDocument,
        template: Boolean,
    ): YamlDocumentRejection? {
        val footerLines = document.lines.withIndex().filter { (_, line) -> line.isLegacyFooterTitle() }
        if (footerLines.isEmpty()) return null
        if (template) {
            return YamlDocumentRejection(
                YamlDocumentRejectionCategory.TEMPLATE_MISMATCH,
                null,
                "Embedded YAML templates cannot contain the reserved legacy comment footer.",
            )
        }
        val footerIndex =
            footerLines.singleOrNull()?.index
                ?: return YamlDocumentRejection(
                    YamlDocumentRejectionCategory.STRUCTURE,
                    null,
                    "YAML documents can contain at most one reserved legacy comment footer.",
                )
        val hasDocumentContentAfterFooter =
            document.lines
                .asSequence()
                .drop(footerIndex + 1)
                .map { line -> line.text.trimStart() }
                .any { line -> line.isNotEmpty() && !line.startsWith('#') }
        return if (hasDocumentContentAfterFooter) {
            YamlDocumentRejection(
                YamlDocumentRejectionCategory.STRUCTURE,
                null,
                "The reserved legacy comment footer must be the terminal comment-only section.",
            )
        } else {
            null
        }
    }

    private fun YamlDocumentLine.isLegacyFooterTitle(): Boolean =
        text == LEGACY_FOOTER_TITLE && !constructFlags.contains(YamlConstructFlag.BLOCK_SCALAR_CONTENT)

    private fun ScannedYamlDocument.ownerOfLeadingComment(line: YamlDocumentLine): YamlPath? =
        entries
            .firstOrNull { entry ->
                val mappingStart = lines[entry.lineIndex].byteSpan.start
                line.byteSpan.start >= entry.subtreeSpan.start && line.byteSpan.start < mappingStart
            }?.path

    private fun ScannedYamlDocument.ownerOfSequenceComment(line: YamlDocumentLine): YamlPath? =
        entries
            .asSequence()
            .filter { entry -> entry.constructFlags.contains(YamlConstructFlag.SEQUENCE) }
            .filter { entry ->
                val header = lines[entry.lineIndex]
                line.byteSpan.start >= header.byteSpan.endExclusive && line.byteSpan.start < structuralEndFor(entry)
            }.maxByOrNull(YamlMappingEntry::indent)
            ?.path

    private fun String.yamlInlineCommentText(): String? {
        var singleQuoted = false
        var doubleQuoted = false
        var escaped = false
        forEachIndexed { index, character ->
            when {
                escaped -> escaped = false
                doubleQuoted && character == '\\' -> escaped = true
                !doubleQuoted && character == '\'' -> singleQuoted = !singleQuoted
                !singleQuoted && character == '"' -> doubleQuoted = !doubleQuoted
                !singleQuoted && !doubleQuoted && character == '#' && index > 0 && this[index - 1].isWhitespace() ->
                    return substring(index)
            }
        }
        return null
    }

    @Suppress("ReturnCount")
    private fun insertMappedComments(
        candidate: ByteArray,
        mapped: List<MappedComment>,
    ): EditStep {
        if (mapped.isEmpty()) return EditStep.Candidate(candidate)
        val document =
            when (val scan = scanner.scan(candidate)) {
                is YamlDocumentScanResult.Scanned -> scan.document
                is YamlDocumentScanResult.Rejected -> return EditStep.Rejected(scan.rejection)
            }
        if (document.newline == YamlNewline.NONE) {
            return rejectedStep(
                YamlDocumentRejectionCategory.NEWLINE,
                null,
                "YAML comment insertion requires a document newline style.",
            )
        }
        val patches =
            mapped
                .groupBy(MappedComment::target)
                .map { (target, comments) ->
                    val entry = document.entry(target)
                    val rendered =
                        comments
                            .joinToString(separator = document.newline.text(), postfix = document.newline.text()) { comment ->
                                " ".repeat(entry.indent) + comment.occurrence.text.trimStart()
                            }.toByteArray(StandardCharsets.UTF_8)
                    YamlPatch(YamlByteSpan(entry.subtreeSpan.start, entry.subtreeSpan.start), rendered)
                }.sortedByDescending(YamlPatch::start)
        val updated = patches.fold(candidate) { bytes, patch -> applyPatch(bytes, patch) }
        return when (val scan = scanner.scan(updated)) {
            is YamlDocumentScanResult.Scanned -> EditStep.Candidate(updated)
            is YamlDocumentScanResult.Rejected -> EditStep.Rejected(scan.rejection)
        }
    }

    @Suppress("ReturnCount")
    private fun appendLegacyFooter(
        candidate: ByteArray,
        comments: List<String>,
    ): EditStep {
        val preserved =
            comments
                .map(String::trimStart)
                .filter { comment -> comment.startsWith('#') && comment != LEGACY_FOOTER_TITLE }
        if (preserved.isEmpty()) return EditStep.Candidate(candidate)
        val document =
            when (val scan = scanner.scan(candidate)) {
                is YamlDocumentScanResult.Scanned -> scan.document
                is YamlDocumentScanResult.Rejected -> return EditStep.Rejected(scan.rejection)
            }
        if (document.newline == YamlNewline.NONE) {
            return rejectedStep(
                YamlDocumentRejectionCategory.NEWLINE,
                null,
                "Legacy YAML comment preservation requires a document newline style.",
            )
        }
        val newline = document.newline.bytes()
        val hasFooter = document.lines.any { line -> line.isLegacyFooterTitle() }
        var updated = candidate
        if (!updated.endsWith(newline)) updated += newline
        if (!hasFooter) {
            if (!updated.endsWith(newline + newline)) updated += newline
            updated += (LEGACY_FOOTER_TITLE + document.newline.text()).toByteArray(StandardCharsets.UTF_8)
        }
        preserved.forEach { comment -> updated += (comment + document.newline.text()).toByteArray(StandardCharsets.UTF_8) }
        return EditStep.Candidate(updated)
    }

    private fun ByteArray.endsWith(suffix: ByteArray): Boolean =
        size >= suffix.size && suffix.indices.all { index -> this[size - suffix.size + index] == suffix[index] }

    private fun YamlNewline.text(): String =
        when (this) {
            YamlNewline.LF -> "\n"
            YamlNewline.CRLF -> "\r\n"
            YamlNewline.NONE -> ""
        }

    private fun YamlPath.isAtOrBelow(ancestor: YamlPath): Boolean =
        segments.size >= ancestor.segments.size && segments.take(ancestor.segments.size) == ancestor.segments

    private fun YamlMappingEntry.isMappingSection(): Boolean = valueSpan == null || valueText.isNullOrEmpty()

    private fun rejectedEnsureSelection(
        category: YamlDocumentRejectionCategory,
        path: YamlPath,
        detail: String,
    ): EnsureSelection.Rejected = EnsureSelection.Rejected(YamlDocumentRejection(category, path, detail))

    private fun templateMismatchSelection(
        path: YamlPath,
        detail: String,
    ): EnsureSelection.Rejected = EnsureSelection.Rejected(templateMismatchRejection(path, detail))

    private fun templateMismatchRejection(
        path: YamlPath,
        detail: String,
    ): YamlDocumentRejection = YamlDocumentRejection(YamlDocumentRejectionCategory.TEMPLATE_MISMATCH, path, detail)

    private fun renderScalar(value: YamlScalar): ScalarRender =
        when (value) {
            is YamlScalar.BooleanValue -> ScalarRender.Rendered(value.value.toString())
            is YamlScalar.IntegerValue -> ScalarRender.Rendered(value.value.toString())
            is YamlScalar.DecimalValue -> renderDecimal(value.value)
            is YamlScalar.StringValue -> renderString(value.value)
        }

    private fun renderDecimal(value: BigDecimal): ScalarRender {
        val estimatedLength = value.estimatedPlainLength()
        if (estimatedLength > MAX_RENDERED_SCALAR_LENGTH) {
            return rejectedScalar("YAML decimal scalar is too large to render safely.")
        }
        return ScalarRender.Rendered(value.toPlainString())
    }

    private fun renderString(value: String): ScalarRender {
        if (value.any(Char::isISOControl)) {
            return rejectedScalar("YAML string scalar contains unsupported control characters.")
        }
        return ScalarRender.Rendered(if (value.isSafePlainYamlString()) value else "'${value.replace("'", "''")}'")
    }

    private fun rejectedPatch(
        category: YamlDocumentRejectionCategory,
        path: YamlPath?,
        detail: String,
    ): YamlPatchOutcome.Rejected = YamlPatchOutcome.Rejected(YamlDocumentRejection(category, path, detail))

    private fun rejectedStep(
        category: YamlDocumentRejectionCategory,
        path: YamlPath?,
        detail: String,
    ): EditStep.Rejected = EditStep.Rejected(YamlDocumentRejection(category, path, detail))

    private fun rejectedScalar(detail: String): ScalarRender.Rejected =
        ScalarRender.Rejected(YamlDocumentRejection(YamlDocumentRejectionCategory.VALIDATION, detail = detail))

    private fun reject(
        category: YamlDocumentRejectionCategory,
        detail: String,
    ): YamlDocumentEditResult.Rejected = YamlDocumentEditResult.Rejected(YamlDocumentRejection(category = category, detail = detail))

    private fun applyPatch(
        original: ByteArray,
        patch: YamlPatch,
    ): ByteArray = original.copyOfRange(0, patch.start) + patch.replacement + original.copyOfRange(patch.endExclusive, original.size)

    private fun ScannedYamlDocument.unsupportedConstructFor(path: YamlPath): YamlConstructFlag? {
        var current: YamlPath? = path
        while (current != null) {
            val flag = entryOrNull(current)?.constructFlags?.firstOrNull()
            if (flag != null) return flag
            current = current.parent()
        }
        return null
    }

    private fun YamlDocumentOperation.claimedPaths(): List<YamlPath> =
        when (this) {
            is YamlDocumentOperation.EnsurePath -> listOf(path)
            is YamlDocumentOperation.MovePath -> listOf(source, target)
            is YamlDocumentOperation.SetScalar -> listOf(path)
            is YamlDocumentOperation.SetValue -> listOf(path)
        }

    private fun YamlPath.overlaps(other: YamlPath): Boolean =
        segments.size <= other.segments.size && segments == other.segments.take(segments.size) ||
            other.segments.size <= segments.size && other.segments == segments.take(other.segments.size)

    private fun BigDecimal.estimatedPlainLength(): Long {
        val precision = precision().toLong()
        val scale = scale().toLong()
        return if (scale >= 0L) {
            maxOf(precision, scale + 1L) + 1L
        } else {
            precision + -scale + 1L
        }
    }

    private fun String.isSafePlainYamlString(): Boolean {
        if (!SAFE_PLAIN_STRING.matches(this)) return false
        return lowercase(Locale.ROOT) !in YAML_IMPLICIT_STRING_LITERALS
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun CommentOccurrence.fingerprint(): CommentFingerprint = CommentFingerprint(owner, text, kind == CommentKind.FOOTER)

    private data class YamlPatch(
        val span: YamlByteSpan,
        val replacement: ByteArray,
    ) {
        val start: Int = span.start
        val endExclusive: Int = span.endExclusive
    }

    private sealed interface EditStep {
        data class Candidate(
            val bytes: ByteArray,
        ) : EditStep

        data class Rejected(
            val rejection: YamlDocumentRejection,
        ) : EditStep
    }

    private sealed interface EnsureSelection {
        data class Selected(
            val entry: YamlMappingEntry,
            val copySpan: YamlByteSpan,
        ) : EnsureSelection

        data class Rejected(
            val rejection: YamlDocumentRejection,
        ) : EnsureSelection

        data object Unchanged : EnsureSelection
    }

    private sealed interface YamlPatchOutcome {
        data class Patch(
            val patch: YamlPatch,
        ) : YamlPatchOutcome

        data class Rejected(
            val rejection: YamlDocumentRejection,
        ) : YamlPatchOutcome

        data object Unchanged : YamlPatchOutcome
    }

    private sealed interface ScalarRender {
        data class Rendered(
            val value: String,
        ) : ScalarRender

        data class Rejected(
            val rejection: YamlDocumentRejection,
        ) : ScalarRender
    }

    private enum class CommentKind {
        LEADING,
        INLINE,
        STANDALONE,
        FOOTER,
    }

    private data class CommentOccurrence(
        val lineStart: Int,
        val owner: YamlPath?,
        val text: String,
        val kind: CommentKind,
    )

    private data class CommentFingerprint(
        val owner: YamlPath?,
        val text: String,
        val isFooter: Boolean,
    )

    private data class MappedComment(
        val target: YamlPath,
        val occurrence: CommentOccurrence,
    )

    private data class RoutedComments(
        val mapped: List<MappedComment>,
        val footer: List<CommentOccurrence>,
    )

    private companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val EMPTY_BYTES = byteArrayOf()
        const val LF: Byte = 0x0A
        const val CR: Byte = 0x0D
        const val SPACE: Byte = 0x20
        const val HASH: Byte = 0x23
        const val COLON: Byte = 0x3A
        const val MAX_RENDERED_SCALAR_LENGTH = 16_384L
        const val LEGACY_FOOTER_TITLE = "# Legacy comments preserved during migration"
        val SAFE_PLAIN_STRING = Regex("[A-Za-z_][A-Za-z0-9_-]*")
        val YAML_IMPLICIT_STRING_LITERALS = setOf("true", "false", "null", "~", "yes", "no", "on", "off", "y", "n")
    }
}
