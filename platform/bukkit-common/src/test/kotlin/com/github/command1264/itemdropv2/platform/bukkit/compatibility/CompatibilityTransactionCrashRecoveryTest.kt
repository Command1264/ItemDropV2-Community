package com.github.command1264.itemdropv2.platform.bukkit.compatibility

import com.github.command1264.itemdropv2.core.CompatibilityBatch
import com.github.command1264.itemdropv2.core.CompatibilityBatchState
import com.github.command1264.itemdropv2.core.CompatibilitySinkKind
import com.github.command1264.itemdropv2.core.CompatibilityTransactionReadResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

class CompatibilityTransactionCrashRecoveryTest {
    @TempDir
    lateinit var directory: Path

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("sinkKindsAndCrashWindows")
    fun `abrupt exit preserves the last committed ledger boundary`(
        kind: CompatibilitySinkKind,
        window: CompatibilityCrashWindow,
    ) {
        val database = directory.resolve("${kind.name}-${window.name}.sqlite")

        runCrashProcess(database, kind, window)

        assertOpened(SqliteCompatibilityTransactionStore.open(database)).use { store ->
            assertEquals(window.expectedDurableState, assertFound(store.find(BATCH_ID)).state)
        }
    }

    private fun runCrashProcess(
        database: Path,
        kind: CompatibilitySinkKind,
        window: CompatibilityCrashWindow,
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
                CompatibilityTransactionCrashProcess::class.java.name,
                database.toString(),
                kind.name,
                window.name,
            ).redirectErrorStream(true)
                .start()
        val exited = process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            process.waitFor(FORCED_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertTrue(exited, "crash fixture process timed out: $output")
        assertEquals(0, process.exitValue(), output)
    }

    private fun childClasspath(): String =
        listOf(
            CompatibilityTransactionCrashProcess::class.java,
            SqliteCompatibilityTransactionStore::class.java,
            CompatibilityBatch::class.java,
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

    private fun assertOpened(result: SqliteCompatibilityTransactionOpenResult): SqliteCompatibilityTransactionStore =
        assertInstanceOf(SqliteCompatibilityTransactionOpenResult.Opened::class.java, result).store

    private fun assertFound(result: CompatibilityTransactionReadResult): CompatibilityBatch =
        assertInstanceOf(CompatibilityTransactionReadResult.Found::class.java, result).batch

    companion object {
        private const val CHILD_TIMEOUT_SECONDS = 20L
        private const val FORCED_EXIT_TIMEOUT_SECONDS = 5L

        @JvmStatic
        fun sinkKindsAndCrashWindows(): Stream<Arguments> =
            CompatibilitySinkKind.entries
                .flatMap { kind -> CompatibilityCrashWindow.entries.map { window -> Arguments.of(kind, window) } }
                .stream()
    }
}

enum class CompatibilityCrashWindow(
    val expectedDurableState: CompatibilityBatchState,
) {
    AFTER_PREPARED(CompatibilityBatchState.PREPARED),
    AFTER_SOURCE_APPLIED(CompatibilityBatchState.SOURCE_APPLIED),
    AFTER_COMMITTED(CompatibilityBatchState.COMMITTED),
    DURING_UNCOMMITTED_SQL(CompatibilityBatchState.SOURCE_APPLIED),
}
