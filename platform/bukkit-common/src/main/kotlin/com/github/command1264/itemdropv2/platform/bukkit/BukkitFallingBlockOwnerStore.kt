package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.NamespacedKey
import org.bukkit.entity.FallingBlock
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

internal interface FallingBlockOwnerStore {
    fun read(entity: FallingBlock): UUID?

    fun write(
        entity: FallingBlock,
        ownerUuid: UUID,
    )

    fun clear(entity: FallingBlock)
}

internal class BukkitFallingBlockOwnerStore(
    private val ownerKey: NamespacedKey,
    private val warningSink: DisplayWarningSink,
) : FallingBlockOwnerStore {
    override fun read(entity: FallingBlock): UUID? {
        val stored =
            entity.persistentDataContainer
                .get(ownerKey, PersistentDataType.STRING)
                ?: return null
        return try {
            UUID.fromString(stored)
        } catch (_: IllegalArgumentException) {
            warningSink.warn("ignored invalid falling block owner UUID")
            null
        }
    }

    override fun write(
        entity: FallingBlock,
        ownerUuid: UUID,
    ) {
        entity.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, ownerUuid.toString())
    }

    override fun clear(entity: FallingBlock) {
        entity.persistentDataContainer.remove(ownerKey)
    }
}
