package com.github.command1264.itemdropv2.platform.bukkit.journal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.SQLException

class SqliteJournalFailureClassifierTest {
    @Test
    fun `classifies actionable primary SQLite result codes without exposing database messages`() {
        val expected =
            mapOf(
                3 to "PermissionDenied",
                5 to "DatabaseBusy",
                8 to "ReadOnlyDatabase",
                10 to "DatabaseIoFailure",
                11 to "CorruptDatabase",
                13 to "DiskFull",
                14 to "DatabaseCannotOpen",
                26 to "CorruptDatabase",
            )

        expected.forEach { (code, reason) ->
            assertEquals(reason, SqliteJournalFailureClassifier.classify(SQLException("sensitive path", "", code)))
        }
        assertEquals("SqliteFailure:31", SqliteJournalFailureClassifier.classify(SQLException("secret", "", 31)))
        assertEquals(
            "DiskFull",
            SqliteJournalFailureClassifier.classify(SQLException("[SQLITE_ERROR] database or disk is full", "", 1)),
        )
    }

    @Test
    fun `classifies extended codes by their stable primary byte`() {
        assertEquals(
            "ReadOnlyDatabase",
            SqliteJournalFailureClassifier.classify(SQLException("moved", "", 8 or (4 shl 8))),
        )
        assertEquals(
            "DatabaseIoFailure",
            SqliteJournalFailureClassifier.classify(SQLException("write", "", 10 or (3 shl 8))),
        )
    }
}
