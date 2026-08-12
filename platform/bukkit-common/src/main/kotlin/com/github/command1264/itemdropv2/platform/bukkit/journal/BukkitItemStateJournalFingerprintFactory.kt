package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemStateJournalFingerprint
import org.bukkit.NamespacedKey
import org.bukkit.configuration.serialization.ConfigurationSerializable
import org.bukkit.entity.Item
import org.bukkit.inventory.ItemStack
import java.security.MessageDigest

public class BukkitItemStateJournalFingerprintFactory internal constructor(
    private val stackSerializer: (ItemStack) -> Map<String, Any> = ItemStack::serialize,
    private val materialKey: (org.bukkit.Material) -> String = { material -> material.key.toString() },
    private val ownedPdcRemover: (ItemStack) -> Unit = ::removeItemDropPdc,
) {
    public fun create(item: Item): BukkitItemStateJournalFingerprintResult = create(item.itemStack)

    @Suppress("TooGenericExceptionCaught")
    public fun create(stack: ItemStack): BukkitItemStateJournalFingerprintResult =
        try {
            val canonical = stack.clone().apply { amount = 1 }
            ownedPdcRemover(canonical)
            val serialized = stackSerializer(canonical).toMutableMap().apply { remove("amount") }
            val encoded = StringBuilder().also { output -> appendCanonical(output, serialized) }.toString()
            val digest = MessageDigest.getInstance(SHA_256).digest(encoded.toByteArray(Charsets.UTF_8))
            BukkitItemStateJournalFingerprintResult.Created(
                ItemStateJournalFingerprint(materialKey(canonical.type), digest.toLowerHex()),
            )
        } catch (error: RuntimeException) {
            BukkitItemStateJournalFingerprintResult.Failed(error.javaClass.simpleName)
        }

    private fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

    @Suppress("CyclomaticComplexMethod")
    private fun appendCanonical(
        output: StringBuilder,
        value: Any?,
    ) {
        when (value) {
            null -> output.append("null;")
            is String -> output.appendLengthPrefixed("string", value)
            is Number -> output.appendLengthPrefixed(value.javaClass.name, value.toString())
            is Boolean -> output.append(if (value) "boolean:1;" else "boolean:0;")
            is Char -> output.appendLengthPrefixed("char", value.toString())
            is Enum<*> -> output.appendLengthPrefixed("enum:${value.javaClass.name}", value.name)
            is NamespacedKey -> output.appendLengthPrefixed("key", value.toString())
            is ByteArray -> output.appendLengthPrefixed("bytes", value.toLowerHex())
            is IntArray -> appendCanonical(output, value.toList())
            is LongArray -> appendCanonical(output, value.toList())
            is Map<*, *> -> {
                output.append("map{")
                value.entries
                    .map { entry ->
                        val key = entry.key as? String ?: error("journal fingerprint map key must be a string")
                        key to entry.value
                    }.sortedBy { it.first }
                    .forEach { (key, nested) ->
                        output.appendLengthPrefixed("key", key)
                        appendCanonical(output, nested)
                    }
                output.append("};")
            }
            is Iterable<*> -> {
                output.append("list[")
                value.forEach { nested -> appendCanonical(output, nested) }
                output.append("];")
            }
            is ConfigurationSerializable -> appendCanonical(output, value.serialize())
            else -> error("unsupported journal fingerprint value: ${value.javaClass.name}")
        }
    }

    private fun StringBuilder.appendLengthPrefixed(
        type: String,
        value: String,
    ) {
        append(type)
            .append(':')
            .append(value.length)
            .append(':')
            .append(value)
            .append(';')
    }

    private companion object {
        private const val SHA_256 = "SHA-256"

        @Suppress("DEPRECATION")
        private val ITEMDROP_OWNED_KEYS =
            listOf(
                "state-schema-version",
                "state-revision",
                "state-owner-uuid",
                "state-eligible-owner-uuids",
                "state-protection-until-epoch-millis",
                "state-protection-seconds-remaining",
                "state-age-ticks",
                "state-lifetime-ticks",
                "state-age-seconds",
                "state-lifetime-seconds",
                "state-remaining-lifetime-seconds",
                "state-original-lifetime-seconds",
                "state-elapsed-lifetime-seconds",
                "state-virtual-amount",
                "age",
                "amount",
                "owner",
                "ownertime",
                "managed-display-name",
                "paper-managed-component",
                "managed-original-name",
                "paper-original-component",
                "managed-original-name-present",
                "managed-original-name-visible",
            ).map { value -> NamespacedKey("itemdropv2", value) }

        private fun removeItemDropPdc(stack: ItemStack) {
            if (!stack.hasItemMeta()) return
            val meta = stack.itemMeta ?: return
            ITEMDROP_OWNED_KEYS.forEach(meta.persistentDataContainer::remove)
            stack.itemMeta = meta
        }
    }
}

public sealed interface BukkitItemStateJournalFingerprintResult {
    public data class Created(
        public val fingerprint: ItemStateJournalFingerprint,
    ) : BukkitItemStateJournalFingerprintResult

    public data class Failed(
        public val errorType: String,
    ) : BukkitItemStateJournalFingerprintResult
}
