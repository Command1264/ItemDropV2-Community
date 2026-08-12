package com.github.command1264.itemdropv2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class DiagnosticLogGatewayTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `warning and error split semicolon sections and report paths into independent records`() {
        val records = mutableListOf<LogRecord>()
        val logger =
            Logger.getAnonymousLogger().apply {
                useParentHandlers = false
                addHandler(recordingHandler(records))
            }
        val reports =
            DiagnosticReportService(
                enabled = true,
                dataDirectory = temporaryDirectory,
                relativeDataDirectory = "plugins/ItemDropV2",
                metadataProvider = { emptyMap() },
                clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC),
                warningRetention = 100,
                errorRetention = 50,
            )
        val gateway = DiagnosticLogGateway(logger, reports, RuntimeWarningLimiter(maximumWarningsPerKey = 2))

        gateway.warning("warning summary; warning detail")
        gateway.error("error summary；error detail")
        reports.close()

        assertEquals(6, records.size)
        assertEquals(
            listOf(Level.WARNING, Level.WARNING, Level.WARNING, Level.SEVERE, Level.SEVERE, Level.SEVERE),
            records.map(LogRecord::getLevel),
        )
        assertEquals("warning summary", records[0].message)
        assertEquals("warning detail", records[1].message)
        assertTrue(records[2].message.startsWith("[diagnostic report: plugins/ItemDropV2/reports/warn/"))
        assertEquals("error summary", records[3].message)
        assertEquals("error detail", records[4].message)
        assertTrue(records[5].message.startsWith("[diagnostic report: plugins/ItemDropV2/reports/error/"))
    }

    @Test
    fun `runtime warning reports follow limiter while suppression does not create a duplicate report`() {
        val records = mutableListOf<LogRecord>()
        val logger =
            Logger.getAnonymousLogger().apply {
                useParentHandlers = false
                addHandler(recordingHandler(records))
            }
        val reports =
            DiagnosticReportService(
                enabled = true,
                dataDirectory = temporaryDirectory,
                relativeDataDirectory = "plugins/ItemDropV2",
                metadataProvider = { emptyMap() },
                clock = Clock.fixed(Instant.parse("2026-07-30T00:02:59Z"), ZoneOffset.UTC),
                warningRetention = 100,
                errorRetention = 50,
            )
        val gateway =
            DiagnosticLogGateway(
                logger,
                reports,
                RuntimeWarningLimiter(maximumWarningsPerKey = 2),
            )

        gateway.runtimeWarning("virtual item merge rejected (MixedVirtualState)")
        gateway.runtimeWarning("virtual item merge rejected (MixedVirtualState)")
        gateway.runtimeWarning("virtual item merge rejected (MixedVirtualState)")
        gateway.runtimeWarning("virtual item merge rejected (MixedVirtualState)")
        reports.close()

        assertEquals(5, records.size)
        assertTrue(records[0].message.contains("ItemDropV2 runtime warning"))
        assertTrue(records[1].message.contains("plugins/ItemDropV2/reports/warn/"))
        assertTrue(records[2].message.contains("ItemDropV2 runtime warning"))
        assertTrue(records[3].message.contains("plugins/ItemDropV2/reports/warn/"))
        assertTrue(records[4].message.contains("additional warnings of this type are suppressed"))
        assertEquals(2, reportFiles("warn").size)
    }

    private fun recordingHandler(records: MutableList<LogRecord>): Handler =
        object : Handler() {
            override fun publish(record: LogRecord) {
                records += record
            }

            override fun flush() = Unit

            override fun close() = Unit
        }

    private fun reportFiles(severity: String): List<Path> =
        Files.list(temporaryDirectory.resolve("reports").resolve(severity)).use { stream ->
            stream
                .filter { path -> path.fileName.toString().endsWith(".txt") }
                .iterator()
                .asSequence()
                .toList()
        }
}
