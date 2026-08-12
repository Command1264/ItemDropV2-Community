package com.github.command1264.itemdropv2.platform.bukkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class RuntimeClassCapabilityCacheTest {
    @Test
    fun `resolves a capability once for each runtime class`() {
        var resolutions = 0
        val cache =
            RuntimeClassCapabilityCache<Any> { type ->
                resolutions++
                type.name
            }

        val first = cache.get(String::class.java)
        val repeated = cache.get(String::class.java)
        val secondClass = cache.get(Int::class.javaObjectType)

        assertSame(first, repeated)
        assertEquals(Int::class.javaObjectType.name, secondClass)
        assertEquals(2, resolutions)
    }
}
