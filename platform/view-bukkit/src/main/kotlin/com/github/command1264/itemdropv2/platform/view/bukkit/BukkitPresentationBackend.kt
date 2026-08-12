package com.github.command1264.itemdropv2.platform.view.bukkit

import com.github.command1264.itemdropv2.core.DirectItemPickupFeedbackView
import com.github.command1264.itemdropv2.core.DirectItemPresentationJournalView
import com.github.command1264.itemdropv2.core.DirectItemPresentationView
import com.github.command1264.itemdropv2.core.ItemPickupFeedbackResult
import com.github.command1264.itemdropv2.core.ItemPresentation
import com.github.command1264.itemdropv2.core.ItemStateJournalPresentation
import com.github.command1264.itemdropv2.core.PresentationBackend
import com.github.command1264.itemdropv2.core.PresentationJournalSnapshotResult
import com.github.command1264.itemdropv2.core.PresentationResult
import com.github.command1264.itemdropv2.core.vanillaItemPickupPitch
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.NamespacedKey
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

@Suppress("TooManyFunctions")
public class BukkitPresentationBackend internal constructor(
    private val targetResolver: BukkitItemTargetResolver,
    private val nextRandomFloat: () -> Float = { ThreadLocalRandom.current().nextFloat() },
) : PresentationBackend,
    DirectItemPresentationView<Item>,
    DirectItemPresentationJournalView<Item>,
    DirectItemPickupFeedbackView<Player, Item> {
    public constructor() : this(
        BukkitItemTargetResolver { entityId ->
            resolvePresentationTarget(
                entityId = entityId,
                direct = Bukkit.getEntity(entityId) as? Item,
                entityIdOf = Item::getUniqueId,
                loadedTargets = {
                    Bukkit
                        .getWorlds()
                        .asSequence()
                        .flatMap { world -> world.loadedChunks.asSequence() }
                        .flatMap { chunk -> chunk.entities.asSequence() }
                        .filterIsInstance<Item>()
                },
            )?.let(::BukkitItemNameTargetAdapter)
        },
    )

    override val id: String = BukkitPresentationBackendProvider.ID

    override fun activate(): Unit = Unit

    @Suppress("TooGenericExceptionCaught")
    override fun present(presentation: ItemPresentation): PresentationResult {
        val target = targetResolver.resolve(presentation.entityId) ?: return PresentationResult.MissingTarget
        return present(target, presentation)
    }

    override fun present(
        target: Item,
        presentation: ItemPresentation,
    ): PresentationResult = present(BukkitItemNameTargetAdapter(target), presentation)

    @Suppress("TooGenericExceptionCaught")
    private fun present(
        target: BukkitItemNameTarget,
        presentation: ItemPresentation,
    ): PresentationResult =
        try {
            if (target.managedDisplayName == null) {
                target.originalNameWasPresent = target.customName != null
                target.originalName = target.customName
                target.originalNameVisible = target.customNameVisible
            }
            target.customName = ChatColor.translateAlternateColorCodes('&', presentation.text)
            target.customNameVisible = presentation.visible
            target.managedDisplayName = target.customName
            PresentationResult.Applied
        } catch (error: RuntimeException) {
            PresentationResult.Failed(error.javaClass.simpleName)
        }

    @Suppress("TooGenericExceptionCaught")
    override fun clear(entityId: UUID): PresentationResult {
        val target = targetResolver.resolve(entityId) ?: return PresentationResult.MissingTarget
        return clear(target)
    }

    override fun clear(target: Item): PresentationResult = clear(BukkitItemNameTargetAdapter(target))

    override fun capture(target: Item): PresentationJournalSnapshotResult = capture(BukkitItemNameTargetAdapter(target))

    override fun restore(
        target: Item,
        snapshot: ItemStateJournalPresentation,
    ): PresentationJournalSnapshotResult = restore(BukkitItemNameTargetAdapter(target), snapshot)

    @Suppress("TooGenericExceptionCaught")
    internal fun capture(target: BukkitItemNameTarget): PresentationJournalSnapshotResult =
        try {
            val managedName = target.managedDisplayName
            val originalPresent = target.originalNameWasPresent
            val originalVisible = target.originalNameVisible
            when {
                managedName == null && originalPresent == null && target.originalName == null && originalVisible == null ->
                    PresentationJournalSnapshotResult.Captured(
                        ItemStateJournalPresentation(null, false, null, false),
                    )
                managedName == null || originalPresent == null || originalVisible == null ->
                    PresentationJournalSnapshotResult.Rejected("PartialPresentationGuard")
                originalPresent && target.originalName == null ->
                    PresentationJournalSnapshotResult.Rejected("PartialPresentationGuard")
                else ->
                    PresentationJournalSnapshotResult.Captured(
                        ItemStateJournalPresentation(managedName, originalPresent, target.originalName, originalVisible),
                    )
            }
        } catch (error: RuntimeException) {
            PresentationJournalSnapshotResult.Failed(error.javaClass.simpleName)
        }

    @Suppress("TooGenericExceptionCaught")
    internal fun restore(
        target: BukkitItemNameTarget,
        snapshot: ItemStateJournalPresentation,
    ): PresentationJournalSnapshotResult =
        try {
            if (snapshot.managedName != null && target.customName != snapshot.managedName) {
                PresentationJournalSnapshotResult.Rejected("ManagedNameMismatch")
            } else {
                target.managedDisplayName = snapshot.managedName
                target.originalNameWasPresent = snapshot.originalNamePresent.takeIf { snapshot.managedName != null }
                target.originalName = snapshot.originalName
                target.originalNameVisible = snapshot.customNameVisible.takeIf { snapshot.managedName != null }
                PresentationJournalSnapshotResult.Applied
            }
        } catch (error: RuntimeException) {
            PresentationJournalSnapshotResult.Failed(error.javaClass.simpleName)
        }

    override fun playPickupFeedback(
        actor: Player,
        target: Item,
        pickedUpAmount: Int,
        carrierRemains: Boolean,
        carrierResynchronizationSupported: Boolean,
    ): ItemPickupFeedbackResult {
        actor.playSound(
            target.location,
            Sound.ENTITY_ITEM_PICKUP,
            SoundCategory.PLAYERS,
            PICKUP_SOUND_VOLUME,
            vanillaItemPickupPitch(nextRandomFloat(), nextRandomFloat()),
        )
        return ItemPickupFeedbackResult.Complete
    }

    @Suppress("TooGenericExceptionCaught")
    private fun clear(target: BukkitItemNameTarget): PresentationResult =
        try {
            val managedName = target.managedDisplayName
            if (managedName != null && target.customName == managedName) {
                target.customName = if (target.originalNameWasPresent == true) target.originalName else null
                target.customNameVisible = target.originalNameVisible ?: false
            }
            target.managedDisplayName = null
            target.originalName = null
            target.originalNameWasPresent = null
            target.originalNameVisible = null
            PresentationResult.Applied
        } catch (error: RuntimeException) {
            PresentationResult.Failed(error.javaClass.simpleName)
        }

    override fun close(): Unit = Unit
}

