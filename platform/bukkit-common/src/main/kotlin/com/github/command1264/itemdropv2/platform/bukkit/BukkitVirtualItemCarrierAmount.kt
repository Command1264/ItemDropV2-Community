package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.VirtualItemAmount
import com.github.command1264.itemdropv2.core.VirtualItemStackingSettings
import com.github.command1264.itemdropv2.core.VirtualStackingRuntimeMode
import com.github.command1264.itemdropv2.core.VirtualStackingRuntimeModeResolver
import org.bukkit.entity.Item

/**
 * Entity ItemStack amount 只負責 client 掉落密度與原生 event carrier；PDC virtual
 * amount 仍是唯一權威數量。所有模式都保證 carrier 不超過 virtual amount。
 */
public fun Item.applyVirtualCarrierAmount(
    virtualAmount: VirtualItemAmount,
    settings: VirtualItemStackingSettings,
    modeResolver: VirtualStackingRuntimeModeResolver,
) {
    val stack = itemStack
    val carrierAmount =
        when (modeResolver.carrierMode(settings, stack.maxStackSize, virtualAmount)) {
            VirtualStackingRuntimeMode.ACTIVE -> settings.carrierAmountFor(virtualAmount, stack.maxStackSize)
            VirtualStackingRuntimeMode.LEGACY_DRAIN -> minOf(stack.amount.toLong(), virtualAmount.value).toInt()
        }
    if (stack.amount != carrierAmount) {
        setItemStack(stack.clone().apply { amount = carrierAmount })
    }
}
