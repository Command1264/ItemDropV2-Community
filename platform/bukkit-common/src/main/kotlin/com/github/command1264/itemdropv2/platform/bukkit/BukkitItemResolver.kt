package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.Bukkit
import org.bukkit.Server
import org.bukkit.entity.Item
import java.util.UUID

internal fun resolveBukkitItem(entityId: UUID): Item? = resolveServerItem(Bukkit.getServer(), entityId)

internal fun resolveServerItem(
    server: Server,
    entityId: UUID,
): Item? =
    findItemByUuid(
        entityId = entityId,
        direct = server.getEntity(entityId) as? Item,
        loadedItems = {
            server
                .worlds
                .asSequence()
                .flatMap { world -> world.loadedChunks.asSequence() }
                .flatMap { chunk -> chunk.entities.asSequence() }
                .filterIsInstance<Item>()
        },
    )

internal fun findItemByUuid(
    entityId: UUID,
    direct: Item?,
    loadedItems: () -> Sequence<Item>,
): Item? = direct?.takeIf { it.uniqueId == entityId } ?: loadedItems().firstOrNull { it.uniqueId == entityId }
