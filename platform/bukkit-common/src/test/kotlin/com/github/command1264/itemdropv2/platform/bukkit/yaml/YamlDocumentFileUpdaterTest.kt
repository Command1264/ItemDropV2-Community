package com.github.command1264.itemdropv2.platform.bukkit.yaml

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.CopyOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

class YamlDocumentFileUpdaterTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `writes a complete candidate in the target directory before validating and replacing`() {
        val target = writeTarget("config.yml", "previous: value\n")
        val candidate = "settings:\n  enabled: true\n".toByteArray(UTF_8)
        var validatorSawTargetBytes: ByteArray? = null
        var replacementObserved = false
        val updater =
            YamlDocumentFileUpdater { temporary, replacementTarget ->
                replacementObserved = true
                assertEquals(target.toAbsolutePath().normalize(), replacementTarget)
                assertEquals(replacementTarget.parent, temporary.parent)
                assertTrue(temporary.fileName.toString().startsWith(".yaml-document-"))
                assertFalse(temporary.fileName.toString().contains("itemdrop"))
                assertArrayEquals(candidate, Files.readAllBytes(temporary))
                Files.move(temporary, replacementTarget, REPLACE_EXISTING)
            }

        val result =
            updater.update(
                target = target,
                candidate = candidate,
                validator =
                    YamlDocumentCandidateValidator { bytes ->
                        validatorSawTargetBytes = Files.readAllBytes(target)
                        if (bytes.contentEquals(candidate)) YamlDocumentValidationResult.Valid else invalid("candidate is incomplete")
                    },
            )

