package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class DisplayTemplateTest {
    @Test
    fun `parses and renders the supported placeholders`() {
        val result =
            DisplayTemplate.parse(
                "&f%item_display_name% &cx%amount% %owner% %owner_count% " +
                    "%other_owner_count% %protection_remaining% %lifetime_elapsed% " +
                    "%lifetime_remaining% %player_world%",
            )
        val valid = assertInstanceOf(DisplayTemplateParseResult.Valid::class.java, result)

        assertEquals(
            "&fDiamond &cx3 Steve 2 1 17 17 283 %player_world%",
            valid.template.render(
                "Diamond",
                3,
                ItemDisplayPlaceholderValues(
                    owner = "Steve",
                    ownerCount = 2,
                    otherOwnerCount = 1,
                    protectionRemaining = "17",
                    lifetimeElapsed = "17",
                    lifetimeRemaining = "283",
                ),
            ),
        )
    }

    @Test
    fun `rejects placeholders without PlaceholderAPI parameters and control characters`() {
        val unknown = DisplayTemplate.parse("%unknown%%item_display_name%")
        val missingIdentifier = DisplayTemplate.parse("%_parameter%%item_display_name%")
        val missingParameters = DisplayTemplate.parse("%identifier_%%item_display_name%")
        val control = DisplayTemplate.parse("%item_display_name%\n%amount%")

        assertEquals(
            listOf("unsupported placeholder: %unknown%"),
            assertInstanceOf(DisplayTemplateParseResult.Invalid::class.java, unknown).errors,
        )
        assertEquals(
            listOf("unsupported placeholder: %_parameter%"),
            assertInstanceOf(DisplayTemplateParseResult.Invalid::class.java, missingIdentifier).errors,
        )
        assertEquals(
            listOf("unsupported placeholder: %identifier_%"),
            assertInstanceOf(DisplayTemplateParseResult.Invalid::class.java, missingParameters).errors,
        )
        assertEquals(
            listOf("template must not contain control characters"),
            assertInstanceOf(DisplayTemplateParseResult.Invalid::class.java, control).errors,
        )
    }

    @Test
    fun `requires a bounded template containing the item name`() {
        val missingName = DisplayTemplate.parse("x%amount%")
        val tooLong = DisplayTemplate.parse("%item_display_name%" + "x".repeat(257))

        assertEquals(
            listOf("template must contain %item_display_name%"),
            assertInstanceOf(DisplayTemplateParseResult.Invalid::class.java, missingName).errors,
        )
        assertEquals(
            listOf("template must not exceed 256 characters"),
            assertInstanceOf(DisplayTemplateParseResult.Invalid::class.java, tooLong).errors,
        )
    }
}
