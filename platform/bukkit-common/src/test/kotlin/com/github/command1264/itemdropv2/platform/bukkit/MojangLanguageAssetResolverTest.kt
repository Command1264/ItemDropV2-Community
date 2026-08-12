package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.MinecraftLanguageCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

class MojangLanguageAssetResolverTest {
    @Test
    fun `uses piston metadata and verifies content addressed language object`() {
        val languageBytes = """{"block.minecraft.stone":"石頭"}""".toByteArray(Charsets.UTF_8)
        val hash = languageBytes.sha1()
        val responses =
            mapOf(
                MojangLanguageAssetResolver.VERSION_MANIFEST_URI to
                    """{"versions":[{"id":"26.2","url":"https://piston-meta.mojang.com/v1/packages/version/26.2.json"}]}"""
                        .toByteArray(),
                URI("https://piston-meta.mojang.com/v1/packages/version/26.2.json") to
                    """{"assetIndex":{"url":"https://piston-meta.mojang.com/v1/packages/assets/32.json"}}"""
                        .toByteArray(),
                URI("https://piston-meta.mojang.com/v1/packages/assets/32.json") to
                    """{"objects":{"minecraft/lang/zh_tw.json":{"hash":"$hash","size":${languageBytes.size}}}}"""
                        .toByteArray(),
                URI("https://resources.download.minecraft.net/${hash.take(2)}/$hash") to languageBytes,
            )
        val requested = mutableListOf<URI>()
        val client =
            BoundedHttpResourceClient { uri, _ ->
                requested += uri
                requireNotNull(responses[uri])
            }

        val result =
            MojangLanguageAssetResolver(client).resolve(
                "26.2",
                MinecraftLanguageCode.parse("zh_tw").getOrThrow(),
            )
        val resolved = assertInstanceOf(MinecraftLanguageAssetResult.Resolved::class.java, result)

        assertEquals(hash, resolved.asset.sha1)
        assertTrue(requested.last().host == "resources.download.minecraft.net")
    }

    @Test
    fun `rejects metadata redirects to untrusted hosts`() {
        val client =
            BoundedHttpResourceClient { _, _ ->
                """{"versions":[{"id":"26.2","url":"https://example.com/steal.json"}]}""".toByteArray()
            }

        assertInstanceOf(
            MinecraftLanguageAssetResult.Failed::class.java,
            MojangLanguageAssetResolver(client).resolve(
                "26.2",
                MinecraftLanguageCode.parse("zh_tw").getOrThrow(),
            ),
        )
    }

    @Test
    fun `returns bounded failure when network IO fails`() {
        val client = BoundedHttpResourceClient { _, _ -> throw IOException("connection reset") }

        val result =
            MojangLanguageAssetResolver(client).resolve(
                "26.2",
                MinecraftLanguageCode.parse("zh_tw").getOrThrow(),
            )

        val failed = assertInstanceOf(MinecraftLanguageAssetResult.Failed::class.java, result)
        assertEquals("connection reset", failed.diagnostic)
    }

    private fun ByteArray.sha1(): String = MessageDigest.getInstance("SHA-1").digest(this).joinToString("") { "%02x".format(it) }
}
