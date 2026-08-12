package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentOutcome
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentRequest
import com.github.command1264.itemdropv2.core.ItemOwnershipAssignmentService
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDropItemEvent
import org.bukkit.event.player.PlayerShearEntityEvent
import java.util.UUID

public class BukkitShearingDropOwnershipController internal constructor(
    private val assignmentService: ItemOwnershipAssignmentService,
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val itemRefresh: ItemOwnershipRefresh,
    private val transientTargetLeaseFactory: TransientItemTargetLeaseFactory =
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
    private val contexts: ShearingContextTracker = ShearingContextTracker(),
) : Listener,
    AutoCloseable {
    public constructor(
        assignmentService: ItemOwnershipAssignmentService,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
    ) : this(
        assignmentService,
        delayedTaskExecutor,
        warningSink,
        itemRefresh,
        TransientItemTargetLeaseFactory { TransientItemTargetLease {} },
        ShearingContextTracker(),
    )

    public constructor(
        assignmentService: ItemOwnershipAssignmentService,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
        itemRefresh: ItemOwnershipRefresh,
        transientTargetLeaseFactory: TransientItemTargetLeaseFactory,
    ) : this(
        assignmentService,
        delayedTaskExecutor,
        warningSink,
        itemRefresh,
        transientTargetLeaseFactory,
        ShearingContextTracker(),
    )

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onPlayerShear(event: PlayerShearEntityEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "PlayerShearEntityEvent") || event.isCancelled) return
        val entity = event.entity
        val contextId = contexts.record(entity.uniqueId, entity.world.name, event.player.uniqueId)
        scheduleExpiry(entity.uniqueId, contextId)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onEntityDropItem(event: EntityDropItemEvent) {
        if (!ensureSynchronous(event.isAsynchronous, "EntityDropItemEvent") || event.isCancelled) return
        val source = event.entity
        val item = event.itemDrop
        contexts.claim(source.uniqueId, source.world.name, item.world.name)?.let { ownerUuid ->
            assignItemOwnershipImmediately(
                item,
                listOf(ownerUuid),
                assignmentService,
                transientTargetLeaseFactory,
                itemRefresh,
                warningSink,
                "shearing",
            )
        }
    }

    override fun close() {
        contexts.clear()
    }

    private fun ensureSynchronous(
        asynchronous: Boolean,
        eventName: String,
    ): Boolean {
        if (!asynchronous) return true
        warningSink.warn("ignored asynchronous $eventName")
        return false
    }

    @Suppress("TooGenericExceptionCaught")
    private fun scheduleExpiry(
        entityId: UUID,
        contextId: Long,
    ) {
        try {
            delayedTaskExecutor.execute(CONTEXT_EXPIRY_DELAY_TICKS) { contexts.expire(entityId, contextId) }
        } catch (error: RuntimeException) {
            contexts.expire(entityId, contextId)
            warningSink.warn("shearing context expiry scheduling failed (${error.javaClass.simpleName})")
        }
    }

    private companion object {
        private const val CONTEXT_EXPIRY_DELAY_TICKS = 1L
    }
}

internal class ShearingContextTracker {
    private val contexts = linkedMapOf<UUID, ShearingContext>()
    private var nextContextId = 1L

    fun record(
        entityId: UUID,
        worldName: String,
        ownerUuid: UUID,
    ): Long {
        val contextId = nextContextId++
        contexts.remove(entityId)
        contexts[entityId] = ShearingContext(contextId, worldName, ownerUuid, MAX_DROPS_PER_CONTEXT)
        while (contexts.size > MAX_CONTEXTS) {
            contexts.remove(contexts.keys.first())
        }
        return contextId
    }

    fun claim(
        entityId: UUID,
        sourceWorldName: String,
        itemWorldName: String,
    ): UUID? {
        val context = contexts[entityId]
        return if (context != null && context.worldName == sourceWorldName && context.worldName == itemWorldName) {
            context.remainingDrops--
            if (context.remainingDrops == 0) contexts.remove(entityId)
            context.ownerUuid
        } else {
            null
        }
    }

    fun expire(
        entityId: UUID,
        contextId: Long,
    ) {
        if (contexts[entityId]?.id == contextId) contexts.remove(entityId)
    }

    fun clear() {
        contexts.clear()
    }

    private companion object {
        private const val MAX_CONTEXTS = 1_024
        private const val MAX_DROPS_PER_CONTEXT = 64
    }
}

private data class ShearingContext(
    val id: Long,
    val worldName: String,
    val ownerUuid: UUID,
    var remainingDrops: Int,
)
