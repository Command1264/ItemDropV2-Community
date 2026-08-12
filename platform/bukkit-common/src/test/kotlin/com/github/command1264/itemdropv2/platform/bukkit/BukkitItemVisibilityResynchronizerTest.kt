package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class BukkitItemVisibilityResynchronizerTest {
    @Test
    fun `resynchronizes the picker and a visible third-party viewer`() {
        val scenario = MultiViewerVisibilityScenario()
        val picker = scenario.player()
        val observer = scenario.player()
        scenario.worldPlayers += listOf(picker, observer)
        val calls = mutableListOf<Pair<String, String>>()
        val resynchronizer =
            scenario.resynchronizer(
                visibilityGateway(
                    hide = { player, _, _ -> calls += "hideEntity" to viewerLabel(player, picker, observer) },
                    show = { player, _, _ -> calls += "showEntity" to viewerLabel(player, picker, observer) },
                ),
            )

        scenario.resynchronize(resynchronizer, picker)

        assertEquals(
            listOf(
                "hideEntity" to "picker",
                "showEntity" to "picker",
                "hideEntity" to "observer",
                "showEntity" to "observer",
            ),
            calls,
        )
    }

    @Test
    fun `does not reveal a third-party viewer item hidden by another plugin`() {
        val scenario = MultiViewerVisibilityScenario()
        val picker = scenario.player()
        val hiddenObserver = scenario.player()
        scenario.worldPlayers += listOf(picker, hiddenObserver)
        val synchronizedViewers = mutableListOf<String>()
        val resynchronizer =
            scenario.resynchronizer(
                object : BukkitEntityVisibilityGateway {
                    override fun isSupported(player: Player): Boolean = true

                    override fun canSee(
                        player: Player,
                        entity: Entity,
                    ): Boolean = player !== hiddenObserver

                    override fun hide(
                        player: Player,
                        plugin: Plugin,
                        entity: Entity,
                    ) {
                        synchronizedViewers += "hide:${viewerLabel(player, picker, hiddenObserver)}"
                    }

                    override fun show(
                        player: Player,
                        plugin: Plugin,
                        entity: Entity,
                    ) {
                        synchronizedViewers += "show:${viewerLabel(player, picker, hiddenObserver)}"
                    }
                },
            )

        scenario.resynchronize(resynchronizer, picker)

        assertEquals(listOf("hide:picker", "show:picker"), synchronizedViewers)
    }

    @Test
    fun `does not resynchronize a player who joined the world after the collect animation`() {
        val scenario = MultiViewerVisibilityScenario()
        val picker = scenario.player()
        val lateObserver = scenario.player()
        scenario.worldPlayers += picker
        val synchronizedViewers = mutableListOf<Player>()
        val resynchronizer =
            scenario.resynchronizer(
                visibilityGateway(hide = { player, _, _ -> synchronizedViewers += player }),
            )

        resynchronizer.resynchronize(picker, scenario.item)
        scenario.worldPlayers += lateObserver
        scenario.runQueuedTask()

        assertEquals(1, synchronizedViewers.size)
        assertTrue(synchronizedViewers.single() === picker)
    }

    @Test
    fun `continues with other viewers when one visibility operation fails`() {
        val scenario = MultiViewerVisibilityScenario()
        val picker = scenario.player()
        val observer = scenario.player()
        scenario.worldPlayers += listOf(picker, observer)
        val observerCalls = mutableListOf<String>()
        val resynchronizer =
            scenario.resynchronizer(
                visibilityGateway(
                    hide = { player, _, _ ->
                        if (player === picker) throw ReflectiveOperationException("failed")
                        observerCalls += "hideEntity"
                    },
                    show = { player, _, _ ->
                        if (player === observer) observerCalls += "showEntity"
                    },
                ),
            )

        scenario.resynchronize(resynchronizer, picker)

        assertEquals(listOf("hideEntity", "showEntity"), observerCalls)
        assertEquals(
            listOf("partial pickup carrier visibility resynchronization failed (ReflectiveOperationException)"),
            scenario.warnings,
        )
    }

    @Test
    fun `resynchronizes a visible surviving carrier one tick later`() {
        val world = proxy<World>()
        val plugin = proxy<Plugin>()
        val item =
            proxy<Item> { method, _ ->
                when (method.name) {
                    "isValid" -> true
                    "isDead" -> false
                    "getWorld" -> world
                    else -> defaultValue(method.returnType)
                }
            }
        val calls = mutableListOf<Pair<String, Any?>>()
        val player =
            proxy<Player> { method, _ ->
                when (method.name) {
                    "isOnline" -> true
                    "getWorld" -> world
                    else -> defaultValue(method.returnType)
                }
            }
        val visibilityGateway =
            visibilityGateway(
                canSee = true,
                hide = { _, _, entity -> calls += "hideEntity" to entity },
                show = { _, _, entity -> calls += "showEntity" to entity },
            )
        val queued = mutableListOf<() -> Unit>()
        val delays = mutableListOf<Long>()
        val warnings = mutableListOf<String>()
        val resynchronizer =
            BukkitItemVisibilityResynchronizer(
                plugin = plugin,
                delayedTaskExecutor =
                    DelayedMainThreadTaskExecutor { delayTicks, task ->
                        delays += delayTicks
                        queued += task
                    },
                warningSink = DisplayWarningSink(warnings::add),
                visibilityGateway = visibilityGateway,
            )

        assertEquals(true, resynchronizer.isSupported(player))
        resynchronizer.resynchronize(player, item)

        assertEquals(listOf(1L), delays)
        assertEquals(emptyList<Pair<String, Any?>>(), calls)
        queued.single().invoke()
        assertEquals(listOf("hideEntity", "showEntity"), calls.map { it.first })
        assertEquals(listOf(item.toString(), item.toString()), calls.map { it.second.toString() })
        assertEquals(emptyList<String>(), warnings)
    }

    @Test
    fun `does not reveal an item hidden by another plugin`() {
        val world = proxy<World>()
        val plugin = proxy<Plugin>()
        val item =
            proxy<Item> { method, _ ->
                when (method.name) {
                    "isValid" -> true
                    "isDead" -> false
                    "getWorld" -> world
                    else -> defaultValue(method.returnType)
                }
            }
        var visibilityMutationCount = 0
        val player =
            proxy<Player> { method, _ ->
                when (method.name) {
                    "isOnline" -> true
                    "getWorld" -> world
                    else -> defaultValue(method.returnType)
                }
            }
        val queued = mutableListOf<() -> Unit>()
        val resynchronizer =
            BukkitItemVisibilityResynchronizer(
                plugin,
                DelayedMainThreadTaskExecutor { _, task -> queued += task },
                DisplayWarningSink {},
                visibilityGateway(
                    canSee = false,
                    hide = { _, _, _ -> visibilityMutationCount += 1 },
                    show = { _, _, _ -> visibilityMutationCount += 1 },
                ),
            )

        resynchronizer.resynchronize(player, item)
        queued.single().invoke()

        assertEquals(0, visibilityMutationCount)
    }

    @Test
    fun `skips an invalid carrier or a carrier in another world`() {
        val playerWorld = proxy<World>()
        val itemWorld = proxy<World>()
        val plugin = proxy<Plugin>()
        var itemValid = false
        val item =
            proxy<Item> { method, _ ->
                when (method.name) {
                    "isValid" -> itemValid
                    "isDead" -> false
                    "getWorld" -> itemWorld
                    else -> defaultValue(method.returnType)
                }
            }
        val player =
            proxy<Player> { method, _ ->
                when (method.name) {
                    "isOnline" -> true
                    "getWorld" -> playerWorld
                    else -> defaultValue(method.returnType)
                }
            }
        var visibilityMutationCount = 0
        val queued = mutableListOf<() -> Unit>()
        val resynchronizer =
            BukkitItemVisibilityResynchronizer(
                plugin,
                DelayedMainThreadTaskExecutor { _, task -> queued += task },
                DisplayWarningSink {},
                visibilityGateway(
                    hide = { _, _, _ -> visibilityMutationCount += 1 },
                    show = { _, _, _ -> visibilityMutationCount += 1 },
                ),
            )

        resynchronizer.resynchronize(player, item)
        queued.removeFirst().invoke()
        itemValid = true
        resynchronizer.resynchronize(player, item)
        queued.removeFirst().invoke()

        assertEquals(0, visibilityMutationCount)
    }

    @Test
    fun `reports scheduling failure without changing the carrier`() {
        val warnings = mutableListOf<String>()
        val world = proxy<World>()
        val item =
            proxy<Item> { method, _ ->
                when (method.name) {
                    "getWorld" -> world
                    else -> defaultValue(method.returnType)
                }
            }
        val resynchronizer =
            BukkitItemVisibilityResynchronizer(
                proxy<Plugin>(),
                DelayedMainThreadTaskExecutor { _, _ -> error("scheduler unavailable") },
                DisplayWarningSink(warnings::add),
                visibilityGateway(),
            )

        resynchronizer.resynchronize(proxy<Player>(), item)

        assertEquals(
            listOf("partial pickup carrier visibility resynchronization scheduling failed (IllegalStateException)"),
            warnings,
        )
    }

    @Test
    fun `reports visibility execution failure`() {
        val world = proxy<World>()
        val item =
            proxy<Item> { method, _ ->
                when (method.name) {
                    "isValid" -> true
                    "isDead" -> false
                    "getWorld" -> world
                    else -> defaultValue(method.returnType)
                }
            }
        val player =
            proxy<Player> { method, _ ->
                when (method.name) {
                    "isOnline" -> true
                    "getWorld" -> world
                    else -> defaultValue(method.returnType)
                }
            }
        val queued = mutableListOf<() -> Unit>()
        val warnings = mutableListOf<String>()
        val resynchronizer =
            BukkitItemVisibilityResynchronizer(
                proxy<Plugin>(),
                DelayedMainThreadTaskExecutor { _, task -> queued += task },
                DisplayWarningSink(warnings::add),
                visibilityGateway(hide = { _, _, _ -> throw ReflectiveOperationException("failed") }),
            )

        resynchronizer.resynchronize(player, item)
        queued.single().invoke()

        assertEquals(
            listOf("partial pickup carrier visibility resynchronization failed (ReflectiveOperationException)"),
            warnings,
        )
    }

    @Test
    fun `detects and invokes the modern entity visibility API reflectively`() {
        val world = proxy<World>()
        val plugin = proxy<Plugin>()
        val item =
            proxy<Item> { method, _ ->
                when (method.name) {
                    "isValid" -> true
                    "isDead" -> false
                    "getWorld" -> world
                    else -> defaultValue(method.returnType)
                }
            }
        val calls = mutableListOf<String>()
        val player =
            modernVisibilityPlayer(world) { method ->
                if (method == "hideEntity" || method == "showEntity") calls += method
            }
        val queued = mutableListOf<() -> Unit>()
        val resynchronizer =
            BukkitItemVisibilityResynchronizer(
                plugin,
                DelayedMainThreadTaskExecutor { _, task -> queued += task },
                DisplayWarningSink {},
            )

        assertEquals(true, resynchronizer.isSupported(player))
        resynchronizer.resynchronize(player, item)
        queued.single().invoke()

        assertEquals(listOf("hideEntity", "showEntity"), calls)
    }
}

