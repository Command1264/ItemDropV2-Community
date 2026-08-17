package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemOwnership
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateJournalPresentation
import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import com.github.command1264.itemdropv2.core.VirtualItemAmount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID

class SqliteItemStateJournalStoreTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `creates readable schema and round trips all state columns`() {
        val database = directory.resolve("item-state.db")
        val record =
            sampleJournalUpsert(7).copyForTest(
                state =
                    ItemState(
                        ownership =
                            ItemOwnership(
                                ownerUuid = UUID.fromString("00000000-0000-0000-0000-000000000504"),
                                protectionSecondsRemaining = 17,
                                eligibleOwnerUuids =
                                    listOf(
                                        UUID.fromString("00000000-0000-0000-0000-000000000504"),
                                        UUID.fromString("00000000-0000-0000-0000-000000000505"),
                                    ),
                            ),
                        elapsedLifetimeSeconds = 3,
                        originalLifetimeSeconds = 300,
                        virtualAmount = VirtualItemAmount.of(8192),
                    ),
                presentation = ItemStateJournalPresentation("current", true, "original", false),
            )

        val store = assertOpened(SqliteItemStateJournalStore.open(database, record.identity.worldUuid))
        assertEquals(JournalWriteResult.Written, store.append(record))
        assertEquals(JournalWriteResult.Written, store.flush())
        assertEquals(listOf(record), store.readAllRecordsForTest())
        store.close()

        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT schema_version FROM journal_schema").use { rows ->
                    assertTrue(rows.next())
                    assertEquals(1, rows.getInt("schema_version"))
                }
                statement
                    .executeQuery(
                        "SELECT revision, material_key, virtual_amount, managed_name FROM item_state",
                    ).use { rows ->
                        assertTrue(rows.next())
                        assertEquals(7, rows.getLong("revision"))
                        assertEquals("minecraft:diamond_sword", rows.getString("material_key"))
                        assertEquals(8192, rows.getLong("virtual_amount"))
                        assertEquals("current", rows.getString("managed_name"))
                    }
            }
        }
    }

    @Test
    fun `conditionally replaces newer revisions refreshes presentation and deletes with tombstone`() {
        val record = sampleJournalUpsert(3)
        val store = assertOpened(SqliteItemStateJournalStore.open(directory.resolve("item-state.db"), record.identity.worldUuid))
        assertEquals(JournalWriteResult.Written, store.append(record))
        assertEquals(JournalWriteResult.Written, store.append(sampleJournalUpsert(2)))
        val refreshed = record.withPresentation(requireNotNull(record.presentation).copy(managedName = "refreshed"))
        assertEquals(JournalWriteResult.Written, store.append(refreshed))
        assertEquals(listOf(refreshed), store.readAllRecordsForTest())

        assertEquals(JournalWriteResult.Written, store.append(record.asTombstone(4)))
        assertTrue(store.readAllRecordsForTest().isEmpty())
        store.close()
    }

    @Test
    fun `fails closed for damaged schema`() {
        val database = directory.resolve("item-state.db")
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().execute("CREATE TABLE journal_schema(singleton INTEGER, schema_version INTEGER NOT NULL)")
            connection.createStatement().execute("INSERT INTO journal_schema VALUES (1, 99)")
        }

        assertInstanceOf(SqliteJournalOpenResult.Failed::class.java, SqliteItemStateJournalStore.open(database, UUID.randomUUID()))
    }

    @Test
    fun `does not initialize an existing database with a missing schema`() {
        val world = sampleJournalUpsert().identity.worldUuid
        val worldDirectory = directory.resolve(world.toString())
        Files.createDirectories(worldDirectory)
        Files.createFile(worldDirectory.resolve("item-state.db"))

        val failed =
            assertInstanceOf(
                SqliteJournalOpenResult.Failed::class.java,
                SqliteItemStateJournalStore.openWithLegacyMigration(worldDirectory, world),
            )

        assertEquals("SchemaMissing", failed.reason)
    }

    @Test
    fun `imports legacy checkpoint and wal without deleting either source`() {
        val world = sampleJournalUpsert(1).identity.worldUuid
        val worldDirectory = directory.resolve(world.toString())
        val checkpoint = ItemStateJournalCheckpointStore(worldDirectory)
        val wal = ItemStateJournalWal(worldDirectory)
        assertInstanceOf(CheckpointWriteResult.Written::class.java, checkpoint.write(listOf(sampleJournalUpsert(1))))
        assertEquals(JournalWriteResult.Written, wal.append(sampleJournalUpsert(2), force = true))

        val migrated = assertOpened(SqliteItemStateJournalStore.openWithLegacyMigration(worldDirectory, world))
        assertEquals(listOf(sampleJournalUpsert(2)), migrated.readAllRecordsForTest())
        migrated.close()

        assertTrue(Files.exists(worldDirectory.resolve("manifest")))
        assertTrue(Files.exists(worldDirectory.resolve("active.wal")))
        assertTrue(Files.exists(worldDirectory.resolve("item-state.db")))
        assertFalse(Files.exists(worldDirectory.resolve("item-state.db.migrating")))
    }

    @Test
    fun `reports readonly permission failure without changing the committed row`() {
        val record = sampleJournalUpsert(1)
        val database = directory.resolve("item-state.db")
        assertOpened(SqliteItemStateJournalStore.open(database, record.identity.worldUuid)).use { store ->
            assertEquals(JournalWriteResult.Written, store.append(record))
        }
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().execute("PRAGMA query_only = ON")
            SqliteItemStateJournalStore(record.identity.worldUuid, connection).use { store ->
                assertEquals(JournalWriteResult.Failed("ReadOnlyDatabase"), store.append(sampleJournalUpsert(2)))
            }
        }

        assertOpened(SqliteItemStateJournalStore.open(database, record.identity.worldUuid)).use { store ->
            assertEquals(listOf(record), store.readAllRecordsForTest())
        }
    }

    @Test
    fun `reports simulated full database and rolls back the complete batch`() {
        val record = sampleJournalUpsert(1)
        val database = directory.resolve("item-state.db")
        assertOpened(SqliteItemStateJournalStore.open(database, record.identity.worldUuid)).close()
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                val pages =
                    statement.executeQuery("PRAGMA page_count").use { rows ->
                        rows.next()
                        rows.getLong(1)
                    }
                statement.execute("PRAGMA max_page_count = $pages")
            }
            SqliteItemStateJournalStore(record.identity.worldUuid, connection).use { store ->
                val records =
                    (1..1_000).map { index ->
                        sampleJournalUpsert(
                            revision = 1,
                            entityUuid = UUID(0, index.toLong()),
                        ).withPresentation(
                            requireNotNull(record.presentation).copy(managedName = "x".repeat(4_096)),
                        )
                    }
                assertEquals(JournalWriteResult.Failed("DiskFull"), store.appendBatch(records))
                assertTrue(store.readAllRecordsForTest().isEmpty())
            }
        }
    }

    @Test
    fun `rejects a database whose header is corrupt`() {
        val world = sampleJournalUpsert().identity.worldUuid
        val worldDirectory = directory.resolve(world.toString())
        Files.createDirectories(worldDirectory)
        Files.write(worldDirectory.resolve("item-state.db"), ByteArray(4_096) { 0x5a })

        val failed =
            assertInstanceOf(
                SqliteJournalOpenResult.Failed::class.java,
                SqliteItemStateJournalStore.openWithLegacyMigration(worldDirectory, world),
            )

        assertEquals("CorruptDatabase", failed.reason)
    }

    @Test
    fun `rejects an oversized world before decoding its rows`() {
        val database = directory.resolve("item-state.db")
        val first = sampleJournalUpsert(1, UUID(0, 701))
        val second = sampleJournalUpsert(1, UUID(0, 702))
        assertOpened(SqliteItemStateJournalStore.open(database, first.identity.worldUuid)).use { store ->
            assertEquals(JournalWriteResult.Written, store.appendBatch(listOf(first, second)))
        }
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection
                .prepareStatement("UPDATE item_state SET entity_uuid = ? WHERE entity_uuid = ?")
                .use { statement ->
                    statement.setString(1, "not-a-uuid")
                    statement.setString(2, second.identity.entityUuid.toString())
                    statement.executeUpdate()
                }
        }

        val failed =
            assertInstanceOf(
                SqliteJournalOpenResult.Failed::class.java,
                SqliteItemStateJournalStore.open(database, first.identity.worldUuid, maximumRecords = 1),
            )

        assertEquals("RecordCapacityExceeded:2>1", failed.reason)
    }

    @Test
    fun `capacity rejects only new identities and tombstones reclaim space`() {
        val database = directory.resolve("item-state.db")
        val first = sampleJournalUpsert(1, UUID(0, 711))
        val second = sampleJournalUpsert(1, UUID(0, 712))
        assertOpened(
            SqliteItemStateJournalStore.open(database, first.identity.worldUuid, maximumRecords = 1),
        ).use { store ->
            assertEquals(JournalWriteResult.Written, store.append(first))
            assertEquals(JournalWriteResult.Failed("RecordCapacityExceeded:1"), store.append(second))
            assertEquals(JournalWriteResult.Written, store.append(sampleJournalUpsert(2, first.identity.entityUuid)))
            assertEquals(JournalWriteResult.Written, store.append(first.asTombstone(3)))
            assertEquals(JournalWriteResult.Written, store.append(second))

            val loaded = mutableListOf<ItemStateJournalRecord>()
            assertEquals(SqliteJournalReadResult.Loaded(1), store.readRecords { record -> loaded.add(record) })
            assertEquals(listOf(second), loaded)
        }
    }

    private fun assertOpened(result: SqliteJournalOpenResult): SqliteItemStateJournalStore =
        assertInstanceOf(SqliteJournalOpenResult.Opened::class.java, result).store
}

private fun ItemStateJournalRecord.copyForTest(
    state: ItemState,
    presentation: ItemStateJournalPresentation,
): ItemStateJournalRecord = ItemStateJournalRecord.upsert(identity, revision, chunk, sessionId, fingerprint, state, presentation)

internal fun SqliteItemStateJournalStore.readAllRecordsForTest(): List<ItemStateJournalRecord> =
    buildList {
        val read = readRecords { record -> add(record) }
        check(read is SqliteJournalReadResult.Loaded) { "journal read failed: $read" }
    }
