package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class SqliteJournalCrashRecoveryTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `recovers committed wal and rolls back an uncommitted transaction after abrupt process exit`() {
        val record = sampleJournalUpsert(1)
        val database = directory.resolve("item-state.db")
        assertOpened(SqliteItemStateJournalStore.open(database, record.identity.worldUuid)).use { store ->
            assertEquals(JournalWriteResult.Written, store.append(record))
        }

        runCrashProcess(database, "commit")
        assertOpened(SqliteItemStateJournalStore.open(database, record.identity.worldUuid)).use { store ->
            assertEquals(2, store.readAllRecordsForTest().single().revision)
        }

        runCrashProcess(database, "uncommitted")
        assertOpened(SqliteItemStateJournalStore.open(database, record.identity.worldUuid)).use { store ->
            assertEquals(2, store.readAllRecordsForTest().single().revision)
        }
    }

    private fun runCrashProcess(
        database: Path,
        mode: String,
    ) {
        val javaExecutable =
            Paths.get(
                System.getProperty("java.home"),
                "bin",
                if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
            )
        val process =
            ProcessBuilder(
                javaExecutable.toString(),
                "-cp",
                childClasspath(),
                SqliteJournalCrashProcess::class.java.name,
                database.toString(),
                mode,
            ).redirectErrorStream(true)
                .start()
        val exited = process.waitFor(20, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
        assertTrue(exited, "crash fixture process timed out")
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.exitValue(), output)
    }

    private fun childClasspath(): String =
        listOf(
            SqliteJournalCrashProcess::class.java,
            SqliteItemStateJournalStore::class.java,
            ItemState::class.java,
            kotlin.Unit::class.java,
            Class.forName("org.sqlite.JDBC"),
        ).map { type ->
            requireNotNull(type.protectionDomain.codeSource)
                .location
                .toURI()
                .let(Paths::get)
                .toString()
        }.distinct()
            .joinToString(File.pathSeparator)

    private fun assertOpened(result: SqliteJournalOpenResult): SqliteItemStateJournalStore =
        assertInstanceOf(SqliteJournalOpenResult.Opened::class.java, result).store
}
