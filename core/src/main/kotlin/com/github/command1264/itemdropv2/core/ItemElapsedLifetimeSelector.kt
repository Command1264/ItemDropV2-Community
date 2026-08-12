package com.github.command1264.itemdropv2.core

internal object ItemElapsedLifetimeSelector {
    fun select(
        strategy: MergeLifetimeStrategy,
        source: Long,
        target: Long,
    ): Long =
        when (strategy) {
            MergeLifetimeStrategy.AVERAGE -> roundedAverage(source, target)
            MergeLifetimeStrategy.MAXIMUM -> minOf(source, target)
            MergeLifetimeStrategy.MINIMUM -> maxOf(source, target)
        }

    private fun roundedAverage(
        first: Long,
        second: Long,
    ): Long = first / 2 + second / 2 + (first % 2 + second % 2 + 1) / 2
}
