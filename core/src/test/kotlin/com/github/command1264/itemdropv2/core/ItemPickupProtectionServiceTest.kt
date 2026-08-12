package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class ItemPickupProtectionServiceTest {
    @Test
    fun `allows the owner and rejects another player while protection remains`() {
        val service = service(ItemState(ItemOwnership(OWNER_ID, 17), 0, 300))

        assertEquals(ItemPickupOutcome.Allowed, service.evaluate(request(PickupActor.Player(OWNER_ID, true, false))))
        assertEquals(
            ItemPickupOutcome.Denied(PickupDeniedReason.OTHER_OWNER, OWNER_ID, 17),
            service.evaluate(request(PickupActor.Player(OTHER_ID, true, false))),
        )
    }

    @Test
    fun `allows every eligible owner in a shared ownership group`() {
        val ownership = ItemOwnership(OWNER_ID, 17, listOf(OWNER_ID, OTHER_ID))
        val service = service(ItemState(ownership, 0, 300))

        assertEquals(ItemPickupOutcome.Allowed, service.evaluate(request(PickupActor.Player(OTHER_ID, true, false))))
    }

    @Test
    fun `denial preserves the complete ordered shared owner group`() {
        val ownership = ItemOwnership(OWNER_ID, 17, listOf(OWNER_ID, SECOND_OWNER_ID, THIRD_OWNER_ID))
        val service = service(ItemState(ownership, 0, 300))

        assertEquals(
            ItemPickupOutcome.Denied(
                PickupDeniedReason.OTHER_OWNER,
                OWNER_ID,
                17,
                listOf(OWNER_ID, SECOND_OWNER_ID, THIRD_OWNER_ID),
            ),
            service.evaluate(request(PickupActor.Player(OTHER_ID, true, false))),
        )
    }

    @Test
    fun `other pickup permission bypasses ownership but base permission is required`() {
        val service = service(ItemState(ItemOwnership(OWNER_ID, 17), 0, 300))

        assertEquals(
            ItemPickupOutcome.Allowed,
            service.evaluate(request(PickupActor.Player(OTHER_ID, true, true))),
        )
        assertEquals(
            ItemPickupOutcome.Denied(PickupDeniedReason.NO_PICKUP_PERMISSION),
            service.evaluate(request(PickupActor.Player(OWNER_ID, false, true))),
        )
    }

    @Test
    fun `unowned plugin item still requires base pickup permission`() {
        val service = service(ItemState(null, 0, 300))

        assertEquals(
            ItemPickupOutcome.Denied(PickupDeniedReason.NO_PICKUP_PERMISSION),
            service.evaluate(request(PickupActor.Player(OTHER_ID, false, false))),
        )
    }

    @Test
    fun `ordinary item and blocked world bypass plugin pickup rules`() {
        val absent = ItemPickupProtectionService(Repository(ItemStateLoadResult.Absent), settings())
        val blocked = service(ItemState(ItemOwnership(OWNER_ID, 17), 0, 300), blockedWorlds = setOf("disabled"))

        assertEquals(ItemPickupOutcome.Allowed, absent.evaluate(request(PickupActor.Player(OTHER_ID, false, false))))
        assertEquals(
            ItemPickupOutcome.Allowed,
            blocked.evaluate(request(PickupActor.Player(OTHER_ID, false, false), worldName = "disabled")),
        )
    }

    @Test
    fun `non-player entity and inventory cannot take protected item by default`() {
        val service = service(ItemState(ItemOwnership(OWNER_ID, 17), 0, 300))

        assertEquals(
            ItemPickupOutcome.Denied(PickupDeniedReason.OTHER_OWNER, OWNER_ID, 17),
            service.evaluate(request(PickupActor.NonPlayerEntity)),
        )
        assertEquals(
            ItemPickupOutcome.Denied(PickupDeniedReason.OTHER_OWNER, OWNER_ID, 17),
            service.evaluate(request(PickupActor.Inventory)),
        )
    }

    @Test
    fun `inventory setting permits protected item pickup`() {
        val service = service(ItemState(ItemOwnership(OWNER_ID, 17), 0, 300), allowHopperPickup = true)

        assertEquals(ItemPickupOutcome.Allowed, service.evaluate(request(PickupActor.Inventory)))
    }

    @Test
    fun `invalid or unreadable state fails closed`() {
        val invalid = ItemPickupProtectionService(Repository(ItemStateLoadResult.Invalid(listOf("bad uuid"))), settings())
        val failed = ItemPickupProtectionService(Repository(ItemStateLoadResult.Failed("StorageFailure")), settings())

        assertEquals(ItemPickupOutcome.Failed("InvalidState"), invalid.evaluate(request(PickupActor.NonPlayerEntity)))
        assertEquals(ItemPickupOutcome.Failed("StorageFailure"), failed.evaluate(request(PickupActor.Inventory)))
    }

    private fun service(
        state: ItemState,
        allowHopperPickup: Boolean = false,
        blockedWorlds: Set<String> = emptySet(),
    ): ItemPickupProtectionService =
        ItemPickupProtectionService(
            Repository(ItemStateLoadResult.Loaded(state)),
            settings(allowHopperPickup, blockedWorlds),
        )

    private fun settings(
        allowHopperPickup: Boolean = false,
        blockedWorlds: Set<String> = emptySet(),
    ): ItemDisplaySettingsRepository {
        val template = (DisplayTemplate.parse("%item_display_name%") as DisplayTemplateParseResult.Valid).template
        return ItemDisplaySettingsRepository {
            ItemDisplaySettings(
                enabled = true,
                blockedWorlds = blockedWorlds,
                singleItemTemplate = template,
                multipleItemTemplate = template,
                ownership =
                    ItemOwnershipSettings(
                        pickup = ItemPickupSettings(allowHopperPickup = allowHopperPickup),
                    ),
            )
        }
    }

    private fun request(
        actor: PickupActor,
        worldName: String = "world",
    ): ItemPickupRequest = ItemPickupRequest(ITEM_ID, worldName, actor)

    private class Repository(
        private val result: ItemStateLoadResult,
    ) : ItemStateRepository {
        override fun load(entityId: UUID): ItemStateLoadResult = result

        override fun save(
            entityId: UUID,
            state: ItemState,
        ): ItemStateWriteResult = error("unexpected save")
    }

    private companion object {
        private val ITEM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        private val OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002")
        private val OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003")
        private val SECOND_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000004")
        private val THIRD_OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000005")
    }
}
