package com.github.command1264.itemdropv2

internal inline fun registerItemMergeThenActivate(
    registerItemMerge: () -> Unit,
    activateCapability: () -> Unit,
) {
    registerItemMerge()
    activateCapability()
}
