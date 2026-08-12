package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.platform.bukkit.RuntimeDiagnosticContext
import java.util.logging.Level
import java.util.logging.Logger

internal class DiagnosticLogGateway(
    logger: Logger,
    private val reports: DiagnosticReportService,
    private val warningLimiter: RuntimeWarningLimiter,
) {
    private val consoleLog = ConsoleLogEmitter(logger)

    fun runtimeWarning(
        message: String,
        context: RuntimeDiagnosticContext = RuntimeDiagnosticContext(),
    ) {
        val accepted = warningLimiter.accept(message) ?: return
        val reportPath =
            if (accepted == message) {
                reports.submit(DiagnosticSeverity.WARNING, message, context)
            } else {
                null
            }
        emit(Level.WARNING, "ItemDropV2 runtime warning: ${accepted.sanitizeForConsole()}", reportPath)
    }

    fun warning(
        message: String,
        context: RuntimeDiagnosticContext = RuntimeDiagnosticContext(),
    ) {
        val reportPath = reports.submit(DiagnosticSeverity.WARNING, message, context)
        emit(Level.WARNING, message.sanitizeForConsole(), reportPath)
    }

    fun error(
        message: String,
        context: RuntimeDiagnosticContext = RuntimeDiagnosticContext(),
    ) {
        val reportPath = reports.submit(DiagnosticSeverity.ERROR, message, context)
        emit(Level.SEVERE, message.sanitizeForConsole(), reportPath)
    }

    fun resetWarningLimiter() {
        warningLimiter.reset()
    }

    private fun emit(
        level: Level,
        message: String,
        reportPath: String?,
    ) {
        consoleLog.log(level, message)
        reportPath?.let { path ->
            consoleLog.log(level, "[diagnostic report: $path]")
        }
    }
}