internal fun interface BukkitItemTargetResolver {
    fun resolve(entityId: UUID): BukkitItemNameTarget?
}

internal interface BukkitItemNameTarget {
    var customName: String?
    var customNameVisible: Boolean
    var managedDisplayName: String?
    var originalName: String?
    var originalNameWasPresent: Boolean?
    var originalNameVisible: Boolean?
}

private class BukkitItemNameTargetAdapter(
    private val item: Item,
) : BukkitItemNameTarget {
    override var customName: String?
        get() = item.customName
        set(value) {
            item.customName = value
        }

    override var customNameVisible: Boolean
        get() = item.isCustomNameVisible
        set(value) {
            item.isCustomNameVisible = value
        }

    override var managedDisplayName: String?
        get() = item.persistentDataContainer.get(MANAGED_DISPLAY_NAME_KEY, PersistentDataType.STRING)
        set(value) {
            if (value == null) {
                item.persistentDataContainer.remove(MANAGED_DISPLAY_NAME_KEY)
            } else {
                item.persistentDataContainer.set(MANAGED_DISPLAY_NAME_KEY, PersistentDataType.STRING, value)
            }
        }

    override var originalName: String?
        get() = item.persistentDataContainer.get(ORIGINAL_NAME_KEY, PersistentDataType.STRING)
        set(value) {
            if (value == null) {
                item.persistentDataContainer.remove(ORIGINAL_NAME_KEY)
            } else {
                item.persistentDataContainer.set(ORIGINAL_NAME_KEY, PersistentDataType.STRING, value)
            }
        }

    override var originalNameWasPresent: Boolean?
        get() = item.persistentDataContainer.get(ORIGINAL_NAME_PRESENT_KEY, PersistentDataType.BYTE)?.toBoolean()
        set(value) {
            item.persistentDataContainer.setOrRemove(ORIGINAL_NAME_PRESENT_KEY, value)
        }

    override var originalNameVisible: Boolean?
        get() = item.persistentDataContainer.get(ORIGINAL_NAME_VISIBLE_KEY, PersistentDataType.BYTE)?.toBoolean()
        set(value) {
            item.persistentDataContainer.setOrRemove(ORIGINAL_NAME_VISIBLE_KEY, value)
        }
}

@Suppress("DEPRECATION")
private val MANAGED_DISPLAY_NAME_KEY = NamespacedKey("itemdropv2", "managed-display-name")

@Suppress("DEPRECATION")
private val ORIGINAL_NAME_KEY = NamespacedKey("itemdropv2", "managed-original-name")

@Suppress("DEPRECATION")
private val ORIGINAL_NAME_PRESENT_KEY = NamespacedKey("itemdropv2", "managed-original-name-present")

@Suppress("DEPRECATION")
private val ORIGINAL_NAME_VISIBLE_KEY = NamespacedKey("itemdropv2", "managed-original-name-visible")

private fun org.bukkit.persistence.PersistentDataContainer.setOrRemove(
    key: NamespacedKey,
    value: Boolean?,
) {
    if (value == null) {
        remove(key)
    } else {
        set(key, PersistentDataType.BYTE, (if (value) 1 else 0).toByte())
    }
}

private fun Byte.toBoolean(): Boolean = this.toInt() != 0

private const val PICKUP_SOUND_VOLUME = 0.2F

internal fun <T> resolvePresentationTarget(
    entityId: UUID,
    direct: T?,
    entityIdOf: (T) -> UUID,
    loadedTargets: () -> Sequence<T>,
): T? =
    direct
        ?.takeIf { target -> entityIdOf(target) == entityId }
        ?: loadedTargets().firstOrNull { target -> entityIdOf(target) == entityId }
