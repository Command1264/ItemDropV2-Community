package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.platform.bukkit.RuntimeDiagnosticContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class DiagnosticReportServiceTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `disabled service does not create report directory`() {
        val service = service(enabled = false)

        val result =
            service.submit(
                DiagnosticSeverity.WARNING,
                "virtual item merge rejected (MixedVirtualState)",
            )
        service.close()

        assertNull(result)
        assertFalse(Files.exists(temporaryDirectory.resolve("reports")))
    }

    @Test
    fun `writes warning and error reports with immutable metadata context and cause`() {
        val service = service()
        val warningPath =
            requireNotNull(
                service.submit(
                    DiagnosticSeverity.WARNING,
                    "virtual item merge rejected (MixedVirtualState)",
                    RuntimeDiagnosticContext(
                        fields =
                            mapOf(
                                "merge.source.material" to "STONE",
                                "merge.target.virtual-amount" to "missing",
                            ),
                    ),
                ),
            )
        val errorPath =
            requireNotNull(
                service.submit(
                    DiagnosticSeverity.ERROR,
                    "ItemDropV2 startup failed",
                    RuntimeDiagnosticContext(cause = IllegalStateException("boom")),
                ),
            )
        service.close()

        assertTrue(warningPath.startsWith("plugins/ItemDropV2/reports/warn/"))
        assertTrue(errorPath.startsWith("plugins/ItemDropV2/reports/error/"))
        assertTrue(warningPath.contains("2026-07-30-08-02-59-UTC+08-00-000001"))
        val warning = resolveRelativeReport(warningPath).toFile().readText()
        val error = resolveRelativeReport(errorPath).toFile().readText()
        assertTrue(warning.contains("severity=warn"))
        assertTrue(warning.contains("timestamp=2026-07-30-08-02-59-UTC+08-00"))
        assertTrue(warning.contains("plugin.version=1.0.0-SNAPSHOT"))
        assertTrue(warning.contains("plugin.git-commit=220ff0458592ff4a6a911364b4001aac4ed7af68"))
        assertTrue(warning.contains("plugin.git-commit-short=220ff045"))
        assertTrue(warning.contains("plugin.git-dirty=true"))
        assertTrue(warning.contains("merge.source.material=STONE"))
        assertTrue(warning.contains("merge.target.virtual-amount=missing"))
        assertTrue(warning.contains("message=virtual item merge rejected (MixedVirtualState)\n\n[runtime]"))
        assertTrue(warning.contains("plugin.version=1.0.0-SNAPSHOT\n\n[context]"))
        assertTrue(warning.contains("merge.target.virtual-amount=missing\n\n[call-stack]"))
        assertTrue(error.contains("severity=error"))
        assertTrue(error.contains("\n\n[cause]"))
        assertTrue(error.contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun `retains only the newest configured reports per severity`() {
        val service = service(warningRetention = 2, errorRetention = 1)
        repeat(3) { index ->
            service.submit(DiagnosticSeverity.WARNING, "warning-$index")
        }
        service.submit(DiagnosticSeverity.ERROR, "error-0")
        service.submit(DiagnosticSeverity.ERROR, "error-1")
        service.close()

        assertEquals(2, reportFiles("warn").size)
        assertEquals(1, reportFiles("error").size)
        val contents = reportFiles("warn").map { path -> path.toFile().readText() }
        assertTrue(contents.any { it.contains("message=warning-1") })
        assertTrue(contents.any { it.contains("message=warning-2") })
    }

    @Test
    fun `diagnostic switch accepts only explicit true`() {
        assertTrue(DiagnosticReportSwitch.isEnabled("true"))
        assertTrue(DiagnosticReportSwitch.isEnabled("TRUE"))
        assertFalse(DiagnosticReportSwitch.isEnabled(null))
        assertFalse(DiagnosticReportSwitch.isEnabled(""))
        assertFalse(DiagnosticReportSwitch.isEnabled("1"))
        assertFalse(DiagnosticReportSwitch.isEnabled("yes"))
    }

    private fun service(
        enabled: Boolean = true,
        warningRetention: Int = 100,
        errorRetention: Int = 50,
    ): DiagnosticReportService =
        DiagnosticReportService(
            enabled = enabled,
            dataDirectory = temporaryDirectory,
            relativeDataDirectory = "plugins/ItemDropV2",
            metadataProvider = {
                mapOf(
                    "plugin.version" to "1.0.0-SNAPSHOT",
                    "plugin.git-commit" to "220ff0458592ff4a6a911364b4001aac4ed7af68",
                    "plugin.git-commit-short" to "220ff045",
                    "plugin.git-dirty" to "true",
                )
            },
            clock = Clock.fixed(Instant.parse("2026-07-30T00:02:59.987Z"), ZoneId.of("Asia/Taipei")),
            warningRetention = warningRetention,
            errorRetention = errorRetention,
        )

    private fun resolveRelativeReport(relativePath: String): Path {
        val underDataDirectory = relativePath.removePrefix("plugins/ItemDropV2/")
        return temporaryDirectory.resolve(underDataDirectory.replace('/', java.io.File.separatorChar))
    }

    private fun reportFiles(severity: String): List<Path> =
        Files.list(temporaryDirectory.resolve("reports").resolve(severity)).use { stream ->
            stream
                .filter { path -> path.fileName.toString().endsWith(".txt") }
                .sorted()
                .iterator()
                .asSequence()
                .toList()
        }
}
