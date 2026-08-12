package com.github.command1264.itemdropv2.core

import java.util.UUID

/**
 * Main-thread timing wheel。
 *
 * 低於 soft threshold 時，Entity 註冊到前一個已處理相位，確保完整經過 [SLOT_COUNT]
 * 次 [advance] 才首次到期。超過門檻時才選擇負載最低、等待時間最接近完整一圈的 slot。
 * Assignment 完成後不再移動，因此其後固定每 [SLOT_COUNT] ticks 處理一次。
 */
public class ItemProcessingWheel(
    private val maximumItemsPerTick: () -> Int = { ItemProcessingSettings.DEFAULT_MAXIMUM_ITEMS_PER_TICK },
) {
    private val slots: List<LinkedHashSet<UUID>> = List(SLOT_COUNT) { linkedSetOf() }
    private val assignments = mutableMapOf<UUID, Int>()
    private var nextSlotToProcess = 0

    public fun register(entityId: UUID): Int =
        assignments[entityId] ?: adaptiveRegistrationSlot().also { slot ->
            slots[slot].add(entityId)
            assignments[entityId] = slot
        }

    public fun forget(entityId: UUID) {
        assignments.remove(entityId)?.let { slot -> slots[slot].remove(entityId) }
    }

    public fun advance(): List<UUID> {
        val due = slots[nextSlotToProcess].toList()
        nextSlotToProcess = (nextSlotToProcess + 1) % SLOT_COUNT
        return due
    }

    public fun trackedEntityIds(): Set<UUID> = assignments.keys.toSet()

    public fun slotLoads(): List<Int> = slots.map(Set<UUID>::size)

    public fun clear() {
        slots.forEach(MutableSet<UUID>::clear)
        assignments.clear()
    }

    private fun adaptiveRegistrationSlot(): Int {
        val alignedSlot = (nextSlotToProcess + SLOT_COUNT - 1) % SLOT_COUNT
        val maximumAlignedLoad = maximumItemsPerTick()
        require(maximumAlignedLoad > 0) { "maximum items per tick must be positive" }
        if (slots[alignedSlot].size < maximumAlignedLoad) return alignedSlot

        val minimumLoad = slots.minOf(Set<UUID>::size)
        repeat(SLOT_COUNT) { distanceFromAligned ->
            val candidate = (alignedSlot - distanceFromAligned + SLOT_COUNT) % SLOT_COUNT
            if (slots[candidate].size == minimumLoad) return candidate
        }
        error("timing wheel has no slot")
    }

    public companion object {
        public const val SLOT_COUNT: Int = 20
    }
}
