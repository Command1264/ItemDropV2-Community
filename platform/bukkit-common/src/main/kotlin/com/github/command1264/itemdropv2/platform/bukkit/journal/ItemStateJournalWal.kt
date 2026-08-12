package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

public class ItemStateJournalWal(
    directory: Path,
    private val codec: ItemStateJournalCodec = ItemStateJournalCodec(),
    private val maximumBytes: Long = DEFAULT_MAXIMUM_BYTES,
    private val maximumRecords: Int = DEFAULT_MAXIMUM_RECORDS,
) : JournalRecordSink {
    public val path: Path = directory.toAbsolutePath().normalize().resolve(ACTIVE_WAL)
    private val tornCopy: Path = path.resolveSibling(TORN_WAL)

    init {
        require(maximumBytes > 0) { "maximum WAL bytes must be positive" }
        require(maximumRecords > 0) { "maximum WAL records must be positive" }
    }

    override fun append(record: ItemStateJournalRecord): JournalWriteResult = append(record, force = false)

    public fun append(
        record: ItemStateJournalRecord,
        force: Boolean,
    ): JournalWriteResult =
        try {
            Files.createDirectories(requireNotNull(path.parent))
            val encoded = ByteArrayOutputStream().also { codec.write(it, listOf(record)) }.toByteArray()
            val currentSize = if (Files.exists(path)) Files.size(path) else 0
            if (encoded.size.toLong() > maximumBytes - currentSize) {
                return JournalWriteResult.Failed("WalSizeLimitExceeded")
            }
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND).use { channel ->
                Channels.newOutputStream(channel).write(encoded)
                if (force) channel.force(false)
            }
            JournalWriteResult.Written
        } catch (error: IOException) {
            JournalWriteResult.Failed(error.javaClass.simpleName)
        } catch (error: SecurityException) {
            JournalWriteResult.Failed(error.javaClass.simpleName)
        }

    override fun flush(): JournalWriteResult =
        try {
            if (Files.exists(path)) {
                FileChannel.open(path, StandardOpenOption.WRITE).use { it.force(false) }
            }
            JournalWriteResult.Written
        } catch (error: IOException) {
            JournalWriteResult.Failed(error.javaClass.simpleName)
        } catch (error: SecurityException) {
            JournalWriteResult.Failed(error.javaClass.simpleName)
        }

    @Suppress("ReturnCount")
    public fun load(repairTornTail: Boolean = false): JournalLoadResult {
        if (!Files.exists(path)) return JournalLoadResult.Loaded(emptyList(), repairedTornTail = false)
        return try {
            if (Files.size(path) > maximumBytes) {
                return JournalLoadResult.Invalid("WAL size exceeds supported limit", validBytes = 0)
            }
            when (val decoded = Files.newInputStream(path).use { codec.read(it, maximumRecords) }) {
                is JournalDecodeResult.Invalid -> JournalLoadResult.Invalid(decoded.reason, decoded.validBytes)
                is JournalDecodeResult.Loaded -> {
                    if (decoded.truncatedTail && repairTornTail) {
                        Files.copy(path, tornCopy, StandardCopyOption.REPLACE_EXISTING)
                        FileChannel.open(path, StandardOpenOption.WRITE).use { channel ->
                            channel.truncate(decoded.validBytes)
                            channel.force(false)
                        }
                    }
                    JournalLoadResult.Loaded(decoded.records, decoded.truncatedTail && repairTornTail)
                }
            }
        } catch (error: IOException) {
            JournalLoadResult.Failed(error.javaClass.simpleName)
        } catch (error: SecurityException) {
            JournalLoadResult.Failed(error.javaClass.simpleName)
        }
    }

    private companion object {
        private const val ACTIVE_WAL = "active.wal"
        private const val TORN_WAL = "active.wal.torn"
        private const val DEFAULT_MAXIMUM_BYTES = 256L * 1024 * 1024
        private const val DEFAULT_MAXIMUM_RECORDS = 1_000_000
    }
}

public interface JournalRecordSink {
    public fun append(record: ItemStateJournalRecord): JournalWriteResult

    public fun appendBatch(records: List<ItemStateJournalRecord>): JournalWriteResult {
        records.forEach { record ->
            val result = append(record)
            if (result is JournalWriteResult.Failed) return result
        }
        return JournalWriteResult.Written
    }

    public fun flush(): JournalWriteResult
}

public sealed interface JournalWriteResult {
    public data object Written : JournalWriteResult

    public data class Failed(
        public val errorType: String,
    ) : JournalWriteResult
}

public sealed interface JournalLoadResult {
    public data class Loaded(
        public val records: List<ItemStateJournalRecord>,
        public val repairedTornTail: Boolean,
    ) : JournalLoadResult

    public data class Invalid(
        public val reason: String,
        public val validBytes: Long,
    ) : JournalLoadResult

    public data class Failed(
        public val errorType: String,
    ) : JournalLoadResult
}
