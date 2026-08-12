package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerEvent
import org.bukkit.plugin.EventExecutor
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method

/**
 * Optional adapter for Paper's zero-capacity pickup attempt event. The event is resolved once at
 * startup so the universal artifact never links a Paper-only class on Spigot.
 */
public class PaperAttemptPickupEventBridge private constructor(
    private val eventClass: Class<out Event>,
    private val itemGetter: Method,
    private val flyAtPlayerSetter: Method,
) {
    @Suppress("TooGenericExceptionCaught")
    internal fun handle(
        event: Event,
        warningSink: DisplayWarningSink,
        attemptHandler: (Player, Item) -> Boolean,
    ) {
        val cancellable = event as Cancellable
        if (event.isAsynchronous) {
            suppressNativePickup(event, cancellable, warningSink)
            warningSink.warn("cancelled asynchronous Paper pickup attempt event")
            return
        }
        try {
            val player = (event as PlayerEvent).player
            val item = itemGetter.invoke(event) as Item
            if (attemptHandler(player, item)) {
                suppressNativePickup(event, cancellable, warningSink)
            }
        } catch (error: ReflectiveOperationException) {
            suppressNativePickup(event, cancellable, warningSink)
            warningSink.warn("Paper pickup attempt bridge failed (${error.javaClass.simpleName})")
        } catch (error: RuntimeException) {
            suppressNativePickup(event, cancellable, warningSink)
            warningSink.warn("Paper pickup attempt processing failed (${error.javaClass.simpleName})")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun suppressNativePickup(
        event: Event,
        cancellable: Cancellable,
        warningSink: DisplayWarningSink,
    ) {
        cancellable.isCancelled = true
        try {
            flyAtPlayerSetter.invoke(event, false)
        } catch (error: ReflectiveOperationException) {
            warningSink.warn("Paper pickup attempt fly-packet suppression failed (${error.javaClass.simpleName})")
        } catch (error: RuntimeException) {
            warningSink.warn("Paper pickup attempt fly-packet suppression failed (${error.javaClass.simpleName})")
        }
    }

    private fun register(
        plugin: Plugin,
        controller: BukkitItemPickupProtectionController,
        warningSink: DisplayWarningSink,
    ) {
        val listener = object : Listener {}
        plugin.server.pluginManager.registerEvent(
            eventClass,
            listener,
            EventPriority.HIGHEST,
            EventExecutor { _, event ->
                handle(event, warningSink, controller::processCreativeNoCapacityPickupAttempt)
            },
            plugin,
            true,
        )
    }

    public companion object {
        private const val EVENT_CLASS_NAME = "org.bukkit.event.player.PlayerAttemptPickupItemEvent"
        private val bridgeByEventClass = RuntimeClassCapabilityCache(::createBridge)

        @Suppress("ReturnCount")
        public fun registerIfAvailable(
            plugin: Plugin,
            controller: BukkitItemPickupProtectionController,
            warningSink: DisplayWarningSink,
        ): Boolean {
            val eventClass =
                try {
                    Class
                        .forName(EVENT_CLASS_NAME, false, plugin.javaClass.classLoader)
                        .asSubclass(Event::class.java)
                } catch (_: ClassNotFoundException) {
                    return false
                } catch (error: ClassCastException) {
                    warningSink.warn("Paper pickup attempt event type invalid (${error.javaClass.simpleName})")
                    return false
                } catch (error: SecurityException) {
                    warningSink.warn("Paper pickup attempt event access denied (${error.javaClass.simpleName})")
                    return false
                } catch (error: LinkageError) {
                    warningSink.warn("Paper pickup attempt event linkage failed (${error.javaClass.simpleName})")
                    return false
                }
            return try {
                forEventClass(eventClass).also { bridge ->
                    bridge.register(plugin, controller, warningSink)
                }
                true
            } catch (error: ReflectiveOperationException) {
                warningSink.warn("Paper pickup attempt event contract unavailable (${error.javaClass.simpleName})")
                false
            } catch (error: SecurityException) {
                warningSink.warn("Paper pickup attempt event contract access denied (${error.javaClass.simpleName})")
                false
            } catch (error: IllegalArgumentException) {
                warningSink.warn("Paper pickup attempt event contract invalid (${error.javaClass.simpleName})")
                false
            }
        }

        internal fun forEventClass(eventClass: Class<out Event>): PaperAttemptPickupEventBridge = bridgeByEventClass.get(eventClass)

        private fun createBridge(eventClass: Class<*>): PaperAttemptPickupEventBridge {
            require(PlayerEvent::class.java.isAssignableFrom(eventClass)) { "attempt event must extend PlayerEvent" }
            require(Cancellable::class.java.isAssignableFrom(eventClass)) { "attempt event must be cancellable" }
            val itemGetter = eventClass.getMethod("getItem")
            require(Item::class.java.isAssignableFrom(itemGetter.returnType)) { "getItem must return Item" }
            val flyAtPlayerSetter = eventClass.getMethod("setFlyAtPlayer", java.lang.Boolean.TYPE)
            return PaperAttemptPickupEventBridge(eventClass.asSubclass(Event::class.java), itemGetter, flyAtPlayerSetter)
        }
    }
}
