package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestReporter
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.system.measureTimeMillis

class SqliteItemStateJournalPerformanceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `persists and reloads bounded 1k 5k and 10k journal populations`(reporter: TestReporter) {
        listOf(1_000, 5_000, 10_000).forEach { population ->
            val worldDirectory = directory.resolve(population.toString())
            val database = worldDirectory.resolve(SqliteItemStateJournalStore.DATABASE_FILE)
            val template = sampleJournalUpsert(1)
            val elapsed =
                measureTimeMillis {
                    val store = assertOpened(SqliteItemStateJournalStore.open(database, template.identity.worldUuid))
                    val writer = BoundedItemStateJournalWriter(capacity = population, sink = store)
                    repeat(population) { index ->
                        val record = sampleJournalUpsert(1, UUID(0, PERFORMANCE_UUID_OFFSET + index))
                        assertInstanceOf(ItemStateDurabilityOutcome.Accepted::class.java, writer.stage(record))
                    }
                    val shutdown = assertInstanceOf(JournalShutdownResult.Stopped::class.java, writer.shutdown(30_000))
                    assertEquals(JournalFlushResult.Flushed, shutdown.flushResult)
                    store.close()
                }
            val bytes = Files.size(database)
            assertTrue(elapsed < MAXIMUM_ELAPSED_MILLIS, "population=$population elapsed=${elapsed}ms")
            assertTrue(bytes in 1..MAXIMUM_DATABASE_BYTES, "population=$population bytes=$bytes")
            assertOpened(SqliteItemStateJournalStore.open(database, template.identity.worldUuid)).use { store ->
                assertEquals(population, store.load().size)
            }
            reporter.publishEntry("sqlite-journal-$population", "elapsed=${elapsed}ms bytes=$bytes")
        }
    }

    private fun assertOpened(result: SqliteJournalOpenResult): SqliteItemStateJournalStore =
        assertInstanceOf(SqliteJournalOpenResult.Opened::class.java, result).store

    private companion object {
        private const val PERFORMANCE_UUID_OFFSET = 10_000L
        private const val MAXIMUM_ELAPSED_MILLIS = 60_000L
        private const val MAXIMUM_DATABASE_BYTES = 64L * 1024 * 1024
    }
}
