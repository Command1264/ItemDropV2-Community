package com.github.command1264.itemdropv2.platform.view.bukkit

import com.github.command1264.itemdropv2.core.ItemPickupFeedbackResult
import com.github.command1264.itemdropv2.core.ItemPresentation
import com.github.command1264.itemdropv2.core.ItemStateJournalPresentation
import com.github.command1264.itemdropv2.core.PresentationJournalSnapshotResult
import com.github.command1264.itemdropv2.core.PresentationResult
import org.bukkit.Location
import org.bukkit.Sound
import org.bukkit.SoundCategory
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitPresentationBackendTest {
    private val entityId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    @Test
    fun `plays vanilla pickup sound at the item using vanilla pitch formula`() {
        val calls = mutableListOf<Array<out Any?>>()
        val location = Location(null, 1.0, 2.0, 3.0)
        val item =
            proxy<Item> { method, _ ->
                when (method) {
                    "getLocation" -> location
                    else -> defaultValue(Item::class.java, method)
                }
            }
        val player =
            proxy<Player> { method, arguments ->
                if (method == "playSound") calls += arguments
                defaultValue(Player::class.java, method)
            }
        val randomValues = ArrayDeque(listOf(0.75F, 0.25F))
        val backend =
            BukkitPresentationBackend(
                targetResolver = BukkitItemTargetResolver { null },
                nextRandomFloat = { randomValues.removeFirst() },
            )

        val result =
            backend.playPickupFeedback(
                player,
                item,
                64,
                carrierRemains = true,
                carrierResynchronizationSupported = false,
            )

        assertEquals(ItemPickupFeedbackResult.Complete, result)
        assertEquals(1, calls.size)
        assertEquals(location, calls.single()[0])
        assertEquals(Sound.ENTITY_ITEM_PICKUP, calls.single()[1])
        assertEquals(SoundCategory.PLAYERS, calls.single()[2])
        assertEquals(0.2F, calls.single()[3])
        assertEquals(2.7F, calls.single()[4])
    }

    @Test
    fun `applies legacy color codes and visibility to the resolved item`() {
        val target = FakeTarget()
        val backend = BukkitPresentationBackend(BukkitItemTargetResolver { target })

        val result = backend.present(ItemPresentation(entityId, "&cStone", true))

        assertEquals(PresentationResult.Applied, result)
        assertEquals("§cStone", target.customName)
        assertEquals(true, target.customNameVisible)
        assertEquals("§cStone", target.managedDisplayName)
    }

    @Test
    fun `clears only the matching ItemDropV2 managed name`() {
        val owned =
            FakeTarget().apply {
                customName = "Stone"
                customNameVisible = true
                managedDisplayName = "Stone"
            }
        val replacedByAnotherPlugin =
            FakeTarget().apply {
                customName = "Other"
                customNameVisible = true
                managedDisplayName = "Stone"
            }

        val ownedResult = BukkitPresentationBackend(BukkitItemTargetResolver { owned }).clear(entityId)
        val replacedResult = BukkitPresentationBackend(BukkitItemTargetResolver { replacedByAnotherPlugin }).clear(entityId)

        assertEquals(PresentationResult.Applied, ownedResult)
        assertEquals(null, owned.customName)
        assertEquals(false, owned.customNameVisible)
        assertEquals(null, owned.managedDisplayName)
        assertEquals(PresentationResult.Applied, replacedResult)
        assertEquals("Other", replacedByAnotherPlugin.customName)
        assertEquals(true, replacedByAnotherPlugin.customNameVisible)
        assertEquals(null, replacedByAnotherPlugin.managedDisplayName)
    }

    @Test
    fun `restores the entity name that existed before ItemDropV2 presentation`() {
        val target =
            FakeTarget().apply {
                customName = "Quest reward"
                customNameVisible = false
            }
        val backend = BukkitPresentationBackend(BukkitItemTargetResolver { target })

        backend.present(ItemPresentation(entityId, "Stone", true))
        backend.present(ItemPresentation(entityId, "Stone x2", true))
        backend.clear(entityId)

        assertEquals("Quest reward", target.customName)
        assertEquals(false, target.customNameVisible)
    }

    @Test
    fun `reports a missing item target`() {
        val backend = BukkitPresentationBackend(BukkitItemTargetResolver { null })

        assertEquals(
            PresentationResult.MissingTarget,
            backend.present(ItemPresentation(entityId, "Stone", true)),
        )
    }

    @Test
    fun `captures and restores the managed original-name guard without replacing CustomName`() {
        val target =
            FakeTarget().apply {
                customName = "Managed x8192"
                customNameVisible = true
                managedDisplayName = "Managed x8192"
                originalNameWasPresent = true
                originalName = "Quest reward"
                originalNameVisible = false
            }
        val backend = BukkitPresentationBackend(BukkitItemTargetResolver { target })
        val expected = ItemStateJournalPresentation("Managed x8192", true, "Quest reward", false)

        assertEquals(PresentationJournalSnapshotResult.Captured(expected), backend.capture(target))
        target.managedDisplayName = null
        target.originalNameWasPresent = null
        target.originalName = null
        target.originalNameVisible = null

        assertEquals(PresentationJournalSnapshotResult.Applied, backend.restore(target, expected))
        assertEquals("Managed x8192", target.customName)
        assertEquals("Managed x8192", target.managedDisplayName)
        assertEquals(true, target.originalNameWasPresent)
        assertEquals("Quest reward", target.originalName)
        assertEquals(false, target.originalNameVisible)
    }

    @Test
    fun `rejects partial guards and a managed name that no longer matches CustomName`() {
        val partial = FakeTarget().apply { managedDisplayName = "Managed" }
        val replaced =
            FakeTarget().apply {
                customName = "Other plugin"
                managedDisplayName = null
            }
        val backend = BukkitPresentationBackend(BukkitItemTargetResolver { null })

        assertEquals(PresentationJournalSnapshotResult.Rejected("PartialPresentationGuard"), backend.capture(partial))
        assertEquals(
            PresentationJournalSnapshotResult.Rejected("ManagedNameMismatch"),
            backend.restore(replaced, ItemStateJournalPresentation("Managed", false, null, false)),
        )
        assertEquals(null, replaced.managedDisplayName)
    }

    @Test
    fun `converts platform write failure into an explicit result`() {
        val backend = BukkitPresentationBackend(BukkitItemTargetResolver { ThrowingTarget() })

        val result = backend.present(ItemPresentation(entityId, "Stone", true))

        assertEquals(
            "IllegalStateException",
            assertInstanceOf(PresentationResult.Failed::class.java, result).errorType,
        )
    }

    @Test
    fun `falls back to loaded world targets when direct entity lookup is temporarily missing`() {
        val expected = TestEntity(entityId)
        val unrelated = TestEntity(UUID.fromString("00000000-0000-0000-0000-000000000002"))

        assertEquals(
            expected,
            resolvePresentationTarget(
                entityId = entityId,
                direct = null,
                entityIdOf = TestEntity::entityId,
                loadedTargets = { sequenceOf(unrelated, expected) },
            ),
        )
    }

    @Test
    fun `prefers a matching direct entity without scanning loaded world targets`() {
        val direct = TestEntity(entityId)
        var loadedTargetsRead = false

        assertEquals(
            direct,
            resolvePresentationTarget(
                entityId = entityId,
                direct = direct,
                entityIdOf = TestEntity::entityId,
                loadedTargets = {
                    loadedTargetsRead = true
                    emptySequence()
                },
            ),
        )
        assertEquals(false, loadedTargetsRead)
    }

    private open class FakeTarget : BukkitItemNameTarget {
        override var customName: String? = null
        override var customNameVisible: Boolean = false
        override var managedDisplayName: String? = null
        override var originalName: String? = null
        override var originalNameWasPresent: Boolean? = null
        override var originalNameVisible: Boolean? = null
    }

    private class ThrowingTarget : FakeTarget() {
        override var customName: String?
            get() = null
            set(
                @Suppress("UNUSED_PARAMETER") value,
            ) {
                throw IllegalStateException("test failure")
            }
    }

    private data class TestEntity(
        val entityId: UUID,
    )
}

private inline fun <reified T> proxy(crossinline invocation: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, arguments ->
        invocation(method.name, arguments ?: emptyArray())
    } as T

private fun defaultValue(
    type: Class<*>,
    methodName: String,
): Any? =
    when (methodName) {
        "toString" -> "Proxy<${type.simpleName}>"
        "hashCode" -> System.identityHashCode(type)
        "equals" -> false
        else -> null
    }
