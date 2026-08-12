package com.github.command1264.itemdropv2

import com.github.command1264.itemdropv2.platform.bukkit.ItemDropV2RuntimeReadiness

internal class MutableItemDropV2RuntimeReadiness : ItemDropV2RuntimeReadiness {
    @Volatile
    private var ready: Boolean = false

    override fun isReady(): Boolean = ready

    fun markReady() {
        ready = true
    }

    fun markNotReady() {
        ready = false
    }
}
