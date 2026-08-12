package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.EntityDamageContribution
import java.util.UUID
import kotlin.math.min

internal class EntityDamageLedger {
    private val combats = linkedMapOf<UUID, Combat>()
    private var sequence = 0L

    fun record(
        entityUuid: UUID,
        playerUuid: UUID,
        finalDamage: Double,
        healthBeforeDamage: Double,
        currentTick: Long,
        timeoutTicks: Long,
    ) {
        require(timeoutTicks > 0) { "combat timeout ticks must be positive" }
        if (finalDamage.isFinite() && healthBeforeDamage.isFinite()) {
            val effectiveDamage = min(finalDamage, healthBeforeDamage)
            if (effectiveDamage > 0.0) recordEffectiveDamage(entityUuid, playerUuid, effectiveDamage, currentTick)
        }
    }

    private fun recordEffectiveDamage(
        entityUuid: UUID,
        playerUuid: UUID,
        effectiveDamage: Double,
        currentTick: Long,
    ) {
        val combat =
            combats[entityUuid] ?: run {
                evictOldestCombatAtCapacity()
                Combat(currentTick).also { combats[entityUuid] = it }
            }
        val previous = combat.contributions[playerUuid]
        if (previous != null || combat.contributions.size < MAX_CONTRIBUTORS_PER_ENTITY) {
            val currentSequence = sequence++
            combat.contributions[playerUuid] =
                if (previous == null) {
                    MutableContribution(effectiveDamage, currentSequence, currentSequence)
                } else {
                    previous.copy(effectiveDamage = previous.effectiveDamage + effectiveDamage, lastHitSequence = currentSequence)
                }
            combat.lastDamageTick = currentTick
        }
    }

    fun consume(
        entityUuid: UUID,
        currentTick: Long,
        timeoutTicks: Long,
    ): List<EntityDamageContribution>? = read(entityUuid, currentTick, timeoutTicks, remove = true)

    fun peek(
        entityUuid: UUID,
        currentTick: Long,
        timeoutTicks: Long,
    ): List<EntityDamageContribution>? = read(entityUuid, currentTick, timeoutTicks, remove = false)

    private fun read(
        entityUuid: UUID,
        currentTick: Long,
        timeoutTicks: Long,
        remove: Boolean,
    ): List<EntityDamageContribution>? {
        require(timeoutTicks > 0) { "combat timeout ticks must be positive" }
        val combat = combats[entityUuid]
        return if (combat == null || currentTick - combat.lastDamageTick >= timeoutTicks) {
            combats.remove(entityUuid)
            null
        } else {
            if (remove) combats.remove(entityUuid)
            combat.toContributions()
        }
    }

    fun discard(entityUuid: UUID) {
        combats.remove(entityUuid)
    }

    fun purgeExpired(
        currentTick: Long,
        timeoutTicks: Long,
    ) {
        require(timeoutTicks > 0) { "combat timeout ticks must be positive" }
        combats.entries.removeIf { (_, combat) -> currentTick - combat.lastDamageTick >= timeoutTicks }
    }

    fun clear() {
        combats.clear()
    }

    private fun evictOldestCombatAtCapacity() {
        if (combats.size < MAX_TRACKED_ENTITIES) return
        combats.entries
            .minByOrNull { (_, combat) -> combat.lastDamageTick }
            ?.key
            ?.let(combats::remove)
    }

    private data class Combat(
        var lastDamageTick: Long,
        val contributions: LinkedHashMap<UUID, MutableContribution> = linkedMapOf(),
    ) {
        fun toContributions(): List<EntityDamageContribution> =
            contributions.map { (playerUuid, contribution) ->
                EntityDamageContribution(
                    playerUuid,
                    contribution.effectiveDamage,
                    contribution.firstHitSequence,
                    contribution.lastHitSequence,
                )
            }
    }

    private data class MutableContribution(
        val effectiveDamage: Double,
        val firstHitSequence: Long,
        val lastHitSequence: Long,
    )

    private companion object {
        private const val MAX_TRACKED_ENTITIES = 10_000
        private const val MAX_CONTRIBUTORS_PER_ENTITY = 64
    }
}
