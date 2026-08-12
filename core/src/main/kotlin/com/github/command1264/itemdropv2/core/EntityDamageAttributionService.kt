package com.github.command1264.itemdropv2.core

import java.util.UUID
import kotlin.math.abs

private const val MAX_DAMAGE_PERCENT = 100.0

public enum class EntityDamageAttributionStrategy(
    public val configValue: String,
) {
    HIGHEST_DAMAGE("highest-damage"),
    FIRST_HIT("first-hit"),
    FINAL_HIT("final-hit"),
    ;

    public companion object {
        public fun parse(value: String): EntityDamageAttributionStrategy? =
            entries.firstOrNull { strategy -> strategy.configValue == value.lowercase() }
    }
}

public data class EntityDamageContribution(
    public val playerUuid: UUID,
    public val effectiveDamage: Double,
    public val firstHitSequence: Long,
    public val lastHitSequence: Long,
) {
    init {
        require(effectiveDamage.isFinite() && effectiveDamage > 0.0) { "effective damage must be finite and positive" }
        require(firstHitSequence >= 0) { "first hit sequence must not be negative" }
        require(lastHitSequence >= firstHitSequence) { "last hit sequence must not precede first hit sequence" }
    }
}

public data class EntityDamageAttributionRequest(
    public val contributions: List<EntityDamageContribution>,
    public val strategy: EntityDamageAttributionStrategy,
    public val maximumHealth: Double,
    public val minimumContributionPercent: Double,
) {
    init {
        require(contributions.map { it.playerUuid }.distinct().size == contributions.size) {
            "damage contributions must contain unique players"
        }
        require(maximumHealth.isFinite() && maximumHealth > 0.0) { "maximum health must be finite and positive" }
        require(minimumContributionPercent.isFinite() && minimumContributionPercent in 0.0..MAX_DAMAGE_PERCENT) {
            "minimum contribution percent must be between 0 and 100"
        }
    }
}

public sealed interface EntityDamageAttributionResult {
    public data class Attributed(
        public val eligibleOwnerUuids: List<UUID>,
    ) : EntityDamageAttributionResult {
        init {
            require(eligibleOwnerUuids.isNotEmpty()) { "attribution must contain at least one owner" }
            require(eligibleOwnerUuids.size <= ItemOwnership.MAX_ELIGIBLE_OWNERS) {
                "attribution exceeds supported owner count"
            }
        }
    }

    public data object NoContribution : EntityDamageAttributionResult

    public data object BelowMinimumContribution : EntityDamageAttributionResult
}

public class EntityDamageAttributionService {
    public fun attribute(request: EntityDamageAttributionRequest): EntityDamageAttributionResult =
        if (request.contributions.isEmpty()) {
            EntityDamageAttributionResult.NoContribution
        } else {
            val requiredDamage = request.maximumHealth * request.minimumContributionPercent / MAX_DAMAGE_PERCENT
            val eligible =
                select(request)
                    .filter { contribution -> contribution.effectiveDamage + DAMAGE_EPSILON >= requiredDamage }
                    .sortedBy(EntityDamageContribution::firstHitSequence)
                    .take(ItemOwnership.MAX_ELIGIBLE_OWNERS)
                    .map(EntityDamageContribution::playerUuid)
            eligible
                .takeIf(List<UUID>::isNotEmpty)
                ?.let(EntityDamageAttributionResult::Attributed)
                ?: EntityDamageAttributionResult.BelowMinimumContribution
        }

    private fun select(request: EntityDamageAttributionRequest): List<EntityDamageContribution> =
        when (request.strategy) {
            EntityDamageAttributionStrategy.FIRST_HIT -> listOf(request.contributions.minBy { it.firstHitSequence })
            EntityDamageAttributionStrategy.FINAL_HIT -> listOf(request.contributions.maxBy { it.lastHitSequence })
            EntityDamageAttributionStrategy.HIGHEST_DAMAGE -> {
                val maximum = request.contributions.maxOf { it.effectiveDamage }
                request.contributions.filter { contribution -> abs(contribution.effectiveDamage - maximum) <= DAMAGE_EPSILON }
            }
        }

    private companion object {
        private const val DAMAGE_EPSILON = 1.0e-9
    }
}
