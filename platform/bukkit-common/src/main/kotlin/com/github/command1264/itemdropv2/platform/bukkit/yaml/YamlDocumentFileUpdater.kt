package com.github.command1264.itemdropv2.platform.bukkit.yaml

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal sealed interface YamlDocumentValidationResult {
    data object Valid : YamlDocumentValidationResult

    data class Invalid(
        val rejection: YamlDocumentRejection,
    ) : YamlDocumentValidationResult
}

internal fun interface YamlDocumentCandidateValidator {
    fun validate(candidate: ByteArray): YamlDocumentValidationResult
}

internal sealed interface YamlDocumentUpdateResult {
    data class Updated(
        val bytes: ByteArray,
        val ownership: YamlDocumentReplacementOwnership,
    ) : YamlDocumentUpdateResult

    data class Rejected(
        val rejection: YamlDocumentRejection,
    ) : YamlDocumentUpdateResult
}

internal class YamlDocumentReplacementOwnership(
    expectedCurrentBytes: ByteArray,
) {
    private val expectedBytes: ByteArray = expectedCurrentBytes.copyOf()

    val expectedCurrentBytes: ByteArray
        get() = expectedBytes.copyOf()
}

internal class YamlDocumentOwnedReplacementFailure(
    val ownership: YamlDocumentReplacementOwnership,
    val failure: Exception,
) : RuntimeException(failure)

internal fun ownedYamlDocumentReplacementFailure(
    expectedCurrentBytes: ByteArray,
    failure: Exception,
): YamlDocumentOwnedReplacementFailure =
    YamlDocumentOwnedReplacementFailure(
        ownership = YamlDocumentReplacementOwnership(expectedCurrentBytes),
        failure = failure,
    )

internal class YamlDocumentFileUpdater {
    private val fileReplacer: (Path, Path) -> Unit
    private val candidateWriter: (Path, ByteArray) -> Unit

    constructor(
        fileReplacer: (Path, Path) -> Unit = ::moveReplacing,
    ) {
        this.fileReplacer = fileReplacer
        this.candidateWriter = ::writeCandidate
    }

    // Preserves the one-lambda production call shape while keeping write failures deterministic in tests.
    internal constructor(
        fileReplacer: (Path, Path) -> Unit,
        candidateWriter: (Path, ByteArray) -> Unit,
    ) {
        this.fileReplacer = fileReplacer
        this.candidateWriter = candidateWriter
    }

    fun update(
        target: Path,
        candidate: ByteArray,
        validator: YamlDocumentCandidateValidator,
    ): YamlDocumentUpdateResult {
        val normalizedTarget = target.toAbsolutePath().normalize()
        val parent = requireNotNull(normalizedTarget.parent) { "YAML document target must have a parent directory." }
        var temporary: Path? = null

        try {
            temporary = Files.createTempFile(parent, ".yaml-document-", ".tmp")
            candidateWriter(temporary, candidate)
            val validatedBytes = Files.readAllBytes(temporary)

            return when (val validation = validator.validate(validatedBytes.copyOf())) {
                YamlDocumentValidationResult.Valid -> {
                    // Post-replacement bytes verify persistence but cannot prove what this transaction wrote.
                    fileReplacer(temporary, normalizedTarget)
                    val persistedBytes = Files.readAllBytes(normalizedTarget)
                    YamlDocumentUpdateResult.Updated(
                        bytes = persistedBytes,
                        ownership = YamlDocumentReplacementOwnership(validatedBytes),
                    )
                }
                is YamlDocumentValidationResult.Invalid -> YamlDocumentUpdateResult.Rejected(validation.rejection)
            }
        } finally {
            temporary?.let { Files.deleteIfExists(it) }
        }
    }
}

private fun writeCandidate(
    target: Path,
    candidate: ByteArray,
) {
    Files.write(target, candidate)
}

private fun moveReplacing(
    source: Path,
    target: Path,
) {
    moveReplacing(source, target) { moveSource, moveTarget, options ->
        Files.move(moveSource, moveTarget, *options)
    }
}

// Internal test seam for the AtomicMoveNotSupportedException fallback; production uses the two-argument overload.
internal fun moveReplacing(
    source: Path,
    target: Path,
    fileMover: (Path, Path, Array<CopyOption>) -> Path,
) {
    try {
        fileMover(source, target, arrayOf(ATOMIC_MOVE, REPLACE_EXISTING))
    } catch (_: AtomicMoveNotSupportedException) {
        fileMover(source, target, arrayOf(REPLACE_EXISTING))
    }
}
