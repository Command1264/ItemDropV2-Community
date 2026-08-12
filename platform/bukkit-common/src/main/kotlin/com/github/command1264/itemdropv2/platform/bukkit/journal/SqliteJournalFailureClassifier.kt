package com.github.command1264.itemdropv2.platform.bukkit.journal

import java.sql.SQLException

/** Maps stable SQLite primary result codes to bounded diagnostics without exposing driver messages or paths. */
public object SqliteJournalFailureClassifier {
    @Suppress("CyclomaticComplexMethod")
    public fun classify(error: SQLException): String {
        val primaryCode = error.errorCode and PRIMARY_RESULT_CODE_MASK
        if (primaryCode == SQLITE_GENERIC_ERROR && error.message?.contains(DISK_FULL_MESSAGE, ignoreCase = true) == true) {
            return "DiskFull"
        }
        return when (primaryCode) {
            SQLITE_PERMISSION -> "PermissionDenied"
            SQLITE_BUSY, SQLITE_LOCKED -> "DatabaseBusy"
            SQLITE_NO_MEMORY -> "MemoryExhausted"
            SQLITE_READ_ONLY -> "ReadOnlyDatabase"
            SQLITE_IO_ERROR -> "DatabaseIoFailure"
            SQLITE_CORRUPT, SQLITE_NOT_A_DATABASE -> "CorruptDatabase"
            SQLITE_FULL -> "DiskFull"
            SQLITE_CANNOT_OPEN -> "DatabaseCannotOpen"
            SQLITE_SCHEMA_CHANGED -> "DatabaseSchemaChanged"
            SQLITE_TOO_BIG -> "DatabaseValueTooLarge"
            SQLITE_CONSTRAINT -> "DatabaseConstraintViolation"
            else -> "SqliteFailure:$primaryCode"
        }
    }

    private const val DISK_FULL_MESSAGE = "database or disk is full"
    private const val PRIMARY_RESULT_CODE_MASK = 0xff
    private const val SQLITE_GENERIC_ERROR = 1
    private const val SQLITE_PERMISSION = 3
    private const val SQLITE_BUSY = 5
    private const val SQLITE_LOCKED = 6
    private const val SQLITE_NO_MEMORY = 7
    private const val SQLITE_READ_ONLY = 8
    private const val SQLITE_IO_ERROR = 10
    private const val SQLITE_CORRUPT = 11
    private const val SQLITE_FULL = 13
    private const val SQLITE_CANNOT_OPEN = 14
    private const val SQLITE_SCHEMA_CHANGED = 17
    private const val SQLITE_TOO_BIG = 18
    private const val SQLITE_CONSTRAINT = 19
    private const val SQLITE_NOT_A_DATABASE = 26
}
