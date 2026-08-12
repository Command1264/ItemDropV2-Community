package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.platform.bukkit.AdministratorTimestampFormatter
import com.github.command1264.itemdropv2.platform.bukkit.RuntimeDiagnosticContext
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Level
import java.util.logging.Logger

internal enum class DiagnosticSeverity(
    val folderName: String,
) {
    WARNING("warn"),
    ERROR("error"),
}

internal object DiagnosticReportSwitch {
    const val PROPERTY_NAME: String = "itemdropv2.diagnostic-reports"

    fun isEnabled(value: String?): Boolean = value.equals("true", ignoreCase = true)
}

internal class DiagnosticReportService(
    private val enabled: Boolean,
    private val dataDirectory: Path,
    private val relativeDataDirectory: String,
    private val metadataProvider: () -> Map<String, String>,
    private val clock: Clock = Clock.system(ZoneId.systemDefault()),
    private val warningRetention: Int = DEFAULT_WARNING_RETENTION,
    private val errorRetention: Int = DEFAULT_ERROR_RETENTION,
    private val failureSink: (String, Throwable?) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val sequence = AtomicLong()
    private val closed = AtomicBoolean()
    private val executor =
        ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(REPORT_QUEUE_CAPACITY),
            DiagnosticThreadFactory,
        )

    init {
        require(relativeDataDirectory.isNotBlank()) { "relative data directory must not be blank" }
        require(warningRetention > 0) { "warning retention must be positive" }
        require(errorRetention > 0) { "error retention must be positive" }
    }

    fun submit(
        severity: DiagnosticSeverity,
        message: String,
        context: RuntimeDiagnosticContext = RuntimeDiagnosticContext(),
    ): String? {
        if (!enabled || closed.get()) return null
        val timestamp = AdministratorTimestampFormatter.format(clock.instant(), clock.zone)
        val reportId = sequence.incrementAndGet()
        val filename = reportFilename(timestamp, reportId, message)
        val severityDirectory = dataDirectory.resolve(REPORTS_DIRECTORY).resolve(severity.folderName)
        val destination = severityDirectory.resolve(filename).normalize()
        require(destination.startsWith(dataDirectory.normalize())) { "diagnostic report escaped plugin data directory" }
        val relativePath = "$relativeDataDirectory/$REPORTS_DIRECTORY/${severity.folderName}/$filename"
        val snapshot =
            DiagnosticReportSnapshot(
                timestamp = timestamp,
                reportId = reportId,
                severity = severity,
                message = message,
                metadata = metadataProvider().toMap(),
                fields = context.fields.toMap(),
                callStack = captureCallStack(),
                cause = context.cause?.stackTraceText(),
            )
        val write = Runnable { writeReport(destination, snapshot) }
        try {
            executor.execute(write)
        } catch (_: RejectedExecutionException) {
            write.run()
        }
        return relativePath.replace('\\', '/')
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.shutdown()
        try {
            if (!executor.awaitTermination(REPORT_SHUTDOWN_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow()
            }
        } catch (error: InterruptedException) {
            executor.shutdownNow()
            Thread.currentThread().interrupt()
            failureSink("diagnostic report shutdown interrupted", error)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun writeReport(
        destination: Path,
        snapshot: DiagnosticReportSnapshot,
    ) {
        try {
            Files.createDirectories(destination.parent)
            val temporary = destination.resolveSibling("${destination.fileName}.tmp")
            Files.write(temporary, render(snapshot).toByteArray(StandardCharsets.UTF_8))
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            prune(destination.parent, retention(snapshot.severity))
        } catch (error: RuntimeException) {
            failureSink("diagnostic report write failed (${error.javaClass.simpleName})", error)
        } catch (error: java.io.IOException) {
            failureSink("diagnostic report write failed (${error.javaClass.simpleName})", error)
        }
    }

    private fun prune(
        directory: Path,
        maximumReports: Int,
    ) {
        val reports =
            Files.newDirectoryStream(directory, "*.txt").use { entries ->
                entries.toList().sortedBy { path -> path.fileName.toString() }
            }
        reports.take((reports.size - maximumReports).coerceAtLeast(0)).forEach { report ->
            Files.deleteIfExists(report)
        }
    }

    private fun retention(severity: DiagnosticSeverity): Int =
        when (severity) {
            DiagnosticSeverity.WARNING -> warningRetention
            DiagnosticSeverity.ERROR -> errorRetention
        }

    private fun render(snapshot: DiagnosticReportSnapshot): String {
        val output = StringBuilder()
        output.appendLine("format-version=1")
        output.appendLine("report-id=${snapshot.reportId}")
        output.appendLine("timestamp=${snapshot.timestamp}")
        output.appendLine("severity=${snapshot.severity.folderName}")
        output.appendLine("message=${escape(snapshot.message)}")
        appendSection(output, "runtime", snapshot.metadata)
        appendSection(output, "context", snapshot.fields)
        output.appendLine()
        output.appendLine("[call-stack]")
        output.appendLine(snapshot.callStack.take(MAX_STACK_TRACE_LENGTH))
        snapshot.cause?.let { cause ->
            output.appendLine()
            output.appendLine("[cause]")
            output.appendLine(cause.take(MAX_STACK_TRACE_LENGTH))
        }
        return output.toString().take(MAX_REPORT_LENGTH)
    }

    private fun appendSection(
        output: StringBuilder,
        name: String,
        values: Map<String, String>,
    ) {
        output.appendLine()
        output.appendLine("[$name]")
        values.toSortedMap().forEach { (key, value) ->
            output.appendLine("${safeKey(key)}=${escape(value)}")
        }
    }

    private fun reportFilename(
        timestamp: String,
        reportId: Long,
        message: String,
    ): String {
        val slug =
            message
                .lowercase(Locale.ROOT)
                .replace(NON_SLUG_CHARACTERS, "-")
                .trim('-')
                .take(MAX_FILENAME_SLUG_LENGTH)
                .ifEmpty { "diagnostic" }
        return "$timestamp-${reportId.toString().padStart(REPORT_ID_WIDTH, '0')}-$slug.txt"
    }

    private fun captureCallStack(): String =
        Thread
            .currentThread()
            .stackTrace
            .dropWhile { frame -> frame.className == Thread::class.java.name }
            .joinToString(System.lineSeparator()) { frame -> "\tat $frame" }

    private fun safeKey(value: String): String =
        value
            .replace(NON_FIELD_KEY_CHARACTERS, "-")
            .trim('-')
            .take(MAX_FIELD_KEY_LENGTH)
            .ifEmpty { "field" }

    private fun escape(value: String): String =
        value
            .take(MAX_FIELD_VALUE_LENGTH)
            .replace("\\", "\\\\")
            .replace("\r", "\\r")
            .replace("\n", "\\n")
            .replace("\t", "\\t")

    private data class DiagnosticReportSnapshot(
        val timestamp: String,
        val reportId: Long,
        val severity: DiagnosticSeverity,
        val message: String,
        val metadata: Map<String, String>,
        val fields: Map<String, String>,
        val callStack: String,
        val cause: String?,
    )

    private object DiagnosticThreadFactory : ThreadFactory {
        override fun newThread(runnable: Runnable): Thread =
            Thread(runnable, "ItemDropV2-Diagnostic-Writer").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
    }

    private companion object {
        private val NON_SLUG_CHARACTERS = Regex("[^a-z0-9]+")
        private val NON_FIELD_KEY_CHARACTERS = Regex("[^A-Za-z0-9_.-]+")
        private const val REPORTS_DIRECTORY = "reports"
        private const val REPORT_QUEUE_CAPACITY = 256
        private const val REPORT_SHUTDOWN_SECONDS = 2L
        private const val DEFAULT_WARNING_RETENTION = 100
        private const val DEFAULT_ERROR_RETENTION = 50
        private const val MAX_FILENAME_SLUG_LENGTH = 60
        private const val REPORT_ID_WIDTH = 6
        private const val MAX_FIELD_KEY_LENGTH = 100
        private const val MAX_FIELD_VALUE_LENGTH = 8_192
        private const val MAX_STACK_TRACE_LENGTH = 65_536
        private const val MAX_REPORT_LENGTH = 262_144
    }
}

private fun Throwable.stackTraceText(): String =
    StringWriter().use { output ->
        PrintWriter(output).use(::printStackTrace)
        output.toString()
    }

internal fun diagnosticReportFailureSink(logger: Logger): (String, Throwable?) -> Unit {
    val consoleLog = ConsoleLogEmitter(logger)
    return { message, cause ->
        consoleLog.log(
            Level.SEVERE,
            "ItemDropV2 diagnostic report system failed: ${message.sanitizeForConsole()}",
            cause,
        )
    }
}

internal fun String.sanitizeForConsole(): String = replace(UNSAFE_LOG_CHARACTERS, " ").take(MAX_LOG_VALUE_LENGTH)

private val UNSAFE_LOG_CHARACTERS = Regex("[\\r\\n\\t]")
private const val MAX_LOG_VALUE_LENGTH = 500
