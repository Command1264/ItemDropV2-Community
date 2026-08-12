package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class EntityDamageAttributionServiceTest {
    private val service = EntityDamageAttributionService()

    @Test
    fun `highest damage returns every tied owner in first hit order`() {
        val result =
            service.attribute(
                request(
                    EntityDamageContribution(FIRST, 12.0, 1, 3),
                    EntityDamageContribution(SECOND, 12.0, 2, 4),
                ),
            )

        assertEquals(EntityDamageAttributionResult.Attributed(listOf(FIRST, SECOND)), result)
    }

    @Test
    fun `first and final hit strategies use contribution sequence`() {
        val contributions =
            listOf(
                EntityDamageContribution(FIRST, 8.0, 1, 4),
                EntityDamageContribution(SECOND, 12.0, 2, 3),
            )

        assertEquals(
            EntityDamageAttributionResult.Attributed(listOf(FIRST)),
            service.attribute(request(*contributions.toTypedArray(), strategy = EntityDamageAttributionStrategy.FIRST_HIT)),
        )
        assertEquals(
            EntityDamageAttributionResult.Attributed(listOf(FIRST)),
            service.attribute(request(*contributions.toTypedArray(), strategy = EntityDamageAttributionStrategy.FINAL_HIT)),
        )
    }

    @Test
    fun `minimum contribution is based on maximum health`() {
        assertEquals(
            EntityDamageAttributionResult.BelowMinimumContribution,
            service.attribute(
                request(
                    EntityDamageContribution(FIRST, 9.99, 1, 1),
                    maximumHealth = 20.0,
                    minimumPercent = 50.0,
                ),
            ),
        )
        assertEquals(
            EntityDamageAttributionResult.Attributed(listOf(FIRST)),
            service.attribute(
                request(
                    EntityDamageContribution(FIRST, 10.0, 1, 1),
                    maximumHealth = 20.0,
                    minimumPercent = 50.0,
                ),
            ),
        )
    }

    private fun request(
        vararg contributions: EntityDamageContribution,
        strategy: EntityDamageAttributionStrategy = EntityDamageAttributionStrategy.HIGHEST_DAMAGE,
        maximumHealth: Double = 20.0,
        minimumPercent: Double = 0.0,
    ) = EntityDamageAttributionRequest(contributions.toList(), strategy, maximumHealth, minimumPercent)

    private companion object {
        private val FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002")
    }
}
