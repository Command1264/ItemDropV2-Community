package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.core.ItemPresentation
import com.github.command1264.itemdropv2.core.PresentationBackend
import com.github.command1264.itemdropv2.core.PresentationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SwitchablePresentationBackendTest {
    @Test
    fun `committed switch is visible immediately and retires old backend only after completion`() {
        val old = FakeBackend("old")
        val candidate = FakeBackend("new")
        val facade = SwitchablePresentationBackend(old)
        val transaction = facade.prepare(candidate)

        assertEquals(null, transaction.commit())
        assertEquals("new", facade.id)
        assertEquals(false, old.closed)

        transaction.complete()

        assertEquals(true, old.closed)
        assertEquals(false, candidate.closed)
    }

    @Test
    fun `rollback restores old backend and closes rejected candidate`() {
        val old = FakeBackend("old")
        val candidate = FakeBackend("new")
        val facade = SwitchablePresentationBackend(old)
        val transaction = facade.prepare(candidate)

        assertEquals(null, transaction.commit())
        transaction.rollback()

        assertEquals("old", facade.id)
        assertEquals(false, old.closed)
        assertEquals(true, candidate.closed)
    }

    @Test
    fun `activation failure keeps old backend and closes candidate`() {
        val old = FakeBackend("old")
        val candidate = FakeBackend("new", activationFailure = IllegalStateException("injected"))
        val facade = SwitchablePresentationBackend(old)

        assertEquals("presentation backend activation failed (IllegalStateException)", facade.prepare(candidate).commit())
        assertEquals("old", facade.id)
        assertEquals(true, candidate.closed)
    }

    @Test
    fun `retirement failure is reported without reverting the committed delegate`() {
        val failure = IllegalStateException("injected close failure")
        val old = FakeBackend("old", closeFailure = failure)
        val candidate = FakeBackend("new")
        val facade = SwitchablePresentationBackend(old)
        var reported: Throwable? = null
        val transaction = facade.prepare(candidate) { error -> reported = error }

        assertEquals(null, transaction.commit())
        transaction.complete()

        assertEquals("new", facade.id)
        assertEquals(failure, reported)
    }

    private class FakeBackend(
        override val id: String,
        private val activationFailure: RuntimeException? = null,
        private val closeFailure: RuntimeException? = null,
    ) : PresentationBackend {
        var closed: Boolean = false
            private set

        override fun activate() {
            activationFailure?.let { throw it }
        }

        override fun present(presentation: ItemPresentation): PresentationResult = PresentationResult.Applied

        override fun close() {
            closeFailure?.let { throw it }
            closed = true
        }
    }
}