private class MultiViewerVisibilityScenario {
    val worldPlayers = mutableListOf<Player>()
    val world: World =
        proxy { method, _ ->
            when (method.name) {
                "getPlayers" -> worldPlayers
                else -> defaultValue(method.returnType)
            }
        }
    val item: Item = validItem(world)
    val warnings = mutableListOf<String>()
    private val queued = mutableListOf<() -> Unit>()
    private val plugin = proxy<Plugin>()

    fun player(): Player = onlinePlayer(world)

    fun resynchronizer(gateway: BukkitEntityVisibilityGateway): BukkitItemVisibilityResynchronizer =
        BukkitItemVisibilityResynchronizer(
            plugin,
            DelayedMainThreadTaskExecutor { _, task -> queued += task },
            DisplayWarningSink(warnings::add),
            gateway,
        )

    fun resynchronize(
        resynchronizer: BukkitItemVisibilityResynchronizer,
        picker: Player,
    ) {
        resynchronizer.resynchronize(picker, item)
        runQueuedTask()
    }

    fun runQueuedTask() {
        queued.single().invoke()
    }
}

private fun onlinePlayer(world: World): Player =
    proxy { method, _ ->
        when (method.name) {
            "isOnline" -> true
            "getWorld" -> world
            else -> defaultValue(method.returnType)
        }
    }

