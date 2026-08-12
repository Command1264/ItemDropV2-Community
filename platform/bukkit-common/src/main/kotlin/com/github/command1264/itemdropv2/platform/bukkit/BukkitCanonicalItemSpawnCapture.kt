package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.Bukkit
import org.bukkit.entity.Item
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.ItemSpawnEvent
import java.util.ArrayDeque

public fun interface CanonicalItemSpawnCapture {
    public fun capture(operation: () -> Item): Item

    public fun capture(
        operation: () -> Item,
        beforePublication: (Item) -> Unit,
    ): Item = capture(operation).also(beforePublication)
}

internal object DirectItemSpawnCapture : CanonicalItemSpawnCapture {
    override fun capture(operation: () -> Item): Item = operation()
}

/**
 * Returns the Item exposed by the synchronous spawn event instead of trusting the API return
 * wrapper. CraftBukkit versions affected by SPIGOT-5707 can return a second wrapper whose PDC is
 * disconnected from the entity that the server subsequently publishes and picks up.
 */
public class BukkitCanonicalItemSpawnCapture internal constructor(
    private val primaryThreadCheck: () -> Boolean,
) : Listener,
    CanonicalItemSpawnCapture {
    public constructor() : this(Bukkit::isPrimaryThread)

    private val activeCaptures = ArrayDeque<ActiveCapture>()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public fun onItemSpawn(event: ItemSpawnEvent) {
        activeCaptures.forEach { capture -> capture.capturedItems += event.entity }
        activeCaptures.peekLast()?.prepare(event.entity)
    }

    override fun capture(operation: () -> Item): Item = capture(operation) {}

    override fun capture(
        operation: () -> Item,
        beforePublication: (Item) -> Unit,
    ): Item {
        check(primaryThreadCheck()) { "canonical Item spawn capture requires the server main thread" }
        val captured = ActiveCapture(beforePublication)
        activeCaptures.addLast(captured)
        val returned =
            try {
                operation()
            } finally {
                check(activeCaptures.removeLast() === captured) { "canonical Item spawn capture stack is corrupted" }
            }
        val canonical =
            captured.capturedItems.firstOrNull { item -> item.uniqueId == returned.uniqueId }
                ?: returned.world.entities
                    .asSequence()
                    .filterIsInstance<Item>()
                    .firstOrNull { item -> item.uniqueId == returned.uniqueId }
        if (canonical == null) {
            returned.remove()
            error("Item spawn did not expose a non-cancelled canonical event target")
        }
        captured.prepare(canonical)
        return canonical
    }

    private class ActiveCapture(
        private val beforePublication: (Item) -> Unit,
    ) {
        val capturedItems: MutableList<Item> = mutableListOf()
        private val preparedEntityIds: MutableSet<java.util.UUID> = mutableSetOf()

        fun prepare(item: Item) {
            if (preparedEntityIds.add(item.uniqueId)) beforePublication(item)
        }
    }
}
