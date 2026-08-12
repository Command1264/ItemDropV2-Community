package com.github.command1264.itemdropv2.platform.bukkit.compatibility

import com.github.command1264.itemdropv2.core.CompatibilityBatch
import com.github.command1264.itemdropv2.core.CompatibilityBatchState
import com.github.command1264.itemdropv2.core.CompatibilityOutput
import com.github.command1264.itemdropv2.core.CompatibilitySinkKind
import com.github.command1264.itemdropv2.core.CompatibilityTransactionReadManyResult
import com.github.command1264.itemdropv2.core.CompatibilityTransactionReadResult
import com.github.command1264.itemdropv2.core.CompatibilityTransactionRepository
import com.github.command1264.itemdropv2.core.CompatibilityTransactionWriteResult
import com.github.command1264.itemdropv2.platform.bukkit.journal.SqliteJournalFailureClassifier
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

public sealed interface SqliteCompatibilityTransactionOpenResult {
    public data class Opened(
        public val store: SqliteCompatibilityTransactionStore,
    ) : SqliteCompatibilityTransactionOpenResult

    public data class Failed(
        public val reason: String,
    ) : SqliteCompatibilityTransactionOpenResult
}

/** Durable prototype ledger for Community/Pro compatibility transactions. */
public class SqliteCompatibilityTransactionStore internal constructor(
    private val connection: Connection,
    private val unresolvedCapacity: Int = DEFAULT_UNRESOLVED_CAPACITY,
) : CompatibilityTransactionRepository {
    init {
        require(unresolvedCapacity > 0) { "unresolved compatibility transaction capacity must be positive" }
    }

    @Suppress("MagicNumber")
    override fun create(batch: CompatibilityBatch): CompatibilityTransactionWriteResult =
        writeTransaction {
            when (val existing = findInternal(batch.batchId)) {
                is CompatibilityTransactionReadResult.Found ->
                    return@writeTransaction CompatibilityTransactionWriteResult.Conflict(existing.batch.state)
                CompatibilityTransactionReadResult.Missing -> Unit
                is CompatibilityTransactionReadResult.Rejected ->
                    return@writeTransaction CompatibilityTransactionWriteResult.Rejected(existing.reason)
                is CompatibilityTransactionReadResult.Failed ->
                    return@writeTransaction CompatibilityTransactionWriteResult.Failed(existing.errorType)
            }
            if (unresolvedCount() >= unresolvedCapacity) {
                return@writeTransaction CompatibilityTransactionWriteResult.CapacityReached
            }
            connection.prepareStatement(INSERT_BATCH).use { statement ->
                statement.setString(1, batch.batchId.toString())
                statement.setString(2, batch.sourceEntityUuid.toString())
                statement.setLong(3, batch.sourceRevision)
                statement.setLong(4, batch.sourceBeforeAmount)
                statement.setLong(5, batch.sourceAfterAmount)
                statement.setLong(6, batch.transferAmount)
                statement.setString(7, batch.sinkKind.name)
                statement.setString(8, batch.sinkIdentity)
                statement.setString(9, batch.state.name)
                statement.setString(10, batch.quarantineReason)
                statement.setLong(11, batch.createdAtEpochSecond)
                statement.setLong(12, batch.changedAtEpochSecond)
                statement.executeUpdate()
            }
            connection.prepareStatement(INSERT_OUTPUT).use { statement ->
                batch.outputs.forEach { output ->
                    statement.setString(1, batch.batchId.toString())
                    statement.setInt(2, output.markerIndex)
                    statement.setLong(3, output.amount)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            CompatibilityTransactionWriteResult.Created
        }

    override fun find(batchId: UUID): CompatibilityTransactionReadResult {
        if (batchId == ZERO_UUID) return CompatibilityTransactionReadResult.Rejected("InvalidBatchId")
        return try {
            findInternal(batchId)
        } catch (_: IllegalArgumentException) {
            CompatibilityTransactionReadResult.Rejected("InvalidRecord")
        } catch (error: SQLException) {
            CompatibilityTransactionReadResult.Failed(SqliteJournalFailureClassifier.classify(error))
        }
    }

    override fun unresolved(limit: Int): CompatibilityTransactionReadManyResult {
        if (limit !in 1..MAX_QUERY_LIMIT) {
            return CompatibilityTransactionReadManyResult.Rejected("LimitOutOfRange")
        }
        return try {
            val batches = mutableListOf<CompatibilityBatch>()
            connection.prepareStatement(SELECT_UNRESOLVED).use { statement ->
                statement.setInt(1, limit)
                statement.executeQuery().use { rows ->
                    while (rows.next()) batches += readBatch(rows)
                }
            }
            CompatibilityTransactionReadManyResult.Loaded(batches)
        } catch (_: IllegalArgumentException) {
            CompatibilityTransactionReadManyResult.Rejected("InvalidRecord")
        } catch (error: SQLException) {
            CompatibilityTransactionReadManyResult.Failed(SqliteJournalFailureClassifier.classify(error))
        }
    }

    @Suppress("MagicNumber")
    override fun transition(
        batchId: UUID,
        expected: CompatibilityBatchState,
        next: CompatibilityBatchState,
        changedAtEpochSecond: Long,
        quarantineReason: String?,
    ): CompatibilityTransactionWriteResult {
        if (batchId == ZERO_UUID) return CompatibilityTransactionWriteResult.Rejected("InvalidBatchId")
        return writeTransaction {
            val current =
                when (val result = findInternal(batchId)) {
                    is CompatibilityTransactionReadResult.Found -> result.batch
                    CompatibilityTransactionReadResult.Missing -> return@writeTransaction CompatibilityTransactionWriteResult.Missing
                    is CompatibilityTransactionReadResult.Rejected ->
                        return@writeTransaction CompatibilityTransactionWriteResult.Rejected(result.reason)
                    is CompatibilityTransactionReadResult.Failed ->
                        return@writeTransaction CompatibilityTransactionWriteResult.Failed(result.errorType)
                }
            if (current.state != expected) {
                return@writeTransaction CompatibilityTransactionWriteResult.Conflict(current.state)
            }
            val transitioned =
                try {
                    current.transitionTo(next, changedAtEpochSecond, quarantineReason)
                } catch (_: IllegalArgumentException) {
                    return@writeTransaction CompatibilityTransactionWriteResult.Rejected("InvalidTransition")
                }
            connection.prepareStatement(UPDATE_STATE).use { statement ->
                statement.setString(1, transitioned.state.name)
                statement.setString(2, transitioned.quarantineReason)
                statement.setLong(3, transitioned.changedAtEpochSecond)
                statement.setString(4, batchId.toString())
                statement.setString(5, expected.name)
                if (statement.executeUpdate() == 0) {
                    return@writeTransaction when (val latest = findInternal(batchId)) {
                        is CompatibilityTransactionReadResult.Found ->
                            CompatibilityTransactionWriteResult.Conflict(latest.batch.state)
                        CompatibilityTransactionReadResult.Missing -> CompatibilityTransactionWriteResult.Missing
                        is CompatibilityTransactionReadResult.Rejected ->
                            CompatibilityTransactionWriteResult.Rejected(latest.reason)
                        is CompatibilityTransactionReadResult.Failed ->
                            CompatibilityTransactionWriteResult.Failed(latest.errorType)
                    }
                }
            }
            CompatibilityTransactionWriteResult.Updated(transitioned.state)
        }
    }

    override fun close() {
        connection.close()
    }

    private fun findInternal(batchId: UUID): CompatibilityTransactionReadResult =
        connection.prepareStatement("$SELECT_BATCH WHERE batch_uuid = ?").use { statement ->
            statement.setString(1, batchId.toString())
            statement.executeQuery().use { rows ->
                if (!rows.next()) {
                    CompatibilityTransactionReadResult.Missing
                } else {
                    CompatibilityTransactionReadResult.Found(readBatch(rows))
                }
            }
        }

    private fun readBatch(rows: ResultSet): CompatibilityBatch {
        val batchId = UUID.fromString(rows.getString("batch_uuid"))
        val outputs = mutableListOf<CompatibilityOutput>()
        connection.prepareStatement(SELECT_OUTPUTS).use { statement ->
            statement.setString(1, batchId.toString())
            statement.executeQuery().use { outputRows ->
                while (outputRows.next()) {
                    outputs += CompatibilityOutput(outputRows.getInt("marker_index"), outputRows.getLong("amount"))
                }
            }
        }
        return CompatibilityBatch.create(
            batchId = batchId,
            sourceEntityUuid = UUID.fromString(rows.getString("source_uuid")),
            sourceRevision = rows.getLong("source_revision"),
            sourceBeforeAmount = rows.getLong("source_before_amount"),
            sourceAfterAmount = rows.getLong("source_after_amount"),
            transferAmount = rows.getLong("transfer_amount"),
            sinkKind = CompatibilitySinkKind.valueOf(rows.getString("sink_kind")),
            sinkIdentity = rows.getString("sink_identity"),
            outputs = outputs,
            state = CompatibilityBatchState.valueOf(rows.getString("state")),
            quarantineReason = rows.getString("quarantine_reason"),
            createdAtEpochSecond = rows.getLong("created_at_epoch_second"),
            changedAtEpochSecond = rows.getLong("changed_at_epoch_second"),
        )
    }

    private fun unresolvedCount(): Int =
        connection.prepareStatement(COUNT_UNRESOLVED).use { statement ->
            statement.executeQuery().use { rows ->
                check(rows.next()) { "compatibility unresolved count query returned no row" }
                rows.getInt(1)
            }
        }

    private fun writeTransaction(block: () -> CompatibilityTransactionWriteResult): CompatibilityTransactionWriteResult {
        val previousAutoCommit = connection.autoCommit
        return try {
            connection.autoCommit = false
            val result = block()
            if (result is CompatibilityTransactionWriteResult.Failed) connection.rollback() else connection.commit()
            result
        } catch (_: IllegalArgumentException) {
            rollbackQuietly()
            CompatibilityTransactionWriteResult.Rejected("InvalidRecord")
        } catch (error: SQLException) {
            rollbackQuietly()
            CompatibilityTransactionWriteResult.Failed(SqliteJournalFailureClassifier.classify(error))
        } finally {
            try {
                connection.autoCommit = previousAutoCommit
            } catch (_: SQLException) {
                // The bounded operation result already captures the primary database failure.
            }
        }
    }

    private fun rollbackQuietly() {
        try {
            connection.rollback()
        } catch (_: SQLException) {
            // Preserve the primary failure classification.
        }
    }

    public companion object {
        public const val DATABASE_FILE: String = "compatibility-transactions.sqlite"
        private const val SCHEMA_VERSION = 1
        private const val DEFAULT_UNRESOLVED_CAPACITY = 10_000
        private const val MAX_QUERY_LIMIT = 100
        private val ZERO_UUID = UUID(0, 0)

        public fun open(database: Path): SqliteCompatibilityTransactionOpenResult {
            var connection: Connection? = null
            return try {
                val initialize = Files.notExists(database)
                database.toAbsolutePath().parent?.let(Files::createDirectories)
                connection = DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}")
                configure(connection)
                if (initialize) createSchema(connection) else validateSchema(connection)
                SqliteCompatibilityTransactionOpenResult.Opened(SqliteCompatibilityTransactionStore(connection))
            } catch (error: SQLException) {
                closeQuietly(connection)
                SqliteCompatibilityTransactionOpenResult.Failed(SqliteJournalFailureClassifier.classify(error))
            } catch (_: java.io.IOException) {
                closeQuietly(connection)
                SqliteCompatibilityTransactionOpenResult.Failed("DatabaseIoFailure")
            } catch (_: SecurityException) {
                closeQuietly(connection)
                SqliteCompatibilityTransactionOpenResult.Failed("PermissionDenied")
            } catch (_: LinkageError) {
                closeQuietly(connection)
                SqliteCompatibilityTransactionOpenResult.Failed("SqliteDriverUnavailable")
            } catch (error: SchemaValidationException) {
                closeQuietly(connection)
                SqliteCompatibilityTransactionOpenResult.Failed(error.reason)
            }
        }

        private fun configure(connection: Connection) {
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA journal_mode = WAL")
                statement.execute("PRAGMA synchronous = FULL")
                statement.execute("PRAGMA busy_timeout = 5000")
            }
        }

        private fun createSchema(connection: Connection) {
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.execute(CREATE_SCHEMA_TABLE)
                    statement.execute("INSERT INTO compatibility_schema VALUES (1, $SCHEMA_VERSION)")
                    statement.execute(CREATE_BATCH_TABLE)
                    statement.execute(CREATE_OUTPUT_TABLE)
                }
                connection.commit()
            } catch (error: SQLException) {
                connection.rollback()
                throw error
            } finally {
                connection.autoCommit = true
            }
        }

        @Suppress("ThrowsCount")
        private fun validateSchema(connection: Connection) {
            if (!tableExists(connection, "compatibility_schema")) throw SchemaValidationException("SchemaMissing")
            val version =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT schema_version FROM compatibility_schema WHERE singleton = 1").use { rows ->
                        if (!rows.next()) throw SchemaValidationException("SchemaMissing")
                        rows.getInt(1)
                    }
                }
            if (version != SCHEMA_VERSION) throw SchemaValidationException("UnsupportedSchemaVersion:$version")
            if (REQUIRED_DATA_TABLES.any { table -> !tableExists(connection, table) }) {
                throw SchemaValidationException("SchemaMissing")
            }
        }

        private fun tableExists(
            connection: Connection,
            table: String,
        ): Boolean =
            connection
                .prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")
                .use { statement ->
                    statement.setString(1, table)
                    statement.executeQuery().use(ResultSet::next)
                }

        private fun closeQuietly(connection: Connection?) {
            try {
                connection?.close()
            } catch (_: SQLException) {
                // Opening already failed; do not replace the bounded primary reason.
            }
        }

        private val REQUIRED_DATA_TABLES =
            listOf(
                "compatibility_batch",
                "compatibility_output",
            )

        private const val CREATE_SCHEMA_TABLE =
            "CREATE TABLE compatibility_schema(" +
                "singleton INTEGER PRIMARY KEY CHECK(singleton = 1)," +
                "schema_version INTEGER NOT NULL CHECK(schema_version > 0))"
        private const val CREATE_BATCH_TABLE =
            "CREATE TABLE compatibility_batch(" +
                "batch_uuid TEXT PRIMARY KEY," +
                "source_uuid TEXT NOT NULL," +
                "source_revision INTEGER NOT NULL CHECK(source_revision > 0)," +
                "source_before_amount INTEGER NOT NULL CHECK(source_before_amount > 0)," +
                "source_after_amount INTEGER NOT NULL CHECK(source_after_amount >= 0)," +
                "transfer_amount INTEGER NOT NULL CHECK(transfer_amount > 0)," +
                "sink_kind TEXT NOT NULL," +
                "sink_identity TEXT NOT NULL," +
                "state TEXT NOT NULL," +
                "quarantine_reason TEXT," +
                "created_at_epoch_second INTEGER NOT NULL CHECK(created_at_epoch_second > 0)," +
                "changed_at_epoch_second INTEGER NOT NULL CHECK(changed_at_epoch_second > 0))"
        private const val CREATE_OUTPUT_TABLE =
            "CREATE TABLE compatibility_output(" +
                "batch_uuid TEXT NOT NULL," +
                "marker_index INTEGER NOT NULL CHECK(marker_index >= 0)," +
                "amount INTEGER NOT NULL CHECK(amount > 0)," +
                "PRIMARY KEY(batch_uuid, marker_index)," +
                "FOREIGN KEY(batch_uuid) REFERENCES compatibility_batch(batch_uuid) ON DELETE CASCADE)"
        private const val INSERT_BATCH =
            "INSERT INTO compatibility_batch(" +
                "batch_uuid,source_uuid,source_revision,source_before_amount,source_after_amount,transfer_amount," +
                "sink_kind,sink_identity,state,quarantine_reason,created_at_epoch_second,changed_at_epoch_second" +
                ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?)"
        private const val INSERT_OUTPUT =
            "INSERT INTO compatibility_output(batch_uuid,marker_index,amount) VALUES (?,?,?)"
        private const val SELECT_BATCH =
            "SELECT batch_uuid,source_uuid,source_revision,source_before_amount,source_after_amount,transfer_amount," +
                "sink_kind,sink_identity,state,quarantine_reason,created_at_epoch_second,changed_at_epoch_second " +
                "FROM compatibility_batch"
        private const val SELECT_OUTPUTS =
            "SELECT marker_index,amount FROM compatibility_output WHERE batch_uuid = ? ORDER BY marker_index"
        private const val SELECT_UNRESOLVED =
            "$SELECT_BATCH WHERE state NOT IN ('COMMITTED','QUARANTINED') " +
                "ORDER BY changed_at_epoch_second,batch_uuid LIMIT ?"
        private const val COUNT_UNRESOLVED =
            "SELECT COUNT(*) FROM compatibility_batch WHERE state NOT IN ('COMMITTED','QUARANTINED')"
        private const val UPDATE_STATE =
            "UPDATE compatibility_batch SET state=?,quarantine_reason=?,changed_at_epoch_second=? " +
                "WHERE batch_uuid=? AND state=?"
    }
}

private class SchemaValidationException(
    val reason: String,
) : RuntimeException()
