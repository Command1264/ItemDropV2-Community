package com.github.command1264.itemdropv2

internal class RuntimeWarningLimiter(
    private val maximumWarningsPerKey: Int,
) {
    private val warningCounts = mutableMapOf<String, Int>()

    init {
        require(maximumWarningsPerKey > 0) { "maximumWarningsPerKey must be positive" }
    }

    fun accept(message: String): String? {
        val key = diagnosticKey(message)
        val count = warningCounts.getOrDefault(key, 0)
        warningCounts[key] = count + 1
        return when {
            count < maximumWarningsPerKey -> message
            count == maximumWarningsPerKey ->
                "$key: additional warnings of this type are suppressed until reload"
            else -> null
        }
    }

    fun reset() {
        warningCounts.clear()
    }

    private fun diagnosticKey(message: String): String =
        message
            .substringBefore(':')
            .substringBefore(" (")
            .trim()
            .ifEmpty { UNKNOWN_WARNING_KEY }

    private companion object {
        private const val UNKNOWN_WARNING_KEY = "UNCLASSIFIED-RUNTIME-WARNING"
    }
}
