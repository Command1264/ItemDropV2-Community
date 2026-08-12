package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BukkitOwnershipEventPriorityTest {
    @Test
    fun `item-providing ownership callbacks run at monitor priority`() {
        val callbacks =
            listOf(
                BukkitBlockDropOwnershipController::class.java to "onBlockDrop",
                BukkitBlockDropOwnershipController::class.java to "onPlantItemSpawn",
                BukkitContainerDropOwnershipController::class.java to "onBlockBreak",
                BukkitDecoratedPotDropOwnershipController::class.java to "onItemSpawn",
                BukkitEntityDropOwnershipController::class.java to "onItemSpawn",
                BukkitFallingBlockDropOwnershipController::class.java to "onFallingBlockDrop",
                BukkitFallingBlockDropOwnershipController::class.java to "onPhysicsItemSpawn",
                BukkitFishingDropOwnershipController::class.java to "onPlayerFish",
                BukkitHarvestDropOwnershipController::class.java to "onItemSpawn",
                BukkitProjectileBlockDropOwnershipController::class.java to "onItemSpawn",
                BukkitShearingDropOwnershipController::class.java to "onEntityDropItem",
                BukkitSpecialEntityDropOwnershipController::class.java to "onItemSpawn",
                BukkitVehicleDropOwnershipController::class.java to "onEntityDropItem",
                BukkitVehicleDropOwnershipController::class.java to "onItemSpawn",
            )

        callbacks.forEach { (controller, methodName) ->
            val handler =
                controller.methods
                    .single { method -> method.name == methodName }
                    .getAnnotation(EventHandler::class.java)
            assertEquals(EventPriority.MONITOR, handler.priority, "${controller.simpleName}.$methodName")
            assertEquals(true, handler.ignoreCancelled, "${controller.simpleName}.$methodName")
        }
    }
}
