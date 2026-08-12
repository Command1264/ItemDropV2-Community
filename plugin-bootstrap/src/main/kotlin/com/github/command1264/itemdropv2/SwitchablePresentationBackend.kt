package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.core.DirectItemPickupFeedbackView
import com.github.command1264.itemdropv2.core.DirectItemPresentationJournalView
import com.github.command1264.itemdropv2.core.DirectItemPresentationView
import com.github.command1264.itemdropv2.core.ItemPickupFeedbackResult
import com.github.command1264.itemdropv2.core.ItemPresentation
import com.github.command1264.itemdropv2.core.ItemStateJournalPresentation
import com.github.command1264.itemdropv2.core.PresentationBackend
import com.github.command1264.itemdropv2.core.PresentationJournalSnapshotResult
import com.github.command1264.itemdropv2.core.PresentationResult
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import java.util.concurrent.atomic.AtomicReference

/** Stable controller-facing facade whose delegate is replaced only by a committed config transaction. */
@Suppress("UNCHECKED_CAST", "TooManyFunctions")
internal class SwitchablePresentationBackend(
    initial: PresentationBackend,
) : PresentationBackend,
    DirectItemPresentationView<Item>,
    DirectItemPickupFeedbackView<Player, Item>,
    DirectItemPresentationJournalView<Item> {
    private val current = AtomicReference(initial)

    override val id: String
        get() = current.get().id

    override fun activate() = current.get().activate()

    override fun present(presentation: ItemPresentation): PresentationResult = current.get().present(presentation)

    override fun present(
        target: Item,
        presentation: ItemPresentation,
    ): PresentationResult = directView().present(target, presentation)

    override fun clear(target: Item): PresentationResult = directView().clear(target)

    override fun playPickupFeedback(
        actor: Player,
        target: Item,
        pickedUpAmount: Int,
        carrierRemains: Boolean,
        carrierResynchronizationSupported: Boolean,
    ): ItemPickupFeedbackResult =
        pickupView().playPickupFeedback(
            actor,
            target,
            pickedUpAmount,
            carrierRemains,
            carrierResynchronizationSupported,
        )

    override fun capture(target: Item): PresentationJournalSnapshotResult = journalView().capture(target)

    override fun restore(
        target: Item,
        snapshot: ItemStateJournalPresentation,
    ): PresentationJournalSnapshotResult = journalView().restore(target, snapshot)

    fun prepare(
        candidate: PresentationBackend,
        retirementFailureSink: (Throwable) -> Unit = { throw it },
    ): BackendSwitchTransaction = BackendSwitchTransaction(this, candidate, retirementFailureSink)

    override fun close() = current.get().close()

    private fun directView(): DirectItemPresentationView<Item> =
        current.get() as? DirectItemPresentationView<Item>
            ?: error("selected backend does not support direct item presentation")

    private fun pickupView(): DirectItemPickupFeedbackView<Player, Item> =
        current.get() as? DirectItemPickupFeedbackView<Player, Item>
            ?: error("selected backend does not support direct pickup feedback")

    private fun journalView(): DirectItemPresentationJournalView<Item> =
        current.get() as? DirectItemPresentationJournalView<Item>
            ?: error("selected backend does not support presentation journal snapshots")

    internal class BackendSwitchTransaction(
        private val owner: SwitchablePresentationBackend,
        private val candidate: PresentationBackend,
        private val retirementFailureSink: (Throwable) -> Unit,
    ) {
        private var previous: PresentationBackend? = null
        private var committed = false
        private var candidateClosed = false

        @Suppress("TooGenericExceptionCaught")
        fun commit(): String? =
            try {
                candidate.activate()
                previous = owner.current.getAndSet(candidate)
                committed = true
                null
            } catch (error: RuntimeException) {
                closeCandidateAfterFailure(error)
                "presentation backend activation failed (${error.javaClass.simpleName})"
            } catch (error: LinkageError) {
                closeCandidateAfterFailure(error)
                "presentation backend linkage failed (${error.javaClass.simpleName})"
            }

        fun rollback() {
            if (committed) {
                owner.current.set(requireNotNull(previous))
                closeCandidate()
                committed = false
            } else if (!candidateClosed) {
                closeCandidate()
            }
        }

        @Suppress("TooGenericExceptionCaught")
        fun complete() {
            if (committed) {
                try {
                    requireNotNull(previous).close()
                } catch (error: RuntimeException) {
                    retirementFailureSink(error)
                } catch (error: LinkageError) {
                    retirementFailureSink(error)
                }
                previous = null
                committed = false
            }
        }

        @Suppress("TooGenericExceptionCaught")
        private fun closeCandidateAfterFailure(failure: Throwable) {
            try {
                closeCandidate()
            } catch (closeFailure: RuntimeException) {
                failure.addSuppressed(closeFailure)
            } catch (closeFailure: LinkageError) {
                failure.addSuppressed(closeFailure)
            }
        }

        private fun closeCandidate() {
            candidate.close()
            candidateClosed = true
        }
    }
}
