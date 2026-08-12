package com.github.command1264.itemdropv2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

class ConsoleLogEmitterTest {
    @Test
    fun `each semicolon section is emitted as an independent log record`() {
        val records = mutableListOf<LogRecord>()
        val emitter = ConsoleLogEmitter(recordingLogger(records))

        emitter.info("first section; second section；third section")

        assertEquals(listOf("first section", "second section", "third section"), records.map(LogRecord::getMessage))
        assertEquals(listOf(Level.INFO, Level.INFO, Level.INFO), records.map(LogRecord::getLevel))
    }

    @Test
    fun `empty sections are ignored without dropping an entirely empty message`() {
        val records = mutableListOf<LogRecord>()
        val emitter = ConsoleLogEmitter(recordingLogger(records))

        emitter.info(" first ;;； second ")
        emitter.info(";;；")

        assertEquals(listOf("first", "second", ""), records.map(LogRecord::getMessage))
    }

    @Test
    fun `failure cause remains attached to the first split record`() {
        val records = mutableListOf<LogRecord>()
        val emitter = ConsoleLogEmitter(recordingLogger(records))
        val cause = IllegalStateException("boom")

        emitter.log(Level.SEVERE, "summary; detail", cause)

        assertSame(cause, records[0].thrown)
        assertNull(records[1].thrown)
    }

    private fun recordingLogger(records: MutableList<LogRecord>): Logger =
        Logger.getAnonymousLogger().apply {
            useParentHandlers = false
            addHandler(
                object : Handler() {
                    override fun publish(record: LogRecord) {
                        records += record
                    }

                    override fun flush() = Unit

                    override fun close() = Unit
                },
            )
        }
}
