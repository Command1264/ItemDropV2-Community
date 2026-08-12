package com.github.command1264.itemdropv2

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Emits each semantic console section as its own LogRecord so Bukkit can prefix every line.
 */
internal class ConsoleLogEmitter(
    private val logger: Logger,
) {
    fun info(message: String) {
        log(Level.INFO, message)
    }

    fun log(
        level: Level,
        message: String,
        cause: Throwable? = null,
    ) {
        sections(message).forEachIndexed { index, section ->
            if (index == 0 && cause != null) {
                logger.log(level, section, cause)
            } else {
                logger.log(level, section)
            }
        }
    }

    private fun sections(message: String): List<String> {
        val sections =
            message
                .split(';', '；')
                .map(String::trim)
                .filter(String::isNotEmpty)
        return sections.ifEmpty { listOf("") }
    }
}
