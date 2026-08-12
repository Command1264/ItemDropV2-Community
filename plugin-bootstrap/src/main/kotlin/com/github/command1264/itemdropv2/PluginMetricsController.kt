package com.github.command1264.itemdropv2

internal const val BSTATS_PLUGIN_ID = 32797

internal fun interface MetricsSession {
    fun shutdown()
}

@Suppress("TooGenericExceptionCaught")
internal class PluginMetricsController(
    private val createSession: () -> MetricsSession,
    private val warningSink: (String, Throwable) -> Unit,
) : AutoCloseable {
    private var activeSession: MetricsSession? = null

    fun start(): Boolean {
        if (activeSession != null) {
            return true
        }
        return try {
            activeSession = createSession()
            true
        } catch (error: RuntimeException) {
            warningSink("bStats metrics startup failed (${error.javaClass.simpleName}).", error)
            false
        } catch (error: LinkageError) {
            warningSink("bStats metrics startup failed (${error.javaClass.simpleName}).", error)
            false
        }
    }

    override fun close() {
        val session = activeSession ?: return
        activeSession = null
        try {
            session.shutdown()
        } catch (error: RuntimeException) {
            warningSink("bStats metrics shutdown failed (${error.javaClass.simpleName}).", error)
        } catch (error: LinkageError) {
            warningSink("bStats metrics shutdown failed (${error.javaClass.simpleName}).", error)
        }
    }
}
