package com.github.command1264.itemdropv2.platform.bukkit

import com.github.command1264.itemdropv2.core.MinecraftLanguageCode
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

public fun interface BoundedHttpResourceClient : AutoCloseable {
    public fun get(
        uri: URI,
        maximumBytes: Int,
    ): ByteArray

    override fun close() = Unit
}

public class HttpUrlConnectionResourceClient(
    private val connectTimeoutMillis: Int = 5_000,
    private val readTimeoutMillis: Int = 10_000,
) : BoundedHttpResourceClient {
    private val closed = AtomicBoolean(false)
    private val activeConnection = AtomicReference<HttpURLConnection?>()

    override fun get(
        uri: URI,
        maximumBytes: Int,
    ): ByteArray {
        check(!closed.get()) { "HTTP resource client is closed" }
        require(uri.scheme == "https") { "only HTTPS resources are allowed" }
        require(maximumBytes > 0) { "maximumBytes must be positive" }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        if (!activeConnection.compareAndSet(null, connection)) {
            connection.disconnect()
            error("concurrent HTTP requests are not supported")
        }
        try {
            check(!closed.get()) { "HTTP resource client is closed" }
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = readTimeoutMillis
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "ItemDropV2/0.1")
            val status = connection.responseCode
            require(status == HttpURLConnection.HTTP_OK) { "HTTP request failed with status $status" }
            val declaredLength = connection.contentLengthLong
            require(declaredLength < 0 || declaredLength <= maximumBytes) { "HTTP response is too large" }
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= maximumBytes) { "HTTP response is too large" }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        } finally {
            activeConnection.compareAndSet(connection, null)
            connection.disconnect()
        }
    }

    override fun close() {
        closed.set(true)
        activeConnection.getAndSet(null)?.disconnect()
    }
}

public data class MinecraftLanguageAsset(
    public val language: MinecraftLanguageCode,
    public val sha1: String,
    public val bytes: ByteArray,
)

public sealed interface MinecraftLanguageAssetResult {
    public data class Resolved(
        public val asset: MinecraftLanguageAsset,
    ) : MinecraftLanguageAssetResult

    public data class Failed(
        public val diagnostic: String,
    ) : MinecraftLanguageAssetResult
}

public class MojangLanguageAssetResolver(
    private val client: BoundedHttpResourceClient,
) : AutoCloseable {
    @Suppress("TooGenericExceptionCaught")
    public fun resolve(
        minecraftVersion: String,
        language: MinecraftLanguageCode,
    ): MinecraftLanguageAssetResult =
        try {
            require(VERSION_ID.matches(minecraftVersion)) { "invalid Minecraft version" }
            require(language != MinecraftLanguageCode.EN_US) { "en_us is bundled and is not in the asset index" }
            val manifest = getJsonObject(VERSION_MANIFEST_URI, MAX_METADATA_BYTES)
            val versionUri = findVersionUri(manifest, minecraftVersion)
            val version = getJsonObject(versionUri, MAX_METADATA_BYTES)
            val assetIndexUri = trustedMetadataUri(version.requiredObject("assetIndex").requiredString("url"))
            val assetIndex = getJsonObject(assetIndexUri, MAX_ASSET_INDEX_BYTES)
            val languagePath = "minecraft/lang/${language.value}.json"
            val languageObject = assetIndex.requiredObject("objects").requiredObject(languagePath)
            val hash = languageObject.requiredString("hash")
            require(SHA1.matches(hash)) { "language asset hash is invalid" }
            val expectedSize = languageObject.requiredPositiveInt("size")
            require(expectedSize <= MinecraftLanguageCatalogParser.MAX_LANGUAGE_BYTES) {
                "language asset is too large"
            }
            val objectUri = URI("https://resources.download.minecraft.net/${hash.take(2)}/$hash")
            val bytes = client.get(objectUri, MinecraftLanguageCatalogParser.MAX_LANGUAGE_BYTES)
            require(bytes.size == expectedSize) { "language asset size does not match metadata" }
            require(bytes.sha1() == hash) { "language asset SHA-1 does not match metadata" }
            MinecraftLanguageAssetResult.Resolved(MinecraftLanguageAsset(language, hash, bytes.copyOf()))
        } catch (error: Exception) {
            MinecraftLanguageAssetResult.Failed(error.message ?: error.javaClass.simpleName)
        }

    override fun close() {
        client.close()
    }

    private fun findVersionUri(
        manifest: JsonObject,
        minecraftVersion: String,
    ): URI {
        val versions = manifest.getAsJsonArray("versions") ?: error("version manifest has no versions")
        for (element in versions) {
            if (!element.isJsonObject) continue
            val value = element.asJsonObject
            if (value.get("id")?.asString == minecraftVersion) {
                return trustedMetadataUri(value.requiredString("url"))
            }
        }
        error("Minecraft version '$minecraftVersion' was not found in the official manifest")
    }

    private fun getJsonObject(
        uri: URI,
        maximumBytes: Int,
    ): JsonObject {
        val bytes = client.get(uri, maximumBytes)
        val root =
            try {
                JsonParser.parseString(String(bytes, StandardCharsets.UTF_8))
            } catch (error: JsonParseException) {
                throw IllegalArgumentException("official metadata is not valid JSON", error)
            }
        require(root.isJsonObject) { "official metadata must be a JSON object" }
        return root.asJsonObject
    }

    private fun trustedMetadataUri(raw: String): URI {
        val uri = URI(raw)
        require(uri.scheme == "https" && uri.host in TRUSTED_METADATA_HOSTS) {
            "official metadata pointed to an untrusted host"
        }
        return uri
    }

    public companion object {
        public val VERSION_MANIFEST_URI: URI = URI("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")

        private val TRUSTED_METADATA_HOSTS = setOf("piston-meta.mojang.com", "launchermeta.mojang.com")
        private val VERSION_ID = Regex("[0-9A-Za-z._-]{1,64}")
        private val SHA1 = Regex("[0-9a-f]{40}")
        private const val MAX_METADATA_BYTES = 4 * 1024 * 1024
        private const val MAX_ASSET_INDEX_BYTES = 32 * 1024 * 1024
    }
}

private fun JsonObject.requiredObject(key: String): JsonObject {
    val value = get(key)
    require(value != null && value.isJsonObject) { "official metadata is missing object '$key'" }
    return value.asJsonObject
}

private fun JsonObject.requiredString(key: String): String {
    val value = get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
        "official metadata is missing string '$key'"
    }
    return value.asString
}

private fun JsonObject.requiredPositiveInt(key: String): Int {
    val value = get(key)
    require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber) {
        "official metadata is missing number '$key'"
    }
    val number = value.asInt
    require(number > 0) { "official metadata number '$key' must be positive" }
    return number
}

private fun ByteArray.sha1(): String = MessageDigest.getInstance("SHA-1").digest(this).joinToString("") { byte -> "%02x".format(byte) }
