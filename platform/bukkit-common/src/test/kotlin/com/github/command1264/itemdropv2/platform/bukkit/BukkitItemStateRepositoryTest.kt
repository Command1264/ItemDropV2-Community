package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.DisplayTemplate
import com.github.command1264.itemdropv2.core.DisplayTemplateParseResult
import com.github.command1264.itemdropv2.core.ItemDisplaySettings
import com.github.command1264.itemdropv2.core.ItemDisplaySettingsRepository
import com.github.command1264.itemdropv2.core.ItemLifetimeRegistrationOutcome
import com.github.command1264.itemdropv2.core.ItemLifetimeService
import com.github.command1264.itemdropv2.core.ItemOwnership
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateDurabilityOutcome
import com.github.command1264.itemdropv2.core.ItemStateLoadResult
import com.github.command1264.itemdropv2.core.ItemStateWriteResult
import com.github.command1264.itemdropv2.core.LegacyItemState
import com.github.command1264.itemdropv2.core.RevisionedItemStateWriteResult
import com.github.command1264.itemdropv2.core.VirtualItemAmount
import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.persistence.PersistentDataAdapterContext
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.util.UUID

@Suppress("LargeClass")
class BukkitItemStateRepositoryTest {
    @Test
    fun `falls back to loaded world items when direct UUID lookup misses a live item`() {
        val expected = item(ENTITY_UUID)
        val unrelated = item(OTHER_ENTITY_UUID)

        assertSame(
            expected,
            findItemByUuid(ENTITY_UUID, direct = null) { sequenceOf(unrelated, expected) },
        )
    }

    @Test
    fun `does not scan loaded world items when direct UUID lookup succeeds`() {
        val expected = item(ENTITY_UUID)

        assertSame(
            expected,
            findItemByUuid(ENTITY_UUID, direct = expected) {
                error("loaded world fallback must stay lazy")
            },
        )
    }

