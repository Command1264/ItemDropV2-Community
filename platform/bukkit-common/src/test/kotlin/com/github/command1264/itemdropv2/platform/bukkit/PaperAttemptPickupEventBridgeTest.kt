package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.HandlerList
import org.bukkit.event.player.PlayerEvent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class PaperAttemptPickupEventBridgeTest {
    @Test
    fun `handled attempt cancels Paper native pickup and disables fly packet`() {
        val player = proxy<Player>()
        val item = proxy<Item>()
        val event = FakeAttemptPickupEvent(player, item)
        val bridge = PaperAttemptPickupEventBridge.forEventClass(FakeAttemptPickupEvent::class.java)

        bridge.handle(event, DisplayWarningSink { error("unexpected warning: $it") }) { actualPlayer, actualItem ->
            assertSame(player, actualPlayer)
            assertSame(item, actualItem)
            true
        }

        assertTrue(event.isCancelled)
        assertFalse(event.getFlyAtPlayer())
    }

    @Test
    fun `capacity-bearing attempt stays native and keeps fly packet`() {
        val event = FakeAttemptPickupEvent(proxy(), proxy())
        val bridge = PaperAttemptPickupEventBridge.forEventClass(FakeAttemptPickupEvent::class.java)

        bridge.handle(event, DisplayWarningSink { error("unexpected warning: $it") }) { _, _ -> false }

        assertFalse(event.isCancelled)
        assertTrue(event.getFlyAtPlayer())
    }

    private class FakeAttemptPickupEvent(
        player: Player,
        private val item: Item,
    ) : PlayerEvent(player),
        Cancellable {
        private var flyAtPlayer: Boolean = true
        private var cancelled: Boolean = false

        fun getItem(): Item = item

        fun getFlyAtPlayer(): Boolean = flyAtPlayer

        fun setFlyAtPlayer(value: Boolean) {
            flyAtPlayer = value
        }

        override fun isCancelled(): Boolean = cancelled

        override fun setCancelled(cancelled: Boolean) {
            this.cancelled = cancelled
        }

        override fun getHandlers(): HandlerList = HANDLERS

        private companion object {
            val HANDLERS = HandlerList()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            when (method.returnType) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                else -> null
            }
        } as T
}
