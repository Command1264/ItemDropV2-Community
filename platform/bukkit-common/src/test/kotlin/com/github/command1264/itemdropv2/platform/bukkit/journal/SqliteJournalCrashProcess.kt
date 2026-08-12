package com.github.command1264.itemdropv2.platform.bukkit.journal

import java.nio.file.Paths
import java.sql.DriverManager

/** Child-process fixture: exits without closing JDBC so the parent can verify WAL recovery. */
internal object SqliteJournalCrashProcess {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val database = Paths.get(args[0]).toAbsolutePath().normalize()
        val committed = args[1] == "commit"
        val connection = DriverManager.getConnection("jdbc:sqlite:$database")
        connection.createStatement().use { statement ->
            statement.execute("PRAGMA journal_mode = WAL")
            statement.execute("PRAGMA synchronous = FULL")
        }
        connection.autoCommit = false
        connection.prepareStatement("UPDATE item_state SET revision = ?").use { statement ->
            statement.setLong(1, if (committed) 2 else 3)
            check(statement.executeUpdate() == 1)
        }
        if (committed) connection.commit()
        Runtime.getRuntime().halt(0)
    }
}