    @Test
    fun `transient target lease supports entity PDC writes until released`() {
        val container = FakePersistentDataContainer()
        val repository = BukkitItemStateRepository({ null }, { true })
        val lease = repository.leaseTransientTarget(item(ENTITY_UUID, container))
        val state = ItemState(null, elapsedLifetimeSeconds = 3, originalLifetimeSeconds = 300)

        assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, state))
        assertEquals(state, assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID)).state)
        lease.release()
        assertEquals(ItemStateLoadResult.MissingTarget, repository.load(ENTITY_UUID))
    }

    @Test
    fun `transient target write is synchronized to a replacement canonical entity wrapper`() {
        val transient = FakePersistentDataContainer()
        val canonical = FakePersistentDataContainer()
        val repository = BukkitItemStateRepository({ canonical }, { true })
        val lease = repository.leaseTransientTarget(item(ENTITY_UUID, transient))
        val state =
            ItemState(
                ItemOwnership(OWNER_UUID, 30),
                elapsedLifetimeSeconds = 0,
                originalLifetimeSeconds = 300,
            )

        assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, state))
        lease.release()

        assertEquals(
            state,
            assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID)).state,
        )
    }

    @Test
    fun `round trips schema eight with a monotonically increasing revision`() {
        val container = FakePersistentDataContainer()
        val repository = repository(container)
        val state =
            ItemState(
                ownership = ItemOwnership(OWNER_UUID, protectionSecondsRemaining = 9, listOf(OWNER_UUID, OTHER_UUID)),
                elapsedLifetimeSeconds = 120,
                originalLifetimeSeconds = 300,
            )

        assertEquals(RevisionedItemStateWriteResult.Applied(1), repository.saveRevisioned(ENTITY_UUID, state))
        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))

        assertEquals(state, loaded.state)
        assertFalse(loaded.requiresSchemaUpgrade)
        assertFalse(container.contains(key("amount")))
        assertEquals(1L, loaded.revision)
        assertEquals(8, container.get(key("state-schema-version"), PersistentDataType.INTEGER))
        assertEquals(1L, container.get(key("state-revision"), PersistentDataType.LONG))
        assertEquals(300L, container.get(key("state-original-lifetime-seconds"), PersistentDataType.LONG))
        assertEquals(120L, container.get(key("state-elapsed-lifetime-seconds"), PersistentDataType.LONG))
        assertFalse(container.contains(key("state-remaining-lifetime-seconds")))
        assertFalse(container.contains(key("state-age-seconds")))
        assertFalse(container.contains(key("state-lifetime-seconds")))
        assertEquals(
            "$OWNER_UUID,$OTHER_UUID",
            container.get(key("state-eligible-owner-uuids"), PersistentDataType.STRING),
        )

        assertEquals(RevisionedItemStateWriteResult.Applied(2), repository.saveRevisioned(ENTITY_UUID, state))
        assertEquals(2L, container.get(key("state-revision"), PersistentDataType.LONG))
    }

    @Test
    fun `schema seven loads as revision zero and upgrades to schema eight revision one`() {
        val container =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 7)
                set(key("state-original-lifetime-seconds"), PersistentDataType.LONG, 300L)
                set(key("state-elapsed-lifetime-seconds"), PersistentDataType.LONG, 120L)
            }
        val repository = repository(container)

        val legacy = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))
        assertEquals(0L, legacy.revision)
        assertTrue(legacy.requiresSchemaUpgrade)

        assertEquals(RevisionedItemStateWriteResult.Applied(1), repository.saveRevisioned(ENTITY_UUID, legacy.state))
        val upgraded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))
        assertEquals(1L, upgraded.revision)
        assertFalse(upgraded.requiresSchemaUpgrade)
    }

    @Test
    fun `schema eight rejects missing nonpositive and wrong type revisions`() {
        val invalidRevisions = listOf(null, 0L, -1L, "1")

        invalidRevisions.forEach { revision ->
            val container =
                FakePersistentDataContainer().apply {
                    set(key("state-schema-version"), PersistentDataType.INTEGER, 8)
                    set(key("state-elapsed-lifetime-seconds"), PersistentDataType.LONG, 0L)
                    when (revision) {
                        is Long -> set(key("state-revision"), PersistentDataType.LONG, revision)
                        is String -> set(key("state-revision"), PersistentDataType.STRING, revision)
                    }
                }

            assertInstanceOf(ItemStateLoadResult.Invalid::class.java, repository(container).load(ENTITY_UUID))
        }
    }

    @Test
    fun `canonical synchronization and fallback repair preserve one allocated revision`() {
        val transient = FakePersistentDataContainer()
        var canonical: PersistentDataContainer = FakePersistentDataContainer()
        val fallback =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 4)
                set(key("state-age-seconds"), PersistentDataType.LONG, 1L)
                set(key("state-lifetime-seconds"), PersistentDataType.LONG, 300L)
            }
        val repository =
            BukkitItemStateRepository(
                targetResolver = { canonical },
                primaryThreadCheck = { true },
                fallbackTargetResolver = {
                    PersistentFallbackTarget(fallback) {
                        canonical = FakePersistentDataContainer()
                    }
                },
            )
        val lease = repository.leaseTransientTarget(item(ENTITY_UUID, transient))

        assertEquals(
            RevisionedItemStateWriteResult.Applied(1),
            repository.saveRevisioned(ENTITY_UUID, ItemState(null, 2, 300)),
        )
        lease.release()

        assertEquals(1L, transient.get(key("state-revision"), PersistentDataType.LONG))
        assertEquals(1L, canonical.get(key("state-revision"), PersistentDataType.LONG))
    }

    @Test
    fun `allocates after the newest wrapper revision and validates all wrappers before mutation`() {
        val transient = currentContainer(revision = 3)
        val canonical = currentContainer(revision = 11)
        val repository = BukkitItemStateRepository({ canonical }, { true })
        val lease = repository.leaseTransientTarget(item(ENTITY_UUID, transient))

        assertEquals(
            RevisionedItemStateWriteResult.Applied(12),
            repository.saveRevisioned(ENTITY_UUID, ItemState(null, 2, 300)),
        )
        assertEquals(12L, transient.get(key("state-revision"), PersistentDataType.LONG))
        assertEquals(12L, canonical.get(key("state-revision"), PersistentDataType.LONG))
        lease.release()

        val untouchedTransient = currentContainer(revision = 4)
        val unsupportedCanonical =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 9)
            }
        val before = untouchedTransient.snapshot()
        val rejecting = BukkitItemStateRepository({ unsupportedCanonical }, { true })
        val rejectingLease = rejecting.leaseTransientTarget(item(ENTITY_UUID, untouchedTransient))

        assertEquals(
            RevisionedItemStateWriteResult.Rejected("UnsupportedSchema:9"),
            rejecting.saveRevisioned(ENTITY_UUID, ItemState(null, 2, 300)),
        )
        assertEquals(before, untouchedTransient.snapshot())
        rejectingLease.release()
    }

    @Test
    fun `revision exhaustion and immediate expiry reject without mutating existing state`() {
        val exhausted = currentContainer(revision = Long.MAX_VALUE)
        val expiring = currentContainer(revision = 7)
        val exhaustedBefore = exhausted.snapshot()
        val expiringBefore = expiring.snapshot()

        assertEquals(
            RevisionedItemStateWriteResult.Rejected("RevisionExhausted"),
            repository(exhausted).saveRevisioned(ENTITY_UUID, ItemState(null, 1, 300)),
        )
        assertEquals(
            RevisionedItemStateWriteResult.Rejected("ImmediateExpiryMustNotBePersisted"),
            repository(expiring).saveRevisioned(ENTITY_UUID, ItemState(null, 300, 300)),
        )
        assertEquals(exhaustedBefore, exhausted.snapshot())
        assertEquals(expiringBefore, expiring.snapshot())
    }

    @Test
    fun `journal gate accepts the allocated revision before any PDC mutation`() {
        val container = FakePersistentDataContainer()
        val entity = item(ENTITY_UUID, container)
        var observedRevision: Long? = null
        val repository =
            BukkitItemStateRepository(
                targetResolver = { container },
                primaryThreadCheck = { true },
                itemResolver = { entity },
                revisionGate =
                    ItemStateRevisionDurabilityGate { item, _, revision ->
                        assertSame(entity, item)
                        assertTrue(container.isEmpty)
                        observedRevision = revision
                        ItemStateDurabilityOutcome.Accepted(false)
                    },
            )

        assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, ItemState(null, 1, 300)))
        assertEquals(1L, observedRevision)
        assertEquals(1L, container.get(key("state-revision"), PersistentDataType.LONG))
    }

    @Test
    fun `allocates after a newer journal revision when the canonical PDC wrapper is stale`() {
        val container = FakePersistentDataContainer()
        val entity = item(ENTITY_UUID, container)
        var preparedRevision: Long? = null
        val gate =
            object : ItemStateRevisionDurabilityGate {
                override fun latestRevision(item: Item): Long {
                    assertSame(entity, item)
                    return 3L
                }

                override fun prepare(
                    item: Item,
                    state: ItemState,
                    revision: Long,
                ): ItemStateDurabilityOutcome {
                    preparedRevision = revision
                    return if (revision > 3L) {
                        ItemStateDurabilityOutcome.Accepted(false)
                    } else {
                        ItemStateDurabilityOutcome.Rejected("StaleRevision")
                    }
                }
            }
        val repository =
            BukkitItemStateRepository(
                targetResolver = { container },
                primaryThreadCheck = { true },
                itemResolver = { entity },
                revisionGate = gate,
            )

        assertEquals(
            RevisionedItemStateWriteResult.Applied(4),
            repository.saveRevisioned(ENTITY_UUID, ItemState(null, 1, 300)),
        )
        assertEquals(4L, preparedRevision)
        assertEquals(4L, container.get(key("state-revision"), PersistentDataType.LONG))
    }

    @Test
    fun `rejects without mutation when the journal revision is exhausted`() {
        val container = FakePersistentDataContainer()
        val entity = item(ENTITY_UUID, container)
        var prepareCalled = false
        val gate =
            object : ItemStateRevisionDurabilityGate {
                override fun latestRevision(item: Item): Long = Long.MAX_VALUE

                override fun prepare(
                    item: Item,
                    state: ItemState,
                    revision: Long,
                ): ItemStateDurabilityOutcome {
                    prepareCalled = true
                    return ItemStateDurabilityOutcome.Accepted(false)
                }
            }
        val repository =
            BukkitItemStateRepository(
                targetResolver = { container },
                primaryThreadCheck = { true },
                itemResolver = { entity },
                revisionGate = gate,
            )

        assertEquals(
            RevisionedItemStateWriteResult.Rejected("Journal:RevisionExhausted"),
            repository.saveRevisioned(ENTITY_UUID, ItemState(null, 1, 300)),
        )
        assertFalse(prepareCalled)
        assertTrue(container.isEmpty)
    }

    @Test
    fun `journal rejection leaves PDC untouched while exact recovery bypasses the gate`() {
        val container = FakePersistentDataContainer()
        val entity = item(ENTITY_UUID, container)
        var gateCalls = 0
        val repository =
            BukkitItemStateRepository(
                targetResolver = { container },
                primaryThreadCheck = { true },
                itemResolver = { entity },
                revisionGate =
                    ItemStateRevisionDurabilityGate { _, _, _ ->
                        gateCalls++
                        ItemStateDurabilityOutcome.Rejected("QueueCapacityExceeded")
                    },
            )
        val state = ItemState(null, 1, 300)

        assertEquals(
            ItemStateWriteResult.Rejected("Journal:QueueCapacityExceeded"),
            repository.save(ENTITY_UUID, state),
        )
        assertTrue(container.isEmpty)
        assertEquals(RevisionedItemStateWriteResult.Applied(9), repository.restoreRevisioned(entity, state, 9))
        assertEquals(1, gateCalls)
        assertEquals(9L, container.get(key("state-revision"), PersistentDataType.LONG))
    }

    @Test
    fun `schema eight preserves forever and Long max original lifetime values`() {
        listOf(-1L, Long.MAX_VALUE).forEach { lifetime ->
            val container = FakePersistentDataContainer()
            val repository = repository(container)
            val state = ItemState(null, remainingLifetimeSeconds = lifetime)

            assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, state))
            val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))

            assertEquals(state, loaded.state)
            assertFalse(loaded.requiresSchemaUpgrade)
        }
    }

    @Test
    fun `schema six remaining lifetime migrates without extending finite death time`() {
        val container =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 6)
                set(key("state-remaining-lifetime-seconds"), PersistentDataType.LONG, 180L)
            }
        val repository = repository(container)
        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))

        assertTrue(loaded.requiresSchemaUpgrade)
        assertEquals(180L, loaded.remainingSecondsForLifetimeMigration)
        assertEquals(
            ItemLifetimeRegistrationOutcome.Registered(
                ItemState(
                    ownership = null,
                    elapsedLifetimeSeconds = 120,
                    originalLifetimeSeconds = 300,
                ),
            ),
            lifetimeService(repository).register(ENTITY_UUID),
        )
        assertEquals(8, container.get(key("state-schema-version"), PersistentDataType.INTEGER))
        assertEquals(300L, container.get(key("state-original-lifetime-seconds"), PersistentDataType.LONG))
        assertEquals(120L, container.get(key("state-elapsed-lifetime-seconds"), PersistentDataType.LONG))
        assertFalse(container.contains(key("state-remaining-lifetime-seconds")))
    }

    @Test
    fun `schema seven requires nonexpired elapsed lifetime long`() {
        val missingElapsed =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 7)
                set(key("state-original-lifetime-seconds"), PersistentDataType.LONG, 300L)
            }
        val expired =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 7)
                set(key("state-original-lifetime-seconds"), PersistentDataType.LONG, 300L)
                set(key("state-elapsed-lifetime-seconds"), PersistentDataType.LONG, 300L)
            }

        assertEquals(
            listOf("itemdropv2:state-elapsed-lifetime-seconds: required LONG is missing"),
            invalidErrors(repository(missingElapsed).load(ENTITY_UUID)),
        )
        assertEquals(
            listOf("itemdropv2:state-elapsed-lifetime-seconds: expired state must not be persisted"),
            invalidErrors(repository(expired).load(ENTITY_UUID)),
        )
    }

    @Test
    fun `round trips schema eight virtual amount as entity long`() {
        val container = FakePersistentDataContainer()
        val repository = repository(container)
        val state =
            ItemState(
                ownership = ItemOwnership(OWNER_UUID, 30),
                elapsedLifetimeSeconds = 120,
                originalLifetimeSeconds = Long.MAX_VALUE,
                virtualAmount = VirtualItemAmount.of(Long.MAX_VALUE),
            )

        assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, state))
        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))

        assertEquals(state, loaded.state)
        assertFalse(loaded.requiresSchemaUpgrade)
        assertEquals(8, container.get(key("state-schema-version"), PersistentDataType.INTEGER))
        assertEquals(Long.MAX_VALUE, container.get(key("state-virtual-amount"), PersistentDataType.LONG))
        assertFalse(container.contains(key("amount")))
    }

    @Test
    fun `schema five requires positive long virtual amount`() {
        val missing =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 5)
                set(key("state-age-seconds"), PersistentDataType.LONG, 0L)
            }
        val wrongType =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 5)
                set(key("state-age-seconds"), PersistentDataType.LONG, 0L)
                set(key("state-virtual-amount"), PersistentDataType.STRING, "8192")
            }
        val zero =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 5)
                set(key("state-age-seconds"), PersistentDataType.LONG, 0L)
                set(key("state-virtual-amount"), PersistentDataType.LONG, 0L)
            }

        assertEquals(
            listOf("itemdropv2:state-virtual-amount: required LONG is missing"),
            invalidErrors(repository(missing).load(ENTITY_UUID)),
        )
        assertEquals(
            listOf("itemdropv2:state-virtual-amount: expected LONG"),
            invalidErrors(repository(wrongType).load(ENTITY_UUID)),
        )
        assertEquals(
            listOf("itemdropv2:state-virtual-amount: must be positive"),
            invalidErrors(repository(zero).load(ENTITY_UUID)),
        )
    }

    @Test
    fun `reads valid legacy values without rewriting them`() {
        val container =
            FakePersistentDataContainer().apply {
                set(key("age"), PersistentDataType.LONG, 30L)
                set(key("amount"), PersistentDataType.LONG, 4L)
                set(key("owner"), PersistentDataType.STRING, OWNER_UUID.toString())
                set(key("ownertime"), PersistentDataType.LONG, 15L)
            }
        val before = container.snapshot()

        val loaded =
            assertInstanceOf(
                ItemStateLoadResult.Legacy::class.java,
                repository(container).load(ENTITY_UUID),
            )

        assertEquals(LegacyItemState(30, 4, OWNER_UUID, 15), loaded.state)
        assertEquals(before, container.snapshot())
    }

    @Test
    fun `loads schema one ticks and epoch protection as schema two seconds`() {
        val container =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 1)
                set(key("state-age-ticks"), PersistentDataType.LONG, 41L)
                set(key("state-lifetime-ticks"), PersistentDataType.LONG, 101L)
                set(key("state-owner-uuid"), PersistentDataType.STRING, OWNER_UUID.toString())
                set(key("state-protection-until-epoch-millis"), PersistentDataType.LONG, 25_001L)
            }
        val repository = BukkitItemStateRepository({ container }, { true }, { 10_000L })

        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))

        assertEquals(
            ItemState(
                ItemOwnership(OWNER_UUID, 16),
                elapsedLifetimeSeconds = 2,
                originalLifetimeSeconds = 6,
            ),
            loaded.state,
        )
        assertTrue(loaded.requiresSchemaUpgrade)
    }

    @Test
    fun `loads schema two as requiring a schema three rewrite`() {
        val container =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 2)
                set(key("state-age-seconds"), PersistentDataType.LONG, 42L)
                set(key("state-owner-uuid"), PersistentDataType.STRING, OWNER_UUID.toString())
                set(key("state-protection-seconds-remaining"), PersistentDataType.LONG, 12L)
            }

        val loaded =
            assertInstanceOf(
                ItemStateLoadResult.Loaded::class.java,
                repository(container).load(ENTITY_UUID),
            )

        assertEquals(
            ItemState(
                ItemOwnership(OWNER_UUID, 12),
                elapsedLifetimeSeconds = 42,
                originalLifetimeSeconds = null,
            ),
            loaded.state,
        )
        assertTrue(loaded.requiresSchemaUpgrade)
    }

    @Test
    fun `lifetime recovery rewrites schema one two and incomplete three to schema eight`() {
        val schemaOne =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 1)
                set(key("state-age-ticks"), PersistentDataType.LONG, 2_400L)
            }
        val schemaTwo =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 2)
                set(key("state-age-seconds"), PersistentDataType.LONG, 130L)
            }
        val schemaThree =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 3)
                set(key("state-age-seconds"), PersistentDataType.LONG, 140L)
            }

        listOf(schemaOne to 120L, schemaTwo to 130L, schemaThree to 140L).forEach { (container, expectedAge) ->
            val outcome = lifetimeService(repository(container)).register(ENTITY_UUID)

            assertInstanceOf(ItemLifetimeRegistrationOutcome.Registered::class.java, outcome)
            assertEquals(8, container.get(key("state-schema-version"), PersistentDataType.INTEGER))
            assertEquals(300L, container.get(key("state-original-lifetime-seconds"), PersistentDataType.LONG))
            assertEquals(expectedAge, container.get(key("state-elapsed-lifetime-seconds"), PersistentDataType.LONG))
            assertFalse(container.contains(key("state-remaining-lifetime-seconds")))
            assertFalse(container.contains(key("state-age-seconds")))
            assertFalse(container.contains(key("state-lifetime-seconds")))
            assertFalse(container.contains(key("state-age-ticks")))
            assertFalse(container.contains(key("state-lifetime-ticks")))
            assertFalse(container.contains(key("state-protection-until-epoch-millis")))
        }
    }

    @Test
    fun `reports wrong types malformed uuid negative values and partial ownership`() {
        val wrongType = FakePersistentDataContainer().apply { set(key("age"), PersistentDataType.STRING, "30") }
        val malformedOwner =
            FakePersistentDataContainer().apply {
                set(key("owner"), PersistentDataType.STRING, "not-a-uuid")
                set(key("ownertime"), PersistentDataType.LONG, -1L)
            }
        val partialOwnership =
            FakePersistentDataContainer().apply {
                set(key("age"), PersistentDataType.LONG, 0L)
                set(key("owner"), PersistentDataType.STRING, OWNER_UUID.toString())
            }

        assertEquals(
            listOf("itemdropv2:age: expected LONG"),
            invalidErrors(repository(wrongType).load(ENTITY_UUID)),
        )
        assertEquals(
            listOf(
                "itemdropv2:owner: expected UUID string",
                "itemdropv2:ownertime: must not be negative",
            ),
            invalidErrors(repository(malformedOwner).load(ENTITY_UUID)),
        )
        assertEquals(
            listOf("legacy owner and ownertime must either both be present or both be absent"),
            invalidErrors(repository(partialOwnership).load(ENTITY_UUID)),
        )
    }

    @Test
    fun `rejects unsupported and incomplete new schema safely`() {
        val unsupported =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 9)
            }
        val missingAge =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 2)
            }
        val payloadWithoutSchema =
            FakePersistentDataContainer().apply {
                set(key("state-age-ticks"), PersistentDataType.LONG, 20L)
            }
        val wrongSchemaType =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.STRING, "1")
            }

        assertEquals(
            ItemStateLoadResult.UnsupportedSchema(9),
            repository(unsupported).load(ENTITY_UUID),
        )
        assertEquals(
            listOf("itemdropv2:state-age-seconds: required LONG is missing"),
            invalidErrors(repository(missingAge).load(ENTITY_UUID)),
        )
        assertEquals(
            listOf("new item state payload exists without state-schema-version"),
            invalidErrors(repository(payloadWithoutSchema).load(ENTITY_UUID)),
        )
        assertEquals(
            listOf("itemdropv2:state-schema-version: expected INTEGER"),
            invalidErrors(repository(wrongSchemaType).load(ENTITY_UUID)),
        )
    }

    @Test
    fun `rejects invalid new state uuid time and overflow boundaries`() {
        val container =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 2)
                set(key("state-age-seconds"), PersistentDataType.LONG, Long.MAX_VALUE)
                set(key("state-lifetime-seconds"), PersistentDataType.LONG, 0L)
                set(key("state-owner-uuid"), PersistentDataType.STRING, "not-a-uuid")
                set(key("state-protection-seconds-remaining"), PersistentDataType.LONG, 0L)
            }

        assertEquals(
            listOf(
                "itemdropv2:state-owner-uuid: expected UUID string",
                "itemdropv2:state-age-seconds: exceeds supported time range",
                "itemdropv2:state-lifetime-seconds: must be positive",
                "itemdropv2:state-protection-seconds-remaining: must be positive",
            ),
            invalidErrors(repository(container).load(ENTITY_UUID)),
        )
    }

    @Test
    fun `rejects partial and duplicate shared ownership lists`() {
        val partial =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 3)
                set(key("state-age-seconds"), PersistentDataType.LONG, 0L)
                set(key("state-owner-uuid"), PersistentDataType.STRING, OWNER_UUID.toString())
                set(key("state-protection-seconds-remaining"), PersistentDataType.LONG, 30L)
            }
        val duplicate =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 3)
                set(key("state-age-seconds"), PersistentDataType.LONG, 0L)
                set(key("state-owner-uuid"), PersistentDataType.STRING, OWNER_UUID.toString())
                set(key("state-eligible-owner-uuids"), PersistentDataType.STRING, "$OWNER_UUID,$OWNER_UUID")
                set(key("state-protection-seconds-remaining"), PersistentDataType.LONG, 30L)
            }

        assertEquals(
            listOf("state owner, eligible owners and protection must either all be present or all be absent"),
            invalidErrors(repository(partial).load(ENTITY_UUID)),
        )
        assertEquals(
            listOf("itemdropv2:state-eligible-owner-uuids: must not contain duplicate UUIDs"),
            invalidErrors(repository(duplicate).load(ENTITY_UUID)),
        )
    }

    @Test
    fun `does not overwrite unsupported or invalid existing new state`() {
        val unsupported =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 9)
                set(key("state-age-ticks"), PersistentDataType.LONG, 20L)
            }
        val invalid =
            FakePersistentDataContainer().apply {
                set(key("state-age-ticks"), PersistentDataType.LONG, 20L)
            }
        val unsupportedBefore = unsupported.snapshot()
        val invalidBefore = invalid.snapshot()
        val replacement =
            ItemState(
                null,
                elapsedLifetimeSeconds = 0,
                originalLifetimeSeconds = null,
            )

        assertEquals(
            ItemStateWriteResult.Rejected("UnsupportedSchema:9"),
            repository(unsupported).save(ENTITY_UUID, replacement),
        )
        assertEquals(
            ItemStateWriteResult.Rejected("InvalidExistingState"),
            repository(invalid).save(ENTITY_UUID, replacement),
        )
        assertEquals(unsupportedBefore, unsupported.snapshot())
        assertEquals(invalidBefore, invalid.snapshot())
    }

    @Test
    fun `removes stale optional new values while preserving legacy keys`() {
        val container =
            FakePersistentDataContainer().apply {
                set(key("owner"), PersistentDataType.STRING, OWNER_UUID.toString())
                set(key("ownertime"), PersistentDataType.LONG, 20L)
            }
        val repository = repository(container)
        repository.save(
            ENTITY_UUID,
            ItemState(
                ItemOwnership(OWNER_UUID, 10),
                elapsedLifetimeSeconds = 40,
                originalLifetimeSeconds = 300,
            ),
        )

        assertEquals(
            ItemStateWriteResult.Applied,
            repository.save(
                ENTITY_UUID,
                ItemState(
                    null,
                    elapsedLifetimeSeconds = 50,
                    originalLifetimeSeconds = null,
                ),
            ),
        )

        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))
        assertEquals(
            ItemState(
                null,
                elapsedLifetimeSeconds = 50,
                originalLifetimeSeconds = null,
            ),
            loaded.state,
        )
        assertTrue(container.contains(key("owner")))
        assertTrue(container.contains(key("ownertime")))
        assertFalse(container.contains(key("state-owner-uuid")))
        assertFalse(container.contains(key("state-eligible-owner-uuids")))
        assertFalse(container.contains(key("state-protection-seconds-remaining")))
        assertFalse(container.contains(key("state-lifetime-seconds")))
    }

    @Test
    fun `does not access Bukkit state off the main thread`() {
        var resolved = false
        val entity = item(ENTITY_UUID, FakePersistentDataContainer())
        val repository =
            BukkitItemStateRepository(
                targetResolver = {
                    resolved = true
                    FakePersistentDataContainer()
                },
                primaryThreadCheck = { false },
            )

        assertEquals(
            ItemStateLoadResult.Failed("OffMainThread"),
            repository.load(ENTITY_UUID),
        )
        assertEquals(
            ItemStateWriteResult.Failed("OffMainThread"),
            repository.save(ENTITY_UUID, ItemState(null, remainingLifetimeSeconds = null)),
        )
        assertEquals(ItemStateWriteResult.Failed("OffMainThread"), repository.clearItemState(entity))
        assertFalse(resolved)
    }

    @Test
    fun `clears entity and fallback state before a consumed item is removed`() {
        val entity = FakePersistentDataContainer()
        val fallback = FakePersistentDataContainer()
        var fallbackCommits = 0
        val repository =
            BukkitItemStateRepository(
                targetResolver = { entity },
                primaryThreadCheck = { true },
                fallbackTargetResolver = {
                    PersistentFallbackTarget(fallback) {
                        fallbackCommits += 1
                    }
                },
            )
        val state = ItemState(null, remainingLifetimeSeconds = 300, virtualAmount = VirtualItemAmount.of(32))
        assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, state))
        fallback.set(key("state-schema-version"), PersistentDataType.INTEGER, 6)
        fallback.set(key("state-virtual-amount"), PersistentDataType.LONG, 32L)

        val result = repository.clearItemState(item(ENTITY_UUID, entity))

        assertEquals(ItemStateWriteResult.Applied, result)
        assertTrue(entity.isEmpty)
        assertTrue(fallback.isEmpty)
        assertEquals(1, fallbackCommits)
    }

    @Test
    fun `discards durable state before clearing entity state`() {
        val entity = FakePersistentDataContainer()
        val item = item(ENTITY_UUID, entity)
        val discarded = mutableListOf<UUID>()
        val gate =
            object : ItemStateRevisionDurabilityGate {
                override fun prepare(
                    item: Item,
                    state: ItemState,
                    revision: Long,
                ): ItemStateDurabilityOutcome = ItemStateDurabilityOutcome.Accepted(false)

                override fun discard(item: Item): ItemStateDurabilityOutcome {
                    discarded += item.uniqueId
                    return ItemStateDurabilityOutcome.Accepted(false)
                }
            }
        val repository =
            BukkitItemStateRepository(
                targetResolver = { entity },
                primaryThreadCheck = { true },
                itemResolver = { item },
                revisionGate = gate,
            )
        assertEquals(
            ItemStateWriteResult.Applied,
            repository.save(ENTITY_UUID, ItemState(null, remainingLifetimeSeconds = 300)),
        )

        assertEquals(ItemStateWriteResult.Applied, repository.clearItemState(item))

        assertEquals(listOf(ENTITY_UUID), discarded)
        assertTrue(entity.isEmpty)
    }

    @Test
    fun `returns missing target and explicit storage failures`() {
        val missing = BukkitItemStateRepository({ null }, { true })
        val failing = repository(FakePersistentDataContainer(failOnWrite = true))

        assertEquals(ItemStateLoadResult.MissingTarget, missing.load(ENTITY_UUID))
        assertEquals(
            ItemStateWriteResult.MissingTarget,
            missing.save(ENTITY_UUID, ItemState(null, remainingLifetimeSeconds = null)),
        )
        assertEquals(
            ItemStateWriteResult.Failed("IllegalStateException"),
            failing.save(ENTITY_UUID, ItemState(null, remainingLifetimeSeconds = null)),
        )
    }

    @Test
    fun `reads item meta fallback when entity state was not persisted`() {
        val entity = FakePersistentDataContainer()
        val fallback =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 1)
                set(key("state-age-ticks"), PersistentDataType.LONG, 2_400L)
            }
        val repository =
            BukkitItemStateRepository(
                targetResolver = { entity },
                primaryThreadCheck = { true },
                fallbackTargetResolver = { PersistentFallbackTarget(fallback) {} },
            )

        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))

        assertEquals(
            ItemState(
                null,
                elapsedLifetimeSeconds = 120,
                originalLifetimeSeconds = null,
            ),
            loaded.state,
        )
        assertTrue(loaded.requiresSchemaUpgrade)
    }

    @Test
    fun `current item meta fallback is marked for migration to the entity`() {
        val entity = FakePersistentDataContainer()
        val fallback = FakePersistentDataContainer()
        val state = ItemState(null, elapsedLifetimeSeconds = 120, originalLifetimeSeconds = 300)
        assertEquals(ItemStateWriteResult.Applied, repository(fallback).save(ENTITY_UUID, state))
        val repository =
            BukkitItemStateRepository(
                targetResolver = { entity },
                primaryThreadCheck = { true },
                fallbackTargetResolver = { PersistentFallbackTarget(fallback) {} },
            )

        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))

        assertEquals(state, loaded.state)
        assertTrue(loaded.requiresSchemaUpgrade)
    }

    @Test
    fun `duplicate current item meta state is marked for cleanup`() {
        val entity = FakePersistentDataContainer()
        val fallback = FakePersistentDataContainer()
        var commits = 0
        var fallbackResolutions = 0
        val state = ItemState(null, elapsedLifetimeSeconds = 120, originalLifetimeSeconds = 300)
        assertEquals(ItemStateWriteResult.Applied, repository(entity).save(ENTITY_UUID, state))
        assertEquals(ItemStateWriteResult.Applied, repository(fallback).save(ENTITY_UUID, state))
        val repository =
            BukkitItemStateRepository(
                targetResolver = { entity },
                primaryThreadCheck = { true },
                fallbackTargetResolver = {
                    fallbackResolutions++
                    PersistentFallbackTarget(fallback) { commits++ }
                },
            )

        val hotPathLoaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID))
        assertEquals(0, fallbackResolutions)
        val loaded = assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.loadForRegistration(ENTITY_UUID))

        assertEquals(1, fallbackResolutions)
        assertFalse(hotPathLoaded.requiresSchemaUpgrade)
        assertEquals(state, loaded.state)
        assertTrue(loaded.requiresSchemaUpgrade)
        assertEquals(ItemLifetimeRegistrationOutcome.Registered(state), lifetimeService(repository).register(ENTITY_UUID))
        assertTrue(fallback.isEmpty)
        assertEquals(1, commits)
    }

    @Test
    fun `writes current schema only to entity and leaves an empty item meta fallback untouched`() {
        val entity = FakePersistentDataContainer()
        val fallback = FakePersistentDataContainer()
        var commits = 0
        val repository =
            BukkitItemStateRepository(
                targetResolver = { entity },
                primaryThreadCheck = { true },
                fallbackTargetResolver = { PersistentFallbackTarget(fallback) { commits++ } },
            )
        val state = ItemState(null, 120, 300)

        assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, state))
        assertEquals(0, commits)
        assertEquals(8, entity.get(key("state-schema-version"), PersistentDataType.INTEGER))
        assertTrue(fallback.isEmpty)
    }

    @Test
    fun `clears an existing item meta fallback after saving current state to entity`() {
        val entity = FakePersistentDataContainer()
        val fallback =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 4)
                set(key("state-age-seconds"), PersistentDataType.LONG, 1L)
                set(key("state-lifetime-seconds"), PersistentDataType.LONG, 300L)
            }
        var commits = 0
        val repository =
            BukkitItemStateRepository(
                targetResolver = { entity },
                primaryThreadCheck = { true },
                fallbackTargetResolver = { PersistentFallbackTarget(fallback) { commits++ } },
            )

        assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, ItemState(null, 2, 300)))
        assertEquals(1, commits)
        assertTrue(fallback.isEmpty)
        assertEquals(300L, entity.get(key("state-original-lifetime-seconds"), PersistentDataType.LONG))
        assertEquals(2L, entity.get(key("state-elapsed-lifetime-seconds"), PersistentDataType.LONG))
    }

    @Test
    fun `does not clear primary state exposed through an aliased fallback container`() {
        val shared = FakePersistentDataContainer()
        var commits = 0
        val repository =
            BukkitItemStateRepository(
                targetResolver = { shared },
                primaryThreadCheck = { true },
                fallbackTargetResolver = { PersistentFallbackTarget(shared) { commits++ } },
            )

        assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, ItemState(null, 2, 300)))
        assertEquals(0, commits)
        assertEquals(8, shared.get(key("state-schema-version"), PersistentDataType.INTEGER))
        assertEquals(300L, shared.get(key("state-original-lifetime-seconds"), PersistentDataType.LONG))
        assertEquals(2L, shared.get(key("state-elapsed-lifetime-seconds"), PersistentDataType.LONG))
    }

    @Test
    fun `restores primary state when a preexisting aliased fallback is cleared`() {
        val shared =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 4)
                set(key("state-age-seconds"), PersistentDataType.LONG, 1L)
                set(key("state-lifetime-seconds"), PersistentDataType.LONG, 300L)
            }
        var commits = 0
        val repository =
            BukkitItemStateRepository(
                targetResolver = { shared },
                primaryThreadCheck = { true },
                fallbackTargetResolver = { PersistentFallbackTarget(shared) { commits++ } },
            )
        val ownedState = ItemState(ItemOwnership(OWNER_UUID, 30), 1, 300)

        assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, ownedState))
        assertEquals(1, commits)
        assertEquals(
            ownedState,
            assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID)).state,
        )
    }

    @Test
    fun `restores state to refreshed primary when fallback commit replaces entity container`() {
        val original =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 4)
                set(key("state-age-seconds"), PersistentDataType.LONG, 1L)
                set(key("state-lifetime-seconds"), PersistentDataType.LONG, 300L)
            }
        var current: PersistentDataContainer = original
        val repository =
            BukkitItemStateRepository(
                targetResolver = { current },
                primaryThreadCheck = { true },
                fallbackTargetResolver = {
                    PersistentFallbackTarget(original) {
                        current = FakePersistentDataContainer()
                    }
                },
            )
        val nextState = ItemState(null, 2, 300)

        assertEquals(ItemStateWriteResult.Applied, repository.save(ENTITY_UUID, nextState))
        assertEquals(
            nextState,
            assertInstanceOf(ItemStateLoadResult.Loaded::class.java, repository.load(ENTITY_UUID)).state,
        )
    }

    @Test
    fun `clears all current and legacy keys from picked up item fallback`() {
        val fallback =
            FakePersistentDataContainer().apply {
                set(key("state-schema-version"), PersistentDataType.INTEGER, 3)
                set(key("state-age-seconds"), PersistentDataType.LONG, 120L)
                set(key("state-lifetime-seconds"), PersistentDataType.LONG, 300L)
                set(key("state-virtual-amount"), PersistentDataType.LONG, 8_192L)
                set(key("owner"), PersistentDataType.STRING, OWNER_UUID.toString())
                set(key("ownertime"), PersistentDataType.LONG, 17L)
            }
        var commits = 0

        val result =
            repository(FakePersistentDataContainer())
                .clearFallback(PersistentFallbackTarget(fallback) { commits++ })

        assertEquals(ItemStateWriteResult.Applied, result)
        assertTrue(fallback.isEmpty)
        assertEquals(1, commits)
    }

    private fun repository(container: PersistentDataContainer): BukkitItemStateRepository =
        BukkitItemStateRepository({ container }, { true })

    private fun currentContainer(revision: Long): FakePersistentDataContainer =
        FakePersistentDataContainer().apply {
            set(key("state-schema-version"), PersistentDataType.INTEGER, 8)
            set(key("state-revision"), PersistentDataType.LONG, revision)
            set(key("state-original-lifetime-seconds"), PersistentDataType.LONG, 300L)
            set(key("state-elapsed-lifetime-seconds"), PersistentDataType.LONG, 1L)
        }

    private fun lifetimeService(repository: BukkitItemStateRepository): ItemLifetimeService {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        val settings = ItemDisplaySettings(true, emptySet(), template, template)
        return ItemLifetimeService(repository, ItemDisplaySettingsRepository { settings })
    }

    private fun invalidErrors(result: ItemStateLoadResult): List<String> =
        assertInstanceOf(ItemStateLoadResult.Invalid::class.java, result).errors

    private fun item(
        entityId: UUID,
        container: PersistentDataContainer? = null,
    ): Item =
        Proxy.newProxyInstance(
            Item::class.java.classLoader,
            arrayOf(Item::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getUniqueId" -> entityId
                "isValid" -> true
                "getPersistentDataContainer" -> container
                "toString" -> "Item($entityId)"
                else -> null
            }
        } as Item

    @Suppress("DEPRECATION")
    private fun key(value: String): NamespacedKey = NamespacedKey("itemdropv2", value)

    internal class FakePersistentDataContainer(
        private val failOnWrite: Boolean = false,
    ) : PersistentDataContainer {
        private val values = linkedMapOf<NamespacedKey, StoredValue>()

        override fun <T : Any, Z : Any> set(
            key: NamespacedKey,
            type: PersistentDataType<T, Z>,
            value: Z,
        ) {
            if (failOnWrite) throw IllegalStateException("write failed")
            values[key] = StoredValue(type, value)
        }

        override fun <T : Any, Z : Any> has(
            key: NamespacedKey,
            type: PersistentDataType<T, Z>,
        ): Boolean = values[key]?.type?.primitiveType == type.primitiveType

        @Suppress("UNCHECKED_CAST")
        override fun <T : Any, Z : Any> get(
            key: NamespacedKey,
            type: PersistentDataType<T, Z>,
        ): Z? = if (has(key, type)) values[key]?.value as Z else null

        override fun <T : Any, Z : Any> getOrDefault(
            key: NamespacedKey,
            type: PersistentDataType<T, Z>,
            defaultValue: Z,
        ): Z = get(key, type) ?: defaultValue

        override fun remove(key: NamespacedKey) {
            if (failOnWrite) throw IllegalStateException("write failed")
            values.remove(key)
        }

        override fun isEmpty(): Boolean = values.isEmpty()

        override fun getAdapterContext(): PersistentDataAdapterContext = throw UnsupportedOperationException("not required by this test")

        fun contains(key: NamespacedKey): Boolean = values.containsKey(key)

        fun snapshot(): Map<String, Pair<Class<*>, Any?>> =
            values.mapKeys { it.key.toString() }.mapValues { it.value.type.primitiveType to it.value.value }

        private data class StoredValue(
            val type: PersistentDataType<*, *>,
            val value: Any?,
        )
    }

    private companion object {
        private val ENTITY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val OTHER_ENTITY_UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val OWNER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000101")
        private val OTHER_UUID = UUID.fromString("00000000-0000-0000-0000-000000000102")
    }
}
