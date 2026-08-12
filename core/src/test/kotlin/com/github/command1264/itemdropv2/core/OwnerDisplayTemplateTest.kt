package com.github.command1264.itemdropv2.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class OwnerDisplayTemplateTest {
    @Test
    fun `renders player name and rejects templates without the required placeholder`() {
        val valid =
            assertInstanceOf(
                OwnerDisplayTemplateParseResult.Valid::class.java,
                OwnerDisplayTemplate.parse("%vault_prefix%&7[&a%player_name%&7]&r "),
            )
        val invalid =
            assertInstanceOf(
                OwnerDisplayTemplateParseResult.Invalid::class.java,
                OwnerDisplayTemplate.parse("owner"),
            )

        assertEquals("%vault_prefix%&7[&aSteve&7]&r ", valid.template.render("Steve"))
        assertEquals(listOf("template must contain %player_name%"), invalid.errors)
    }

    @Test
    fun `renders protection remaining in migrated owner templates`() {
        val valid =
            assertInstanceOf(
                OwnerDisplayTemplateParseResult.Valid::class.java,
                OwnerDisplayTemplate.parse("&7[%player_name%] %protection_remaining%s "),
            )

        assertEquals("&7[Steve] 1:05s ", valid.template.render("Steve", protectionSecondsRemaining = 65))
    }
}
