package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

public class ItemStateJournalCheckpointStore(
    root: Path,
    private val codec: ItemStateJournalCodec = ItemStateJournalCodec(),
    private val mover: JournalAtomicMover = DefaultJournalAtomicMover,
    private val deleter: JournalFileDeleter = DefaultJournalFileDeleter,
    private val maximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
    private val maximumRecords: Int = DEFAULT_MAXIMUM_RECORDS,
) {
    private val directory = root.toAbsolutePath().normalize().resolve(CHECKPOINT_DIRECTORY)
    private val manifest = root.toAbsolutePath().normalize().resolve(MANIFEST_FILE)

    init {
        require(maximumBytes > 0) { "maximum checkpoint bytes must be positive" }
        require(maximumRecords > 0) { "maximum checkpoint records must be positive" }
    }

    @Suppress("ReturnCount")
    public fun write(records: Collection<ItemStateJournalRecord>): CheckpointWriteResult {
        var checkpointTemporary: Path? = null
        var manifestTemporary: Path? = null
        return try {
            if (records.size > maximumRecords) return CheckpointWriteResult.Failed("CheckpointRecordLimitExceeded")
            Files.createDirectories(directory)
            val generation = nextGeneration()
            val fileName = checkpointFileName(generation)
            val checkpoint = directory.resolve(fileName)
            checkpointTemporary = Files.createTempFile(directory, ".checkpoint-", ".tmp")
            if (!writeCheckpoint(checkpointTemporary, records)) {
                return CheckpointWriteResult.Failed("CheckpointSizeLimitExceeded")
            }
            mover.move(checkpointTemporary, checkpoint)
            checkpointTemporary = null
            if (!checkpointMatches(checkpoint, records)) {
                return CheckpointWriteResult.Failed("CheckpointVerificationFailed")
            }

            manifestTemporary = Files.createTempFile(requireNotNull(manifest.parent), ".manifest-", ".tmp")
            FileChannel.open(manifestTemporary, StandardOpenOption.WRITE).use { channel ->
                val bytes = "$fileName\n".toByteArray(Charsets.US_ASCII)
                channel.write(java.nio.ByteBuffer.wrap(bytes))
                channel.force(false)
            }
            mover.move(manifestTemporary, manifest)
            manifestTemporary = null
            CheckpointWriteResult.Written(generation, checkpoint, pruneOlderGenerations(generation))
        } catch (error: IOException) {
            CheckpointWriteResult.Failed(error.javaClass.simpleName)
        } catch (error: SecurityException) {
            CheckpointWriteResult.Failed(error.javaClass.simpleName)
        } finally {
            deleteTemporary(checkpointTemporary)
            deleteTemporary(manifestTemporary)
        }
    }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    public fun load(): CheckpointLoadResult {
        if (!Files.isRegularFile(manifest)) return CheckpointLoadResult.Missing
        return try {
            val bytes = Files.readAllBytes(manifest)
            if (bytes.isEmpty() || bytes.size > MAX_MANIFEST_BYTES) {
                return CheckpointLoadResult.Invalid("checkpoint manifest size is invalid")
            }
            val fileName = bytes.toString(Charsets.US_ASCII).trimEnd('\r', '\n')
            val match =
                CHECKPOINT_FILE.matchEntire(fileName)
                    ?: return CheckpointLoadResult.Invalid("checkpoint manifest entry is invalid")
            val checkpoint = directory.resolve(fileName).normalize()
            if (checkpoint.parent != directory || !Files.isRegularFile(checkpoint)) {
                return CheckpointLoadResult.Invalid("checkpoint generation is missing")
            }
            if (Files.size(checkpoint) > maximumBytes) {
                return CheckpointLoadResult.Invalid("checkpoint size exceeds supported limit")
            }
            when (val decoded = Files.newInputStream(checkpoint).use { codec.read(it, maximumRecords) }) {
                is JournalDecodeResult.Invalid -> CheckpointLoadResult.Invalid(decoded.reason)
                is JournalDecodeResult.Loaded -> {
                    if (decoded.truncatedTail) {
                        CheckpointLoadResult.Invalid("checkpoint has a truncated tail")
                    } else {
                        CheckpointLoadResult.Loaded(match.groupValues[1].toLong(), decoded.records)
                    }
                }
            }
        } catch (error: IOException) {
            CheckpointLoadResult.Failed(error.javaClass.simpleName)
        } catch (error: SecurityException) {
            CheckpointLoadResult.Failed(error.javaClass.simpleName)
        } catch (error: NumberFormatException) {
            CheckpointLoadResult.Invalid("checkpoint generation is invalid")
        }
    }

    private fun nextGeneration(): Long {
        if (!Files.isDirectory(directory)) return 1
        val maximum =
            Files.list(directory).use { paths ->
                paths
                    .map {
                        CHECKPOINT_FILE
                            .matchEntire(it.fileName.toString())
                            ?.groupValues
                            ?.get(1)
                            ?.toLongOrNull()
                    }.filter { it != null }
                    .mapToLong { requireNotNull(it) }
                    .max()
                    .orElse(0)
            }
        require(maximum < Long.MAX_VALUE) { "checkpoint generation is exhausted" }
        return maximum + 1
    }

    private fun checkpointFileName(generation: Long): String =
        "checkpoint-${generation.toString().padStart(CHECKPOINT_GENERATION_DIGITS, '0')}.bin"

    private fun checkpointMatches(
        checkpoint: Path,
        expected: Collection<ItemStateJournalRecord>,
    ): Boolean =
        when (val decoded = Files.newInputStream(checkpoint).use { codec.read(it, maximumRecords) }) {
            is JournalDecodeResult.Invalid -> false
            is JournalDecodeResult.Loaded -> !decoded.truncatedTail && decoded.records == expected.toList()
        }

    private fun writeCheckpoint(
        target: Path,
        records: Collection<ItemStateJournalRecord>,
    ): Boolean {
        var bytesWritten = 0L
        FileChannel.open(target, StandardOpenOption.WRITE).use { channel ->
            records.forEach { record ->
                val encoded = ByteArrayOutputStream().also { codec.write(it, listOf(record)) }.toByteArray()
                if (encoded.size.toLong() > maximumBytes - bytesWritten) return false
                Channels.newOutputStream(channel).write(encoded)
                bytesWritten += encoded.size
            }
            channel.force(false)
        }
        return true
    }

    private fun pruneOlderGenerations(currentGeneration: Long): Int {
        var failures = 0
        val oldestRetained = (currentGeneration - 1).coerceAtLeast(1)
        try {
            Files.list(directory).use { paths ->
                paths.forEach { failures += deleteIfOlder(it, oldestRetained) }
            }
        } catch (_: IOException) {
            failures++
        } catch (_: SecurityException) {
            failures++
        }
        return failures
    }

    private fun deleteIfOlder(
        path: Path,
        oldestRetained: Long,
    ): Int {
        val generation =
            CHECKPOINT_FILE
                .matchEntire(path.fileName.toString())
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        if (generation == null || generation >= oldestRetained) return 0
        return try {
            deleter.delete(path)
            0
        } catch (_: IOException) {
            1
        } catch (_: SecurityException) {
            1
        }
    }

    private fun deleteTemporary(path: Path?) {
        if (path == null) return
        try {
            Files.deleteIfExists(path)
        } catch (_: IOException) {
            // A bounded temporary file remains for administrator inspection.
        }
    }

    private companion object {
        private const val CHECKPOINT_DIRECTORY = "checkpoints"
        private const val MANIFEST_FILE = "manifest"
        private const val MAX_MANIFEST_BYTES = 128
        private const val CHECKPOINT_GENERATION_DIGITS = 20
        private const val DEFAULT_MAXIMUM_BYTES = 256L * 1024 * 1024
        private const val DEFAULT_MAXIMUM_RECORDS = 1_000_000
        private val CHECKPOINT_FILE = Regex("checkpoint-([0-9]{20})\\.bin")
    }
}

public fun interface JournalAtomicMover {
    @Throws(IOException::class)
    public fun move(
        source: Path,
        target: Path,
    )
}

public fun interface JournalFileDeleter {
    @Throws(IOException::class)
    public fun delete(path: Path): Boolean
}

private object DefaultJournalAtomicMover : JournalAtomicMover {
    override fun move(
        source: Path,
        target: Path,
    ) {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private object DefaultJournalFileDeleter : JournalFileDeleter {
    override fun delete(path: Path): Boolean = Files.deleteIfExists(path)
}

public sealed interface CheckpointWriteResult {
    public data class Written(
        public val generation: Long,
        public val path: Path,
        public val cleanupFailures: Int,
    ) : CheckpointWriteResult

    public data class Failed(
        public val errorType: String,
    ) : CheckpointWriteResult
}

public sealed interface CheckpointLoadResult {
    public data object Missing : CheckpointLoadResult

    public data class Loaded(
        public val generation: Long,
        public val records: List<ItemStateJournalRecord>,
    ) : CheckpointLoadResult

    public data class Invalid(
        public val reason: String,
    ) : CheckpointLoadResult

    public data class Failed(
        public val errorType: String,
    ) : CheckpointLoadResult
}
