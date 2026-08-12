package com.github.command1264.itemdropv2.platform.bukkit.compatibility

import com.github.command1264.itemdropv2.core.CompatibilityBatch
import com.github.command1264.itemdropv2.core.CompatibilityBatchState
import com.github.command1264.itemdropv2.core.CompatibilityOutput
import com.github.command1264.itemdropv2.core.CompatibilitySinkKind
import com.github.command1264.itemdropv2.core.CompatibilityTransactionReadManyResult
import com.github.command1264.itemdropv2.core.CompatibilityTransactionReadResult
import com.github.command1264.itemdropv2.core.CompatibilityTransactionWriteResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

class SqliteCompatibilityTransactionStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `creates versioned schema and round trips batch plus outputs`() {
        val database = directory.resolve(SqliteCompatibilityTransactionStore.DATABASE_FILE)
        val batch = sampleBatch()

        assertOpened(SqliteCompatibilityTransactionStore.open(database)).use { store ->
            assertEquals(CompatibilityTransactionWriteResult.Created, store.create(batch))
            assertEquals(CompatibilityTransactionReadResult.Found(batch), store.find(batch.batchId))
        }

        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT schema_version FROM compatibility_schema").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt(1))
                }
                statement.executeQuery("SELECT COUNT(*) FROM compatibility_output").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(2, rows.getInt(1))
                }
            }
        }
    }

    @Test
    fun `conditional transition persists state and rejects stale expected state`() {
        val database = directory.resolve(SqliteCompatibilityTransactionStore.DATABASE_FILE)
        val batch = sampleBatch()

        assertOpened(SqliteCompatibilityTransactionStore.open(database)).use { store ->
            assertEquals(CompatibilityTransactionWriteResult.Created, store.create(batch))
            assertEquals(
                CompatibilityTransactionWriteResult.Updated(CompatibilityBatchState.SOURCE_APPLIED),
                store.transition(
                    batch.batchId,
                    CompatibilityBatchState.PREPARED,
                    CompatibilityBatchState.SOURCE_APPLIED,
                    2,
                ),
            )
            assertEquals(
                CompatibilityTransactionWriteResult.Conflict(CompatibilityBatchState.SOURCE_APPLIED),
                store.transition(
                    batch.batchId,
                    CompatibilityBatchState.PREPARED,
                    CompatibilityBatchState.COMMITTED,
                    3,
                ),
            )
            assertEquals(
                CompatibilityBatchState.SOURCE_APPLIED,
                assertFound(store.find(batch.batchId)).state,
            )
        }
    }

    @Test
    fun `quarantine transition persists bounded reason`() {
        val batch = sampleBatch()
        assertOpened(SqliteCompatibilityTransactionStore.open(directory.resolve("compatibility-transactions.sqlite"))).use { store ->
            store.create(batch)

            assertEquals(
                CompatibilityTransactionWriteResult.Updated(CompatibilityBatchState.QUARANTINED),
                store.transition(
                    batch.batchId,
                    CompatibilityBatchState.PREPARED,
                    CompatibilityBatchState.QUARANTINED,
                    2,
                    "PartialOutputs",
                ),
            )
            assertEquals("PartialOutputs", assertFound(store.find(batch.batchId)).quarantineReason)
        }
    }

    @Test
    fun `unresolved query validates limit and uses stable changed time then uuid order`() {
        val first = sampleBatch(batchId = UUID(0, 1), changedAtEpochSecond = 2)
        val second = sampleBatch(batchId = UUID(0, 2), changedAtEpochSecond = 1)
        val third = sampleBatch(batchId = UUID(0, 3), changedAtEpochSecond = 2)

        assertOpened(SqliteCompatibilityTransactionStore.open(directory.resolve("compatibility-transactions.sqlite"))).use { store ->
            listOf(first, second, third).forEach(store::create)

            assertEquals(
                CompatibilityTransactionReadManyResult.Loaded(listOf(second, first)),
                store.unresolved(2),
            )
            assertEquals(
                CompatibilityTransactionReadManyResult.Rejected("LimitOutOfRange"),
                store.unresolved(0),
            )
            assertEquals(
                CompatibilityTransactionReadManyResult.Rejected("LimitOutOfRange"),
                store.unresolved(101),
            )
        }
    }

    @Test
    fun `duplicate batch reports current durable state`() {
        val batch = sampleBatch()
        assertOpened(SqliteCompatibilityTransactionStore.open(directory.resolve("compatibility-transactions.sqlite"))).use { store ->
            assertEquals(CompatibilityTransactionWriteResult.Created, store.create(batch))
            assertEquals(
                CompatibilityTransactionWriteResult.Conflict(CompatibilityBatchState.PREPARED),
                store.create(batch.copyForTest(sinkIdentity = "sink:other")),
            )
        }
    }

    @Test
    fun `unresolved capacity rejects a new batch without altering existing rows`() {
        val database = directory.resolve("compatibility-transactions.sqlite")
        assertOpened(SqliteCompatibilityTransactionStore.open(database)).close()
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            configure(connection)
            SqliteCompatibilityTransactionStore(connection, unresolvedCapacity = 2).use { store ->
                assertEquals(CompatibilityTransactionWriteResult.Created, store.create(sampleBatch(batchId = UUID(0, 1))))
                assertEquals(CompatibilityTransactionWriteResult.Created, store.create(sampleBatch(batchId = UUID(0, 2))))
                assertEquals(
                    CompatibilityTransactionWriteResult.CapacityReached,
                    store.create(sampleBatch(batchId = UUID(0, 3))),
                )
                assertEquals(2, assertLoaded(store.unresolved(100)).size)
            }
        }
    }

    @Test
    fun `missing and nil batch ids fail without SQL mutation`() {
        assertOpened(SqliteCompatibilityTransactionStore.open(directory.resolve("compatibility-transactions.sqlite"))).use { store ->
            assertEquals(CompatibilityTransactionReadResult.Missing, store.find(UUID(0, 99)))
            assertEquals(CompatibilityTransactionReadResult.Rejected("InvalidBatchId"), store.find(UUID(0, 0)))
            assertEquals(
                CompatibilityTransactionWriteResult.Rejected("InvalidBatchId"),
                store.transition(UUID(0, 0), CompatibilityBatchState.PREPARED, CompatibilityBatchState.SOURCE_APPLIED, 2),
            )
        }
    }

    @Test
    fun `readonly database reports bounded failure and retains committed row`() {
        val batch = sampleBatch()
        val database = directory.resolve("compatibility-transactions.sqlite")
        assertOpened(SqliteCompatibilityTransactionStore.open(database)).use { store -> store.create(batch) }

        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            configure(connection)
            connection.createStatement().execute("PRAGMA query_only = ON")
            SqliteCompatibilityTransactionStore(connection).use { store ->
                assertEquals(
                    CompatibilityTransactionWriteResult.Failed("ReadOnlyDatabase"),
                    store.transition(
                        batch.batchId,
                        CompatibilityBatchState.PREPARED,
                        CompatibilityBatchState.SOURCE_APPLIED,
                        2,
                    ),
                )
            }
        }

        assertOpened(SqliteCompatibilityTransactionStore.open(database)).use { store ->
            assertEquals(CompatibilityBatchState.PREPARED, assertFound(store.find(batch.batchId)).state)
        }
    }

    @Test
    fun `single batch rolls back when database becomes full`() {
        val database = directory.resolve("compatibility-transactions.sqlite")
        assertOpened(SqliteCompatibilityTransactionStore.open(database)).close()
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            configure(connection)
            connection.createStatement().use { statement ->
                val pages =
                    statement.executeQuery("PRAGMA page_count").use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                statement.execute("PRAGMA max_page_count = $pages")
            }
            val outputs = (0 until 128).map { CompatibilityOutput(it, 1) }
            SqliteCompatibilityTransactionStore(connection).use { store ->
                assertEquals(
                    CompatibilityTransactionWriteResult.Failed("DiskFull"),
                    store.create(
                        sampleBatch(
                            sourceBeforeAmount = 256,
                            sourceAfterAmount = 128,
                            transferAmount = 128,
                            outputs = outputs,
                        ),
                    ),
                )
                assertEquals(CompatibilityTransactionReadResult.Missing, store.find(BATCH_ID))
            }
        }
    }

    @Test
    fun `opening corrupt unsupported or schemaless database fails closed`() {
        val corrupt = directory.resolve("corrupt.sqlite")
        Files.write(corrupt, ByteArray(4_096) { 0x5a })
        assertEquals(
            SqliteCompatibilityTransactionOpenResult.Failed("CorruptDatabase"),
            SqliteCompatibilityTransactionStore.open(corrupt),
        )

        val unsupported = directory.resolve("unsupported.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${unsupported.toAbsolutePath()}").use { connection ->
            connection.createStatement().execute(
                "CREATE TABLE compatibility_schema(singleton INTEGER PRIMARY KEY, schema_version INTEGER NOT NULL)",
            )
            connection.createStatement().execute("INSERT INTO compatibility_schema VALUES (1, 99)")
        }
        assertEquals(
            SqliteCompatibilityTransactionOpenResult.Failed("UnsupportedSchemaVersion:99"),
            SqliteCompatibilityTransactionStore.open(unsupported),
        )

        val schemaless = directory.resolve("schemaless.sqlite")
        Files.createFile(schemaless)
        assertEquals(
            SqliteCompatibilityTransactionOpenResult.Failed("SchemaMissing"),
            SqliteCompatibilityTransactionStore.open(schemaless),
        )

        val incomplete = directory.resolve("incomplete.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${incomplete.toAbsolutePath()}").use { connection ->
            connection.createStatement().execute(
                "CREATE TABLE compatibility_schema(singleton INTEGER PRIMARY KEY, schema_version INTEGER NOT NULL)",
            )
            connection.createStatement().execute("INSERT INTO compatibility_schema VALUES (1, 1)")
        }
        assertEquals(
            SqliteCompatibilityTransactionOpenResult.Failed("SchemaMissing"),
            SqliteCompatibilityTransactionStore.open(incomplete),
        )
    }

    @Test
    fun `invalid persisted enum returns bounded record rejection`() {
        val batch = sampleBatch()
        val database = directory.resolve("compatibility-transactions.sqlite")
        assertOpened(SqliteCompatibilityTransactionStore.open(database)).use { store -> store.create(batch) }
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.prepareStatement("UPDATE compatibility_batch SET sink_kind = ? WHERE batch_uuid = ?").use { statement ->
                statement.setString(1, "UNKNOWN_KIND")
                statement.setString(2, batch.batchId.toString())
                statement.executeUpdate()
            }
        }

        assertOpened(SqliteCompatibilityTransactionStore.open(database)).use { store ->
            assertEquals(
                CompatibilityTransactionReadResult.Rejected("InvalidRecord"),
                store.find(batch.batchId),
            )
        }
    }

    private fun assertOpened(result: SqliteCompatibilityTransactionOpenResult): SqliteCompatibilityTransactionStore =
        assertInstanceOf(SqliteCompatibilityTransactionOpenResult.Opened::class.java, result).store

    private fun assertFound(result: CompatibilityTransactionReadResult): CompatibilityBatch =
        assertInstanceOf(CompatibilityTransactionReadResult.Found::class.java, result).batch

    private fun assertLoaded(result: CompatibilityTransactionReadManyResult): List<CompatibilityBatch> =
        assertInstanceOf(CompatibilityTransactionReadManyResult.Loaded::class.java, result).batches

    private fun configure(connection: java.sql.Connection) {
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA foreign_keys = ON")
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA synchronous = FULL")
            statement.execute("PRAGMA busy_timeout = 5000")
        }
    }
}

