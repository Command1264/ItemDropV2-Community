package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.DirectItemPresentationView
import com.github.command1264.itemdropv2.core.ItemPresentation
import com.github.command1264.itemdropv2.core.ItemPresentationView
import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.PresentationResult
import com.github.command1264.itemdropv2.platform.bukkit.resolveBukkitItem
import org.bukkit.entity.Item
import java.util.UUID

/** Keeps the recovery journal's display snapshot aligned with successful Bukkit presentation changes. */
public class BukkitJournalAwareItemPresentationView(
    private val view: ItemPresentationView,
    private val directView: DirectItemPresentationView<Item>,
    private val presentationApplied: (Item) -> Unit,
    private val itemResolver: (UUID) -> Item? = ::resolveBukkitItem,
) : ItemPresentationView,
    DirectItemPresentationView<Item> {
    override fun present(presentation: ItemPresentation): PresentationResult =
        view.present(presentation).also { result ->
            if (result == PresentationResult.Applied) itemResolver(presentation.entityId)?.let(presentationApplied)
        }

    override fun clear(entityId: UUID): PresentationResult =
        view.clear(entityId).also { result ->
            if (result == PresentationResult.Applied) itemResolver(entityId)?.let(presentationApplied)
        }

    override fun present(
        target: Item,
        presentation: ItemPresentation,
    ): PresentationResult =
        directView.present(target, presentation).also { result ->
            if (result == PresentationResult.Applied) presentationApplied(target)
        }

    override fun clear(target: Item): PresentationResult =
        directView.clear(target).also { result ->
            if (result == PresentationResult.Applied) presentationApplied(target)
        }
}

/** Publishes a same-revision presentation refresh and degrades once if journal safety can no longer be guaranteed. */
public class BukkitItemStateJournalPresentationRefresher(
    private val runtime: ItemStateJournalRuntimeAccess,
    private val adapter: BukkitItemStateJournalAdapter,
    private val failureSink: (String) -> Unit,
) {
    private var degraded = false

    public fun refresh(item: Item) {
        if (!degraded) {
            val identity = ItemStateJournalIdentity(item.world.uid, item.uniqueId)
            val existing = runtime[identity]
            val state = existing?.state
            if (state != null) {
                when (val outcome = adapter.refreshPresentation(item, state, existing.revision)) {
                    is ItemStateDurabilityOutcome.Accepted -> Unit
                    is ItemStateDurabilityOutcome.Rejected -> degrade(identity, outcome.reason)
                    is ItemStateDurabilityOutcome.Failed -> degrade(identity, outcome.errorType)
                }
            }
        }
    }

    private fun degrade(
        identity: ItemStateJournalIdentity,
        reason: String,
    ) {
        degraded = true
        val safeReason = "${identity.entityUuid}:PresentationRefresh:${reason.take(MAXIMUM_REASON_LENGTH)}"
        runtime.degrade(safeReason)
        failureSink(safeReason)
    }

    private companion object {
        private const val MAXIMUM_REASON_LENGTH = 100
    }
}
