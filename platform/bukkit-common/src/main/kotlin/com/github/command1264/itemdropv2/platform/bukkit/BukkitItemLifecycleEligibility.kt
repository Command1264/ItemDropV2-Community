package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.entity.Item

/**
 * Minecraft uses the maximum signed-short pickup delay for Item entities that must never be picked up.
 * This includes transient command feedback and intentionally decorative Item entities. ItemDropV2 must
 * not turn those entities into managed lifetime carriers.
 */
internal fun Item.isItemDropLifecycleEligible(): Boolean = pickupDelay < NEVER_PICKUP_DELAY

private const val NEVER_PICKUP_DELAY = Short.MAX_VALUE.toInt()
