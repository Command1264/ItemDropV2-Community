package com.github.command1264.itemdropv2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class RuntimeWarningLimiterTest {
    @Test
    fun `one saturated diagnostic does not suppress another diagnostic`() {
        val limiter = RuntimeWarningLimiter(maximumWarningsPerKey = 2)

        assertEquals("LIFETIME-MISSING: first", limiter.accept("LIFETIME-MISSING: first"))
        assertEquals("LIFETIME-MISSING: second", limiter.accept("LIFETIME-MISSING: second"))
        assertEquals(
            "LIFETIME-MISSING: additional warnings of this type are suppressed until reload",
            limiter.accept("LIFETIME-MISSING: third"),
        )
        assertNull(limiter.accept("LIFETIME-MISSING: fourth"))
        assertEquals("LANGUAGE-LOAD: first", limiter.accept("LANGUAGE-LOAD: first"))
    }

    @Test
    fun `reset restores every diagnostic allowance`() {
        val limiter = RuntimeWarningLimiter(maximumWarningsPerKey = 1)
        limiter.accept("LIFETIME-MISSING: first")
        limiter.accept("LIFETIME-MISSING: second")

        limiter.reset()

        assertEquals("LIFETIME-MISSING: after reload", limiter.accept("LIFETIME-MISSING: after reload"))
    }
}
