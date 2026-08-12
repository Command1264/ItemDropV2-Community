package com.github.command1264.itemdropv2.platform.bukkit

/**
 * 依 runtime class 保存公開 API capability，避免在事件或顯示熱路徑重複掃描 methods。
 */
internal class RuntimeClassCapabilityCache<T : Any>(
    private val resolver: (Class<*>) -> T,
) {
    private val capabilities =
        object : ClassValue<T>() {
            override fun computeValue(type: Class<*>): T = resolver(type)
        }

    fun get(type: Class<*>): T = capabilities.get(type)
}
