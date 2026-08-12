package com.github.command1264.itemdropv2.platform.bukkit.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

class ItemStateJournalStorageTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `index keeps highest revision and rejects equal revision conflicts`() {
        val index = ItemStateJournalIndex()
        val first = sampleJournalUpsert(revision = 1)

        assertInstanceOf(JournalIndexUpdate.Applied::class.java, index.apply(first))
        assertInstanceOf(JournalIndexUpdate.Duplicate::class.java, index.apply(first))
        assertInstanceOf(JournalIndexUpdate.Replaced::class.java, index.apply(sampleJournalUpsert(revision = 2)))
        assertInstanceOf(JournalIndexUpdate.IgnoredOlder::class.java, index.apply(sampleJournalUpsert(revision = 1)))
        assertInstanceOf(
            JournalIndexUpdate.Conflict::class.java,
            index.apply(sampleJournalUpsert(revision = 2).asTombstone(revision = 2)),
        )
        assertInstanceOf(
            JournalIndexUpdate.Replaced::class.java,
            index.apply(sampleJournalUpsert(revision = 2).asTombstone(revision = 3)),
        )
        assertEquals(3, index[first.identity]?.revision)
        assertEquals(3, index.snapshot().single().revision)
    }

    @Test
    fun `wal preserves and repairs a torn tail`() {
        val wal = ItemStateJournalWal(directory)
        val first = sampleJournalUpsert(revision = 1)
        val second = sampleJournalUpsert(revision = 2)
        assertInstanceOf(JournalWriteResult.Written::class.java, wal.append(first, force = true))
        assertInstanceOf(JournalWriteResult.Written::class.java, wal.append(second, force = true))
        val originalSize = Files.size(wal.path)
        Files.newByteChannel(wal.path, java.nio.file.StandardOpenOption.WRITE).use { channel ->
            channel.truncate(originalSize - 5)
        }

        val repaired = assertInstanceOf(JournalLoadResult.Loaded::class.java, wal.load(repairTornTail = true))

        assertEquals(listOf(first), repaired.records)
        assertTrue(repaired.repairedTornTail)
        assertTrue(Files.isRegularFile(directory.resolve("active.wal.torn")))
        assertTrue(Files.size(wal.path) < originalSize)
    }

    @Test
    fun `checkpoint publishes a new generation without overwriting the previous one`() {
        val store = ItemStateJournalCheckpointStore(directory)
        val first = sampleJournalUpsert(revision = 1)
        val second = sampleJournalUpsert(revision = 2)
        val third = sampleJournalUpsert(revision = 3)

        assertInstanceOf(CheckpointWriteResult.Written::class.java, store.write(listOf(first)))
        assertInstanceOf(CheckpointWriteResult.Written::class.java, store.write(listOf(second)))
        val written = assertInstanceOf(CheckpointWriteResult.Written::class.java, store.write(listOf(third)))
        val loaded = assertInstanceOf(CheckpointLoadResult.Loaded::class.java, store.load())

        assertEquals(0, written.cleanupFailures)
        assertEquals(listOf(third), loaded.records)
        assertEquals(2, Files.list(directory.resolve("checkpoints")).use { it.count() })
    }

    @Test
    fun `failed manifest replacement leaves the previous checkpoint readable`() {
        val store = ItemStateJournalCheckpointStore(directory)
        val first = sampleJournalUpsert(revision = 1)
        assertInstanceOf(CheckpointWriteResult.Written::class.java, store.write(listOf(first)))
        var moves = 0
        val failingStore =
            ItemStateJournalCheckpointStore(
                directory,
                mover =
                    JournalAtomicMover { source, target ->
                        moves++
                        if (moves == 2) throw IOException("injected manifest failure")
                        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                    },
            )

        assertInstanceOf(CheckpointWriteResult.Failed::class.java, failingStore.write(listOf(sampleJournalUpsert(2))))
        val loaded = assertInstanceOf(CheckpointLoadResult.Loaded::class.java, store.load())

        assertEquals(1, loaded.generation)
        assertEquals(listOf(first), loaded.records)
    }

    @Test
    fun `wal rejects writes beyond its configured bound`() {
        val wal = ItemStateJournalWal(directory, maximumBytes = 1)

        val failed = assertInstanceOf(JournalWriteResult.Failed::class.java, wal.append(sampleJournalUpsert()))

        assertEquals("WalSizeLimitExceeded", failed.errorType)
        assertEquals(false, Files.exists(wal.path))
    }

    @Test
    fun `wal rejects oversized existing files and excessive records`() {
        val oversizedDirectory = directory.resolve("oversized")
        Files.createDirectories(oversizedDirectory)
        Files.write(oversizedDirectory.resolve("active.wal"), byteArrayOf(1, 2))
        val oversized = ItemStateJournalWal(oversizedDirectory, maximumBytes = 1)

        assertInstanceOf(JournalLoadResult.Invalid::class.java, oversized.load())

        val recordDirectory = directory.resolve("records")
        val recordLimited = ItemStateJournalWal(recordDirectory, maximumRecords = 1)
        recordLimited.append(sampleJournalUpsert(1))
        recordLimited.append(sampleJournalUpsert(2))
        assertInstanceOf(JournalLoadResult.Invalid::class.java, recordLimited.load())
    }

    @Test
    fun `checkpoint verifies a generation before publishing its manifest`() {
        val corruptingStore =
            ItemStateJournalCheckpointStore(
                directory,
                mover =
                    JournalAtomicMover { source, target ->
                        Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
                        if (target.fileName.toString().startsWith("checkpoint-")) {
                            Files.write(target, byteArrayOf(1), java.nio.file.StandardOpenOption.APPEND)
                        }
                    },
            )

        val failed =
            assertInstanceOf(CheckpointWriteResult.Failed::class.java, corruptingStore.write(listOf(sampleJournalUpsert())))

        assertEquals("CheckpointVerificationFailed", failed.errorType)
        assertInstanceOf(CheckpointLoadResult.Missing::class.java, corruptingStore.load())
    }

    @Test
    fun `checkpoint rejects record and byte limits before publishing`() {
        val recordLimited = ItemStateJournalCheckpointStore(directory.resolve("records"), maximumRecords = 1)
        val byteLimited = ItemStateJournalCheckpointStore(directory.resolve("bytes"), maximumBytes = 1)

        val tooMany = recordLimited.write(listOf(sampleJournalUpsert(1), sampleJournalUpsert(2)))
        val tooLarge = byteLimited.write(listOf(sampleJournalUpsert()))

        assertEquals("CheckpointRecordLimitExceeded", assertInstanceOf(CheckpointWriteResult.Failed::class.java, tooMany).errorType)
        assertEquals("CheckpointSizeLimitExceeded", assertInstanceOf(CheckpointWriteResult.Failed::class.java, tooLarge).errorType)
        assertInstanceOf(CheckpointLoadResult.Missing::class.java, recordLimited.load())
        assertInstanceOf(CheckpointLoadResult.Missing::class.java, byteLimited.load())
    }

    @Test
    fun `checkpoint reports cleanup failure after safely publishing`() {
        val store =
            ItemStateJournalCheckpointStore(
                directory,
                deleter = JournalFileDeleter { throw IOException("injected cleanup failure") },
            )
        store.write(listOf(sampleJournalUpsert(1)))
        store.write(listOf(sampleJournalUpsert(2)))

        val written =
            assertInstanceOf(CheckpointWriteResult.Written::class.java, store.write(listOf(sampleJournalUpsert(3))))
        val loaded = assertInstanceOf(CheckpointLoadResult.Loaded::class.java, store.load())

        assertEquals(1, written.cleanupFailures)
        assertEquals(3, loaded.generation)
        assertEquals(3, Files.list(directory.resolve("checkpoints")).use { it.count() })
    }
}
