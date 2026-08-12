package com.github.command1264.itemdropv2.platform.bukkit.compatibility

import com.github.command1264.itemdropv2.core.CompatibilityBatchState
import com.github.command1264.itemdropv2.core.CompatibilitySinkKind
import com.github.command1264.itemdropv2.core.CompatibilityTransactionWriteResult
import java.nio.file.Paths
import java.sql.DriverManager

/** Child-process fixture: halts without cleanup after a selected durable transaction boundary. */
internal object CompatibilityTransactionCrashProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == EXPECTED_ARGUMENT_COUNT)
        val database = Paths.get(args[0]).toAbsolutePath().normalize()
        val sinkKind = CompatibilitySinkKind.valueOf(args[1])
        val window = CompatibilityCrashWindow.valueOf(args[2])
        val store =
            when (val opened = SqliteCompatibilityTransactionStore.open(database)) {
                is SqliteCompatibilityTransactionOpenResult.Opened -> opened.store
                is SqliteCompatibilityTransactionOpenResult.Failed -> error("open failed: ${opened.reason}")
            }
        check(store.create(sampleBatch(sinkKind = sinkKind)) == CompatibilityTransactionWriteResult.Created)
        when (window) {
            CompatibilityCrashWindow.AFTER_PREPARED -> halt()
            CompatibilityCrashWindow.AFTER_SOURCE_APPLIED -> {
                advanceToSourceApplied(store)
                halt()
            }
            CompatibilityCrashWindow.AFTER_COMMITTED -> {
                advanceToSourceApplied(store)
                check(
                    store.transition(
                        BATCH_ID,
                        CompatibilityBatchState.SOURCE_APPLIED,
                        CompatibilityBatchState.COMMITTED,
                        COMMITTED_AT,
                    ) == CompatibilityTransactionWriteResult.Updated(CompatibilityBatchState.COMMITTED),
                )
                halt()
            }
            CompatibilityCrashWindow.DURING_UNCOMMITTED_SQL -> {
                advanceToSourceApplied(store)
                store.close()
                haltDuringUncommittedUpdate(database.toString())
            }
        }
    }

    private fun advanceToSourceApplied(store: SqliteCompatibilityTransactionStore) {
        check(
            store.transition(
                BATCH_ID,
                CompatibilityBatchState.PREPARED,
                CompatibilityBatchState.SOURCE_APPLIED,
                SOURCE_APPLIED_AT,
            ) == CompatibilityTransactionWriteResult.Updated(CompatibilityBatchState.SOURCE_APPLIED),
        )
    }

    private fun haltDuringUncommittedUpdate(database: String) {
        val connection = DriverManager.getConnection("jdbc:sqlite:$database")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA synchronous = FULL")
        }
        connection.autoCommit = false
        connection
            .prepareStatement(
                "UPDATE compatibility_batch SET state=?,changed_at_epoch_second=? WHERE batch_uuid=? AND state=?",
            ).use { statement ->
                statement.setString(1, CompatibilityBatchState.COMMITTED.name)
                statement.setLong(2, COMMITTED_AT)
                statement.setString(3, BATCH_ID.toString())
                statement.setString(4, CompatibilityBatchState.SOURCE_APPLIED.name)
                check(statement.executeUpdate() == 1)
            }
        halt()
    }

    private fun halt(): Nothing {
        Runtime.getRuntime().halt(0)
        error("Runtime.halt returned")
    }

    private const val EXPECTED_ARGUMENT_COUNT = 3
    private const val SOURCE_APPLIED_AT = 2L
    private const val COMMITTED_AT = 3L
}
