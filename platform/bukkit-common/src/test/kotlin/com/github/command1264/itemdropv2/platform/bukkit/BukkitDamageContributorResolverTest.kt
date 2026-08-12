package com.github.command1264.itemdropv2.platform.bukkit

import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Tameable
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID

class BukkitDamageContributorResolverTest {
    @Test
    fun `resolves direct player projectile shooter and tameable owner`() {
        val player = player()
        val projectile = proxy<Projectile> { method, _ -> if (method.name == "getShooter") player else defaultValue(method.returnType) }
        val owner = proxy<OfflinePlayer> { method, _ -> if (method.name == "getUniqueId") OWNER else defaultValue(method.returnType) }
        val tameable = proxy<Tameable> { method, _ -> if (method.name == "getOwner") owner else defaultValue(method.returnType) }

        assertEquals(OWNER, resolvePlayerContributor(player))
        assertEquals(OWNER, resolvePlayerContributor(projectile))
        assertEquals(OWNER, resolvePlayerContributor(tameable))
    }

    private fun player(): Player = proxy { method, _ -> if (method.name == "getUniqueId") OWNER else defaultValue(method.returnType) }

    private inline fun <reified T> proxy(crossinline answer: (Method, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> answer(method, args) } as T

    private companion object {
        private val OWNER = UUID.fromString("00000000-0000-0000-0000-000000000020")

        private fun defaultValue(type: Class<*>): Any? =
            when (type) {
                java.lang.Boolean.TYPE -> false
                java.lang.Integer.TYPE -> 0
                java.lang.Long.TYPE -> 0L
                java.lang.Double.TYPE -> 0.0
                else -> null
            }
    }
}
