package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.entity.Entity
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.lang.reflect.Method

/**
 * 在 Paper collect animation 從 client 移除仍存活的 Item 後，重新建立當下 viewer 的追蹤狀態。
 */
public class BukkitItemVisibilityResynchronizer internal constructor(
    private val plugin: Plugin,
    private val delayedTaskExecutor: DelayedMainThreadTaskExecutor,
    private val warningSink: DisplayWarningSink,
    private val visibilityGateway: BukkitEntityVisibilityGateway,
) {
    public constructor(
        plugin: Plugin,
        delayedTaskExecutor: DelayedMainThreadTaskExecutor,
        warningSink: DisplayWarningSink,
    ) : this(
        plugin,
        delayedTaskExecutor,
        warningSink,
        ReflectiveBukkitEntityVisibilityGateway,
    )

    public fun isSupported(player: Player): Boolean = visibilityGateway.isSupported(player)

    @Suppress("TooGenericExceptionCaught")
    public fun resynchronize(
        player: Player,
        item: Item,
    ) {
        try {
            val viewers =
                buildList {
                    add(player)
                    item.world.players
                        .orEmpty()
                        .filter { viewer -> viewer !== player }
                        .forEach(::add)
                }
            delayedTaskExecutor.execute(RESYNCHRONIZATION_DELAY_TICKS) {
                resynchronizeNow(viewers, item)
            }
        } catch (error: RuntimeException) {
            warningSink.warn(
                "partial pickup carrier visibility resynchronization scheduling failed " +
                    "(${error.javaClass.simpleName})",
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun resynchronizeNow(
        viewers: List<Player>,
        item: Item,
    ) {
        try {
            if (!item.isValid || item.isDead) return
            viewers.forEach { viewer -> resynchronizeViewer(viewer, item) }
        } catch (error: Exception) {
            warningSink.warn(
                "partial pickup carrier visibility resynchronization failed " +
                    "(${error.javaClass.simpleName})",
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun resynchronizeViewer(
        player: Player,
        item: Item,
    ) {
        try {
            if (!player.isOnline || !visibilityGateway.isSupported(player)) return
            if (player.world !== item.world || !visibilityGateway.canSee(player, item)) return
            visibilityGateway.hide(player, plugin, item)
            visibilityGateway.show(player, plugin, item)
        } catch (error: Exception) {
            warningSink.warn(
                "partial pickup carrier visibility resynchronization failed " +
                    "(${error.javaClass.simpleName})",
            )
        }
    }

    private companion object {
        private const val RESYNCHRONIZATION_DELAY_TICKS = 1L
    }
}

internal interface BukkitEntityVisibilityGateway {
    fun isSupported(player: Player): Boolean

    fun canSee(
        player: Player,
        entity: Entity,
    ): Boolean

    fun hide(
        player: Player,
        plugin: Plugin,
        entity: Entity,
    )

    fun show(
        player: Player,
        plugin: Plugin,
        entity: Entity,
    )
}

internal object ReflectiveBukkitEntityVisibilityGateway : BukkitEntityVisibilityGateway {
    private val capabilityByPlayerClass =
        object : ClassValue<EntityVisibilityCapability>() {
            override fun computeValue(type: Class<*>): EntityVisibilityCapability =
                try {
                    EntityVisibilityCapability.Supported(
                        canSee = type.getMethod("canSee", Entity::class.java),
                        hide = type.getMethod("hideEntity", Plugin::class.java, Entity::class.java),
                        show = type.getMethod("showEntity", Plugin::class.java, Entity::class.java),
                    )
                } catch (_: NoSuchMethodException) {
                    EntityVisibilityCapability.Unsupported
                } catch (_: SecurityException) {
                    EntityVisibilityCapability.Unsupported
                } catch (_: LinkageError) {
                    EntityVisibilityCapability.Unsupported
                }
        }

    override fun isSupported(player: Player): Boolean =
        capabilityByPlayerClass.get(player.javaClass) is EntityVisibilityCapability.Supported

    override fun canSee(
        player: Player,
        entity: Entity,
    ): Boolean = methods(player).canSee.invoke(player, entity) as Boolean

    override fun hide(
        player: Player,
        plugin: Plugin,
        entity: Entity,
    ) {
        methods(player).hide.invoke(player, plugin, entity)
    }

    override fun show(
        player: Player,
        plugin: Plugin,
        entity: Entity,
    ) {
        methods(player).show.invoke(player, plugin, entity)
    }

    private fun methods(player: Player): EntityVisibilityCapability.Supported =
        capabilityByPlayerClass.get(player.javaClass) as? EntityVisibilityCapability.Supported
            ?: error("Entity visibility API is unavailable for ${player.javaClass.name}")
}

private sealed interface EntityVisibilityCapability {
    data object Unsupported : EntityVisibilityCapability

    data class Supported(
        val canSee: Method,
        val hide: Method,
        val show: Method,
    ) : EntityVisibilityCapability
}