        val updated = assertInstanceOf<YamlDocumentUpdateResult.Updated>(result)
        assertArrayEquals("previous: value\n".toByteArray(UTF_8), validatorSawTargetBytes)
        assertTrue(replacementObserved)
        assertArrayEquals(candidate, updated.bytes)
        assertArrayEquals(candidate, Files.readAllBytes(target))
        assertEquals(listOf(target), directoryEntries())
    }

    @Test
    fun `default replacer replaces an existing target on the current filesystem`() {
        val target = writeTarget("config.yml", "previous: value\n")
        val candidate = "settings: true\n".toByteArray(UTF_8)

        val result =
            YamlDocumentFileUpdater().update(
                target = target,
                candidate = candidate,
                validator = YamlDocumentCandidateValidator { YamlDocumentValidationResult.Valid },
            )

        assertArrayEquals(candidate, assertInstanceOf<YamlDocumentUpdateResult.Updated>(result).bytes)
        assertArrayEquals(candidate, Files.readAllBytes(target))
        assertEquals(listOf(target), directoryEntries())
    }

    @Test
    fun `successful replacement ownership remains the candidate when target changes before capture`() {
        val target = writeTarget("config.yml", "previous: value\n")
        val candidate = "settings: true\n".toByteArray(UTF_8)
        val external = "external-owner: true\n".toByteArray(UTF_8)
        val updater =
            YamlDocumentFileUpdater { source, replacementTarget ->
                Files.move(source, replacementTarget, REPLACE_EXISTING)
                Files.write(replacementTarget, external)
            }

        val result =
            updater.update(
                target = target,
                candidate = candidate,
                validator = YamlDocumentCandidateValidator { YamlDocumentValidationResult.Valid },
            )

        val updated = assertInstanceOf<YamlDocumentUpdateResult.Updated>(result)
        assertArrayEquals(external, updated.bytes)
        assertArrayEquals(candidate, updated.ownership.expectedCurrentBytes)
        assertArrayEquals(external, Files.readAllBytes(target))
    }

    @Test
    fun `replacement ownership does not expose mutable backing bytes`() {
        val target = writeTarget("config.yml", "previous: value\n")
        val candidate = "settings: true\n".toByteArray(UTF_8)
        val updated =
            assertInstanceOf<YamlDocumentUpdateResult.Updated>(
                YamlDocumentFileUpdater().update(
                    target = target,
                    candidate = candidate,
                    validator = YamlDocumentCandidateValidator { YamlDocumentValidationResult.Valid },
                ),
            )

        updated.ownership.expectedCurrentBytes.fill(0)

        assertArrayEquals(candidate, updated.ownership.expectedCurrentBytes)
    }

    @Test
    fun `rejects an invalid candidate without replacing the existing target or retaining a temp file`() {
        val target = writeTarget("config.yml", "previous: value\n")
        val rejection = invalid("config candidate is invalid").rejection
        var replacementAttempted = false
        val updater = YamlDocumentFileUpdater { _, _ -> replacementAttempted = true }

        val result =
            updater.update(
                target = target,
                candidate = "settings: [\n".toByteArray(UTF_8),
                validator = YamlDocumentCandidateValidator { YamlDocumentValidationResult.Invalid(rejection) },
            )

        assertEquals(YamlDocumentUpdateResult.Rejected(rejection), result)
        assertFalse(replacementAttempted)
        assertArrayEquals("previous: value\n".toByteArray(UTF_8), Files.readAllBytes(target))
        assertEquals(listOf(target), directoryEntries())
    }

    @Test
    fun `preserves the existing target and removes its temp file when validation throws`() {
        val target = writeTarget("config.yml", "previous: value\n")
        val updater = YamlDocumentFileUpdater { _, _ -> error("replacement must not run") }

        assertThrows(IOException::class.java) {
            updater.update(
                target = target,
                candidate = "settings: true\n".toByteArray(UTF_8),
                validator = YamlDocumentCandidateValidator { throw IOException("injected validation failure") },
            )
        }

        assertArrayEquals("previous: value\n".toByteArray(UTF_8), Files.readAllBytes(target))
        assertEquals(listOf(target), directoryEntries())
    }

    @Test
    fun `preserves the existing target and removes its temp file when injected replacement fails before mutation`() {
        val target = writeTarget("config.yml", "previous: value\n")
        var temporary: Path? = null
        val updater =
            YamlDocumentFileUpdater { source, _ ->
                temporary = source
                throw IOException("injected replacement failure")
            }

        assertThrows(IOException::class.java) {
            updater.update(
                target = target,
                candidate = "settings: true\n".toByteArray(UTF_8),
                validator = YamlDocumentCandidateValidator { YamlDocumentValidationResult.Valid },
            )
        }

        assertArrayEquals("previous: value\n".toByteArray(UTF_8), Files.readAllBytes(target))
        assertFalse(Files.exists(requireNotNull(temporary)))
        assertEquals(listOf(target), directoryEntries())
    }

    @Test
    fun `preserves the existing target and removes its temp file when candidate writing fails`() {
        val target = writeTarget("config.yml", "previous: value\n")
        var temporary: Path? = null
        val updater =
            YamlDocumentFileUpdater(
                fileReplacer = { _, _ -> error("replacement must not run") },
                candidateWriter = { path, _ ->
                    temporary = path
                    throw IOException("injected candidate write failure")
                },
            )

        assertThrows(IOException::class.java) {
            updater.update(
                target = target,
                candidate = "settings: true\n".toByteArray(UTF_8),
                validator = YamlDocumentCandidateValidator { error("validation must not run") },
            )
        }

        assertArrayEquals("previous: value\n".toByteArray(UTF_8), Files.readAllBytes(target))
        assertFalse(Files.exists(requireNotNull(temporary)))
        assertEquals(listOf(target), directoryEntries())
    }

    @Test
    fun `falls back to non-atomic replacement only when atomic replacement is unsupported`() {
        val target = writeTarget("config.yml", "previous: value\n")
        val candidate = "settings: true\n".toByteArray(UTF_8)
        val optionsByAttempt = mutableListOf<List<CopyOption>>()
        val updater =
            YamlDocumentFileUpdater { source, replacementTarget ->
                moveReplacing(source, replacementTarget) { moveSource, moveTarget, options ->
                    optionsByAttempt += options.toList()
                    if (options.contains(ATOMIC_MOVE)) {
                        throw AtomicMoveNotSupportedException(moveSource.toString(), moveTarget.toString(), "injected")
                    }
                    Files.move(moveSource, moveTarget, *options)
                }
            }

        val result =
            updater.update(
                target = target,
                candidate = candidate,
                validator = YamlDocumentCandidateValidator { YamlDocumentValidationResult.Valid },
            )

        assertArrayEquals(candidate, assertInstanceOf<YamlDocumentUpdateResult.Updated>(result).bytes)
        assertEquals(listOf(listOf(ATOMIC_MOVE, REPLACE_EXISTING), listOf(REPLACE_EXISTING)), optionsByAttempt)
        assertArrayEquals(candidate, Files.readAllBytes(target))
        assertEquals(listOf(target), directoryEntries())
    }

    @Test
    fun `preserves the existing target and removes its temp file when injected atomic fallback fails before mutation`() {
        val target = writeTarget("config.yml", "previous: value\n")
        var temporary: Path? = null
        val updater =
            YamlDocumentFileUpdater { source, replacementTarget ->
                temporary = source
                moveReplacing(source, replacementTarget) { moveSource, moveTarget, options ->
                    if (options.contains(ATOMIC_MOVE)) {
                        throw AtomicMoveNotSupportedException(moveSource.toString(), moveTarget.toString(), "injected")
                    }
                    throw IOException("injected fallback failure")
                }
            }

        assertThrows(IOException::class.java) {
            updater.update(
                target = target,
                candidate = "settings: true\n".toByteArray(UTF_8),
                validator = YamlDocumentCandidateValidator { YamlDocumentValidationResult.Valid },
            )
        }

        assertArrayEquals("previous: value\n".toByteArray(UTF_8), Files.readAllBytes(target))
        assertFalse(Files.exists(requireNotNull(temporary)))
        assertEquals(listOf(target), directoryEntries())
    }

    @Test
    fun `creates a missing target after each independent candidate shape validates`() {
        val candidates =
            listOf(
                "config.yml" to "settings:\n  enabled: true\n",
                "item-lifetime.yml" to "lifetime-seconds: 6000\n",
                "zh_TW.yml" to "messages:\n  reload: 已重新載入\n",
            )

        candidates.forEach { (filename, source) ->
            val target = temporaryDirectory.resolve(filename)
            val candidate = source.toByteArray(UTF_8)

            val result =
                YamlDocumentFileUpdater().update(
                    target = target,
                    candidate = candidate,
                    validator = YamlDocumentCandidateValidator(::validateSyntheticDocument),
                )

            assertArrayEquals(candidate, assertInstanceOf<YamlDocumentUpdateResult.Updated>(result).bytes)
            assertArrayEquals(candidate, Files.readAllBytes(target))
        }
    }

    private fun validateSyntheticDocument(candidate: ByteArray): YamlDocumentValidationResult {
        val text = candidate.toString(UTF_8)
        return when {
            text == "settings:\n  enabled: true\n" -> YamlDocumentValidationResult.Valid
            text == "lifetime-seconds: 6000\n" -> YamlDocumentValidationResult.Valid
            text == "messages:\n  reload: 已重新載入\n" -> YamlDocumentValidationResult.Valid
            else -> invalid("synthetic document does not match a supported shape")
        }
    }

    private fun writeTarget(
        filename: String,
        content: String,
    ): Path = temporaryDirectory.resolve(filename).also { Files.write(it, content.toByteArray(UTF_8)) }

    private fun directoryEntries(): List<Path> =
        Files.list(temporaryDirectory).use { entries ->
            entries
                .iterator()
                .asSequence()
                .toList()
                .sorted()
        }

    private fun invalid(detail: String): YamlDocumentValidationResult.Invalid =
        YamlDocumentValidationResult.Invalid(
            YamlDocumentRejection(
                category = YamlDocumentRejectionCategory.VALIDATION,
                detail = detail,
            ),
        )
}