internal fun sampleBatch(
    batchId: UUID = BATCH_ID,
    sourceEntityUuid: UUID = SOURCE_ID,
    sourceRevision: Long = 7,
    sourceBeforeAmount: Long = 128,
    sourceAfterAmount: Long = 64,
    transferAmount: Long = 64,
    sinkKind: CompatibilitySinkKind = CompatibilitySinkKind.HOPPER_INVENTORY,
    sinkIdentity: String = "world:00000000-0000-0000-0000-000000000003:0:64:0",
    outputs: List<CompatibilityOutput> = listOf(CompatibilityOutput(0, 32), CompatibilityOutput(1, 32)),
    state: CompatibilityBatchState = CompatibilityBatchState.PREPARED,
    quarantineReason: String? = null,
    createdAtEpochSecond: Long = 1,
    changedAtEpochSecond: Long = createdAtEpochSecond,
): CompatibilityBatch =
    CompatibilityBatch.create(
        batchId = batchId,
        sourceEntityUuid = sourceEntityUuid,
        sourceRevision = sourceRevision,
        sourceBeforeAmount = sourceBeforeAmount,
        sourceAfterAmount = sourceAfterAmount,
        transferAmount = transferAmount,
        sinkKind = sinkKind,
        sinkIdentity = sinkIdentity,
        outputs = outputs,
        state = state,
        quarantineReason = quarantineReason,
        createdAtEpochSecond = createdAtEpochSecond,
        changedAtEpochSecond = changedAtEpochSecond,
    )

private fun CompatibilityBatch.copyForTest(sinkIdentity: String): CompatibilityBatch =
    CompatibilityBatch.create(
        batchId = batchId,
        sourceEntityUuid = sourceEntityUuid,
        sourceRevision = sourceRevision,
        sourceBeforeAmount = sourceBeforeAmount,
        sourceAfterAmount = sourceAfterAmount,
        transferAmount = transferAmount,
        sinkKind = sinkKind,
        sinkIdentity = sinkIdentity,
        outputs = outputs,
        state = state,
        quarantineReason = quarantineReason,
        createdAtEpochSecond = createdAtEpochSecond,
        changedAtEpochSecond = changedAtEpochSecond,
    )

internal val BATCH_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
internal val SOURCE_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000102")