private fun validItem(world: World): Item =
    proxy { method, _ ->
        when (method.name) {
            "isValid" -> true
            "isDead" -> false
            "getWorld" -> world
            else -> defaultValue(method.returnType)
        }
    }

private fun viewerLabel(
    player: Player,
    picker: Player,
    observer: Player,
): String =
    when {
        player === picker -> "picker"
        player === observer -> "observer"
        else -> "unexpected"
    }

private interface ModernEntityVisibilityPlayer {
    fun canSee(entity: Entity): Boolean

    fun hideEntity(
        plugin: Plugin,
        entity: Entity,
    )

    fun showEntity(
        plugin: Plugin,
        entity: Entity,
    )
}

private fun modernVisibilityPlayer(
    world: World,
    methodCall: (String) -> Unit,
): Player =
    Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java, ModernEntityVisibilityPlayer::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "toString" -> "Proxy<ModernPlayer>"
            "hashCode" -> System.identityHashCode(Player::class.java)
            "equals" -> false
            "isOnline", "canSee" -> true
            "getWorld" -> world
            "hideEntity", "showEntity" -> {
                methodCall(method.name)
                null
            }
            else -> defaultValue(method.returnType)
        }
    } as Player

private fun visibilityGateway(
    supported: Boolean = true,
    canSee: Boolean = true,
    hide: (Player, Plugin, Entity) -> Unit = { _, _, _ -> },
    show: (Player, Plugin, Entity) -> Unit = { _, _, _ -> },
): BukkitEntityVisibilityGateway =
    object : BukkitEntityVisibilityGateway {
        override fun isSupported(player: Player): Boolean = supported

        override fun canSee(
            player: Player,
            entity: Entity,
        ): Boolean = canSee

        override fun hide(
            player: Player,
            plugin: Plugin,
            entity: Entity,
        ) {
            hide(player, plugin, entity)
        }

        override fun show(
            player: Player,
            plugin: Plugin,
            entity: Entity,
        ) {
            show(player, plugin, entity)
        }
    }

private inline fun <reified T> proxy(
    crossinline invocation: (Method, Array<out Any?>) -> Any? = { method, _ -> defaultValue(method.returnType) },
): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
        when (method.name) {
            "toString" -> "Proxy<${T::class.java.simpleName}>"
            "hashCode" -> System.identityHashCode(T::class.java)
            "equals" -> false
            else -> invocation(method, arguments ?: emptyArray())
        }
    } as T

private fun defaultValue(type: Class<*>): Any? =
    when (type) {
        Boolean::class.javaPrimitiveType -> false
        Byte::class.javaPrimitiveType -> 0.toByte()
        Short::class.javaPrimitiveType -> 0.toShort()
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        Float::class.javaPrimitiveType -> 0F
        Double::class.javaPrimitiveType -> 0.0
        Char::class.javaPrimitiveType -> '\u0000'
        else -> null
    }
