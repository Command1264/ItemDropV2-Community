package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemOwnership
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateJournalChunk
import com.github.command1264.itemdropv2.core.ItemStateJournalFingerprint
import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.ItemStateJournalPresentation
import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import com.github.command1264.itemdropv2.core.ItemStateJournalRecordType
import com.github.command1264.itemdropv2.core.VirtualItemAmount
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import java.util.UUID

@Suppress("MagicNumber", "TooManyFunctions")
public class SqliteItemStateJournalStore internal constructor(
    private val worldUuid: UUID,
    private val connection: Connection,
) : JournalRecordSink,
    AutoCloseable {
    override fun append(record: ItemStateJournalRecord): JournalWriteResult = appendBatch(listOf(record))

    override fun appendBatch(records: List<ItemStateJournalRecord>): JournalWriteResult {
        if (records.isEmpty()) return JournalWriteResult.Written
        return transaction {
            records.forEach(::writeRecord)
        }
    }

    override fun flush(): JournalWriteResult =
        try {
            connection.createStatement().use { it.execute("PRAGMA wal_checkpoint(FULL)") }
            JournalWriteResult.Written
        } catch (error: SQLException) {
            JournalWriteResult.Failed(SqliteJournalFailureClassifier.classify(error))
        }

    public fun load(): List<ItemStateJournalRecord> {
        val records = mutableListOf<ItemStateJournalRecord>()
        connection
            .prepareStatement(
                """
                SELECT entity_uuid, revision, chunk_x, chunk_z, session_uuid, material_key, metadata_sha256,
                       owner_uuid, protection_seconds, elapsed_lifetime_seconds, original_lifetime_seconds,
                       virtual_amount, managed_name, original_name_present, original_name, custom_name_visible
                FROM item_state WHERE world_uuid = ? ORDER BY entity_uuid
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, worldUuid.toString())
                statement.executeQuery().use { rows ->
                    while (rows.next()) records += rows.toRecord(loadEligibleOwners(rows.getString("entity_uuid")))
                }
            }
        return records
    }

    override fun close() {
        connection.close()
    }

    private fun writeRecord(record: ItemStateJournalRecord) {
        require(record.identity.worldUuid == worldUuid) { "journal record belongs to another world" }
        val existingRevision = findRevision(record.identity.entityUuid)
        if (existingRevision != null && record.revision < existingRevision) return
        if (record.type == ItemStateJournalRecordType.TOMBSTONE) {
            delete(record.identity.entityUuid, record.revision)
            return
        }
        deleteEligibleOwners(record.identity.entityUuid)
        if (existingRevision == null) insert(record) else update(record)
        insertEligibleOwners(record)
    }

    private fun findRevision(entityUuid: UUID): Long? =
        connection.prepareStatement("SELECT revision FROM item_state WHERE world_uuid = ? AND entity_uuid = ?").use { statement ->
            statement.setString(1, worldUuid.toString())
            statement.setString(2, entityUuid.toString())
            statement.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) else null }
        }

    private fun insert(record: ItemStateJournalRecord) {
        connection.prepareStatement("INSERT INTO item_state ($COLUMNS) VALUES (${List(17) { "?" }.joinToString()})").use {
            bindRecord(it, record)
            it.executeUpdate()
        }
    }

    private fun update(record: ItemStateJournalRecord) {
        val assignments = COLUMNS.split(", ").drop(2).joinToString { "$it = ?" }
        connection.prepareStatement("UPDATE item_state SET $assignments WHERE world_uuid = ? AND entity_uuid = ?").use { statement ->
            bindPayload(statement, record, 1)
            statement.setString(16, worldUuid.toString())
            statement.setString(17, record.identity.entityUuid.toString())
            statement.executeUpdate()
        }
    }

    private fun bindRecord(
        statement: java.sql.PreparedStatement,
        record: ItemStateJournalRecord,
    ) {
        statement.setString(1, worldUuid.toString())
        statement.setString(2, record.identity.entityUuid.toString())
        bindPayload(statement, record, 3)
    }

    private fun bindPayload(
        statement: java.sql.PreparedStatement,
        record: ItemStateJournalRecord,
        offset: Int,
    ) {
        val state = requireNotNull(record.state)
        val presentation = requireNotNull(record.presentation)
        statement.setLong(offset, record.revision)
        statement.setInt(offset + 1, record.chunk.x)
        statement.setInt(offset + 2, record.chunk.z)
        statement.setString(offset + 3, record.sessionId.toString())
        statement.setString(offset + 4, record.fingerprint.materialKey)
        statement.setString(offset + 5, record.fingerprint.metadataSha256)
        statement.setNullableString(offset + 6, state.ownership?.ownerUuid?.toString())
        statement.setNullableLong(offset + 7, state.ownership?.protectionSecondsRemaining)
        statement.setLong(offset + 8, state.elapsedLifetimeSeconds)
        statement.setNullableLong(offset + 9, state.originalLifetimeSeconds)
        statement.setNullableLong(offset + 10, state.virtualAmount?.value)
        statement.setNullableString(offset + 11, presentation.managedName)
        statement.setInt(offset + 12, if (presentation.originalNamePresent) 1 else 0)
        statement.setNullableString(offset + 13, presentation.originalName)
        statement.setInt(offset + 14, if (presentation.customNameVisible) 1 else 0)
    }

    private fun insertEligibleOwners(record: ItemStateJournalRecord) {
        val owners = requireNotNull(record.state).ownership?.eligibleOwnerUuids.orEmpty()
        connection
            .prepareStatement(
                "INSERT INTO item_state_eligible_owner(world_uuid, entity_uuid, ordinal, owner_uuid) VALUES (?, ?, ?, ?)",
            ).use { statement ->
                owners.forEachIndexed { ordinal, owner ->
                    statement.setString(1, worldUuid.toString())
                    statement.setString(2, record.identity.entityUuid.toString())
                    statement.setInt(3, ordinal)
                    statement.setString(4, owner.toString())
                    statement.addBatch()
                }
                if (owners.isNotEmpty()) statement.executeBatch()
            }
    }

    private fun loadEligibleOwners(entityUuid: String): List<UUID> =
        connection
            .prepareStatement(
                "SELECT owner_uuid FROM item_state_eligible_owner WHERE world_uuid = ? AND entity_uuid = ? ORDER BY ordinal",
            ).use { statement ->
                statement.setString(1, worldUuid.toString())
                statement.setString(2, entityUuid)
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(UUID.fromString(rows.getString(1))) } }
            }

    private fun deleteEligibleOwners(entityUuid: UUID) {
        connection.prepareStatement("DELETE FROM item_state_eligible_owner WHERE world_uuid = ? AND entity_uuid = ?").use {
            it.setString(1, worldUuid.toString())
            it.setString(2, entityUuid.toString())
            it.executeUpdate()
        }
    }

    private fun delete(
        entityUuid: UUID,
        revision: Long,
    ) {
        connection.prepareStatement("DELETE FROM item_state WHERE world_uuid = ? AND entity_uuid = ? AND revision <= ?").use {
            it.setString(1, worldUuid.toString())
            it.setString(2, entityUuid.toString())
            it.setLong(3, revision)
            it.executeUpdate()
        }
    }

    private fun ResultSet.toRecord(eligibleOwners: List<UUID>): ItemStateJournalRecord {
        val owner = nullableString("owner_uuid")?.let(UUID::fromString)
        val state =
            ItemState(
                ownership =
                    owner?.let {
                        ItemOwnership(it, requireNotNull(nullableLong("protection_seconds")), eligibleOwners)
                    },
                elapsedLifetimeSeconds = getLong("elapsed_lifetime_seconds"),
                originalLifetimeSeconds = nullableLong("original_lifetime_seconds"),
                virtualAmount = nullableLong("virtual_amount")?.let(VirtualItemAmount::of),
            )
        return ItemStateJournalRecord.upsert(
            ItemStateJournalIdentity(worldUuid, UUID.fromString(getString("entity_uuid"))),
            getLong("revision"),
            ItemStateJournalChunk(getInt("chunk_x"), getInt("chunk_z")),
            UUID.fromString(getString("session_uuid")),
            ItemStateJournalFingerprint(getString("material_key"), getString("metadata_sha256")),
            state,
            ItemStateJournalPresentation(
                nullableString("managed_name"),
                getInt("original_name_present") != 0,
                nullableString("original_name"),
                getInt("custom_name_visible") != 0,
            ),
        )
    }

    private fun transaction(block: () -> Unit): JournalWriteResult {
        var result: JournalWriteResult = JournalWriteResult.Written
        try {
            connection.autoCommit = false
            block()
            connection.commit()
        } catch (error: SQLException) {
            rollbackQuietly()
            result = JournalWriteResult.Failed(SqliteJournalFailureClassifier.classify(error))
        } catch (error: IllegalArgumentException) {
            rollbackQuietly()
            result = JournalWriteResult.Failed(error.javaClass.simpleName)
        } finally {
            try {
                connection.autoCommit = true
            } catch (error: SQLException) {
                if (result == JournalWriteResult.Written) {
                    result = JournalWriteResult.Failed(SqliteJournalFailureClassifier.classify(error))
                }
            }
        }
        return result
    }

    private fun rollbackQuietly() {
        try {
            connection.rollback()
        } catch (_: SQLException) {
            // The original database failure remains the actionable error.
        }
    }

    public companion object {
        public const val DATABASE_FILE: String = "item-state.db"
        private const val SCHEMA_VERSION = 1
        private const val COLUMNS =
            "world_uuid, entity_uuid, revision, chunk_x, chunk_z, session_uuid, material_key, metadata_sha256, " +
                "owner_uuid, protection_seconds, elapsed_lifetime_seconds, original_lifetime_seconds, virtual_amount, " +
                "managed_name, original_name_present, original_name, custom_name_visible"

        public fun open(
            database: Path,
            worldUuid: UUID,
        ): SqliteJournalOpenResult = openInternal(database.toAbsolutePath().normalize(), worldUuid, create = true)

        public fun openWithLegacyMigration(
            worldDirectory: Path,
            worldUuid: UUID,
        ): SqliteJournalOpenResult {
            val directory = worldDirectory.toAbsolutePath().normalize()
            val database = directory.resolve(DATABASE_FILE)
            if (Files.exists(database)) return openInternal(database, worldUuid, create = false)
            return migrateLegacy(directory, database, worldUuid)
        }

        @Suppress("ReturnCount")
        private fun migrateLegacy(
            directory: Path,
            database: Path,
            worldUuid: UUID,
        ): SqliteJournalOpenResult {
            val records =
                when (val loaded = loadLegacy(directory)) {
                    is LegacyLoadResult.Loaded -> loaded.records
                    is LegacyLoadResult.Failed -> return SqliteJournalOpenResult.Failed(loaded.reason)
                }
            val temporary = directory.resolve("$DATABASE_FILE.migrating")
            return try {
                Files.createDirectories(directory)
                Files.deleteIfExists(temporary)
                val opened = openInternal(temporary, worldUuid, create = true)
                val store = (opened as? SqliteJournalOpenResult.Opened)?.store ?: return opened
                val written = store.appendBatch(records)
                val expected = records.filter { it.type == ItemStateJournalRecordType.UPSERT }.associateBy { it.identity }
                val actual = store.load().associateBy { it.identity }
                if (written is JournalWriteResult.Failed || actual != expected) {
                    store.close()
                    return SqliteJournalOpenResult.Failed("LegacyMigrationVerificationFailed")
                }
                if (store.flush() is JournalWriteResult.Failed) {
                    store.close()
                    return SqliteJournalOpenResult.Failed("LegacyMigrationFlushFailed")
                }
                store.close()
                moveAtomically(temporary, database)
                openInternal(database, worldUuid, create = false)
            } catch (error: IOException) {
                SqliteJournalOpenResult.Failed(error.javaClass.simpleName)
            } catch (error: SQLException) {
                SqliteJournalOpenResult.Failed(SqliteJournalFailureClassifier.classify(error))
            } catch (error: SecurityException) {
                SqliteJournalOpenResult.Failed(error.javaClass.simpleName)
            } finally {
                deleteTemporary(temporary)
                deleteTemporary(temporary.resolveSibling("${temporary.fileName}-wal"))
                deleteTemporary(temporary.resolveSibling("${temporary.fileName}-shm"))
            }
        }

        @Suppress("ReturnCount")
        private fun loadLegacy(directory: Path): LegacyLoadResult {
            val checkpointRecords =
                when (val loaded = ItemStateJournalCheckpointStore(directory).load()) {
                    CheckpointLoadResult.Missing -> emptyList()
                    is CheckpointLoadResult.Loaded -> loaded.records
                    is CheckpointLoadResult.Invalid -> return LegacyLoadResult.Failed("Checkpoint:${loaded.reason}")
                    is CheckpointLoadResult.Failed -> return LegacyLoadResult.Failed("Checkpoint:${loaded.errorType}")
                }
            val walRecords =
                when (val loaded = ItemStateJournalWal(directory).load(repairTornTail = false)) {
                    is JournalLoadResult.Loaded -> loaded.records
                    is JournalLoadResult.Invalid -> return LegacyLoadResult.Failed("Wal:${loaded.reason}")
                    is JournalLoadResult.Failed -> return LegacyLoadResult.Failed("Wal:${loaded.errorType}")
                }
            val index = ItemStateJournalIndex()
            for (record in checkpointRecords + walRecords) {
                if (index.apply(record) is JournalIndexUpdate.Conflict) return LegacyLoadResult.Failed("RevisionConflict")
            }
            return LegacyLoadResult.Loaded(index.snapshot())
        }

        private fun openInternal(
            database: Path,
            worldUuid: UUID,
            create: Boolean,
        ): SqliteJournalOpenResult {
            var connection: Connection? = null
            return try {
                if (!create && !Files.isRegularFile(database)) return SqliteJournalOpenResult.Failed("DatabaseMissing")
                Files.createDirectories(requireNotNull(database.parent))
                // DriverManager performs the startup capability probe without linking server-owned SQLite classes.
                val openedConnection = DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}")
                connection = openedConnection
                configure(openedConnection)
                val schemaResult = initializeOrValidateSchema(openedConnection, allowCreate = create)
                if (schemaResult != null) {
                    openedConnection.close()
                    connection = null
                    SqliteJournalOpenResult.Failed(schemaResult)
                } else {
                    val store = SqliteItemStateJournalStore(worldUuid, openedConnection)
                    store.load()
                    connection = null
                    SqliteJournalOpenResult.Opened(store)
                }
            } catch (error: SQLException) {
                closeQuietly(connection)
                SqliteJournalOpenResult.Failed(SqliteJournalFailureClassifier.classify(error))
            } catch (error: IllegalArgumentException) {
                closeQuietly(connection)
                SqliteJournalOpenResult.Failed(error.javaClass.simpleName)
            } catch (error: IOException) {
                closeQuietly(connection)
                SqliteJournalOpenResult.Failed(error.javaClass.simpleName)
            } catch (error: SecurityException) {
                closeQuietly(connection)
                SqliteJournalOpenResult.Failed(error.javaClass.simpleName)
            } catch (error: LinkageError) {
                closeQuietly(connection)
                SqliteJournalOpenResult.Failed("SqliteDriverLinkage:${error.javaClass.simpleName}")
            }
        }

        private fun closeQuietly(connection: Connection?) {
            try {
                connection?.close()
            } catch (_: SQLException) {
                // Preserve the startup failure that made this connection unusable.
            }
        }

        private fun deleteTemporary(path: Path) {
            try {
                Files.deleteIfExists(path)
            } catch (_: IOException) {
                // A bounded migration temporary remains for administrator inspection.
            } catch (_: SecurityException) {
                // A bounded migration temporary remains for administrator inspection.
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

        @Suppress("ReturnCount")
        private fun initializeOrValidateSchema(
            connection: Connection,
            allowCreate: Boolean,
        ): String? {
            val hasSchema =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='journal_schema'").use {
                        it.next()
                    }
                }
            if (!hasSchema && !allowCreate) return "SchemaMissing"
            if (!hasSchema) createSchema(connection)
            val version =
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT singleton, schema_version FROM journal_schema").use { rows ->
                        if (!rows.next()) return "SchemaVersionMissing"
                        if (rows.getInt("singleton") != 1) return "SchemaSingletonInvalid"
                        val current = rows.getInt("schema_version")
                        if (rows.next()) return "SchemaVersionAmbiguous"
                        current
                    }
                }
            return if (version == SCHEMA_VERSION) null else "UnsupportedSchemaVersion:$version"
        }

        private fun createSchema(connection: Connection) {
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE TABLE journal_schema(singleton INTEGER PRIMARY KEY CHECK(singleton = 1), schema_version INTEGER NOT NULL)",
                )
                statement.execute("INSERT INTO journal_schema(singleton, schema_version) VALUES (1, $SCHEMA_VERSION)")
                statement.execute(
                    """
                    CREATE TABLE item_state(
                        world_uuid TEXT NOT NULL, entity_uuid TEXT NOT NULL, revision INTEGER NOT NULL CHECK(revision > 0),
                        chunk_x INTEGER NOT NULL, chunk_z INTEGER NOT NULL, session_uuid TEXT NOT NULL,
                        material_key TEXT NOT NULL, metadata_sha256 TEXT NOT NULL,
                        owner_uuid TEXT, protection_seconds INTEGER, elapsed_lifetime_seconds INTEGER NOT NULL,
                        original_lifetime_seconds INTEGER, virtual_amount INTEGER, managed_name TEXT,
                        original_name_present INTEGER NOT NULL, original_name TEXT, custom_name_visible INTEGER NOT NULL,
                        PRIMARY KEY(world_uuid, entity_uuid)
                    )
                    """.trimIndent(),
                )
                statement.execute(
                    """
                    CREATE TABLE item_state_eligible_owner(
                        world_uuid TEXT NOT NULL, entity_uuid TEXT NOT NULL, ordinal INTEGER NOT NULL, owner_uuid TEXT NOT NULL,
                        PRIMARY KEY(world_uuid, entity_uuid, ordinal),
                        FOREIGN KEY(world_uuid, entity_uuid) REFERENCES item_state(world_uuid, entity_uuid) ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        private fun moveAtomically(
            source: Path,
            target: Path,
        ) {
            try {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(source, target)
            }
        }
    }
}

private fun java.sql.PreparedStatement.setNullableString(
    index: Int,
    value: String?,
) {
    if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
}

private fun java.sql.PreparedStatement.setNullableLong(
    index: Int,
    value: Long?,
) {
    if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
}

private fun ResultSet.nullableString(column: String): String? = getString(column).takeUnless { wasNull() }

private fun ResultSet.nullableLong(column: String): Long? = getLong(column).takeUnless { wasNull() }

private sealed interface LegacyLoadResult {
    data class Loaded(
        val records: List<ItemStateJournalRecord>,
    ) : LegacyLoadResult

    data class Failed(
        val reason: String,
    ) : LegacyLoadResult
}

public sealed interface SqliteJournalOpenResult {
    public data class Opened(
        public val store: SqliteItemStateJournalStore,
    ) : SqliteJournalOpenResult

    public data class Failed(
        public val reason: String,
    ) : SqliteJournalOpenResult
}
