package com.github.command1264.itemdropv2.platform.bukkit.compatibility

import org.bukkit.NamespacedKey
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import java.util.UUID

public sealed interface CompatibilityMarkerReadResult {
    public data object Absent : CompatibilityMarkerReadResult

    public data class Rejected(
        public val reason: String,
    ) : CompatibilityMarkerReadResult
}

public sealed interface CompatibilityMarker : CompatibilityMarkerReadResult {
    public data class Source(
        public val batchId: UUID,
    ) : CompatibilityMarker

    public data class Output(
        public val batchId: UUID,
        public val markerIndex: Int,
    ) : CompatibilityMarker {
        init {
            require(markerIndex >= 0) { "compatibility marker index must not be negative" }
        }
    }
}

/** Reads and writes provisional transaction markers on an exact caller-supplied PDC target. */
public class BukkitCompatibilityBatchMarker {
    public fun writeSource(
        container: PersistentDataContainer,
        batchId: UUID,
    ) {
        requireValidBatchId(batchId)
        container.remove(OUTPUT_INDEX_KEY)
        container.set(BATCH_ID_KEY, PersistentDataType.STRING, batchId.toString())
        container.set(ROLE_KEY, PersistentDataType.INTEGER, SOURCE_ROLE)
    }

    public fun writeOutput(
        container: PersistentDataContainer,
        batchId: UUID,
        markerIndex: Int,
    ) {
        requireValidBatchId(batchId)
        require(markerIndex >= 0) { "compatibility marker index must not be negative" }
        container.set(BATCH_ID_KEY, PersistentDataType.STRING, batchId.toString())
        container.set(OUTPUT_INDEX_KEY, PersistentDataType.INTEGER, markerIndex)
        container.set(ROLE_KEY, PersistentDataType.INTEGER, OUTPUT_ROLE)
    }

    public fun read(container: PersistentDataContainer): CompatibilityMarkerReadResult {
        val presence =
            MarkerPresence(
                batch = container.hasAnyType(BATCH_ID_KEY),
                role = container.hasAnyType(ROLE_KEY),
                index = container.hasAnyType(OUTPUT_INDEX_KEY),
            )
        return when {
            presence.isAbsent -> CompatibilityMarkerReadResult.Absent
            presence.isIncomplete -> CompatibilityMarkerReadResult.Rejected("IncompleteMarker")
            !hasValidTypes(container, presence) -> CompatibilityMarkerReadResult.Rejected("InvalidType")
            else -> readComplete(container, presence.index)
        }
    }

    private fun readComplete(
        container: PersistentDataContainer,
        indexPresent: Boolean,
    ): CompatibilityMarkerReadResult {
        val rawBatchId = container.get(BATCH_ID_KEY, PersistentDataType.STRING).orEmpty()
        val batchId = parseCanonicalBatchId(rawBatchId) ?: return CompatibilityMarkerReadResult.Rejected("InvalidBatchId")
        return when (container.get(ROLE_KEY, PersistentDataType.INTEGER)) {
            SOURCE_ROLE ->
                if (indexPresent) {
                    CompatibilityMarkerReadResult.Rejected("UnexpectedOutputIndex")
                } else {
                    CompatibilityMarker.Source(batchId)
                }
            OUTPUT_ROLE -> readOutput(container, batchId, indexPresent)
            else -> CompatibilityMarkerReadResult.Rejected("InvalidRole")
        }
    }

    private fun readOutput(
        container: PersistentDataContainer,
        batchId: UUID,
        indexPresent: Boolean,
    ): CompatibilityMarkerReadResult {
        if (!indexPresent) return CompatibilityMarkerReadResult.Rejected("MissingOutputIndex")
        val markerIndex = container.get(OUTPUT_INDEX_KEY, PersistentDataType.INTEGER)
        return if (markerIndex == null || markerIndex < 0) {
            CompatibilityMarkerReadResult.Rejected("InvalidOutputIndex")
        } else {
            CompatibilityMarker.Output(batchId, markerIndex)
        }
    }

    private fun hasValidTypes(
        container: PersistentDataContainer,
        presence: MarkerPresence,
    ): Boolean =
        container.has(BATCH_ID_KEY, PersistentDataType.STRING) &&
            container.has(ROLE_KEY, PersistentDataType.INTEGER) &&
            (!presence.index || container.has(OUTPUT_INDEX_KEY, PersistentDataType.INTEGER))

    public fun clear(
        container: PersistentDataContainer,
        expected: CompatibilityMarker,
    ): Boolean {
        if (read(container) != expected) return false
        container.remove(BATCH_ID_KEY)
        container.remove(ROLE_KEY)
        container.remove(OUTPUT_INDEX_KEY)
        return true
    }

    private fun requireValidBatchId(batchId: UUID) {
        require(batchId != ZERO_UUID) { "compatibility batch UUID must not be nil" }
    }

    private fun parseCanonicalBatchId(raw: String): UUID? =
        try {
            UUID.fromString(raw).takeIf { it != ZERO_UUID && it.toString() == raw.lowercase() }
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun PersistentDataContainer.hasAnyType(key: NamespacedKey): Boolean =
        has(key, PersistentDataType.BYTE) ||
            has(key, PersistentDataType.SHORT) ||
            has(key, PersistentDataType.INTEGER) ||
            has(key, PersistentDataType.LONG) ||
            has(key, PersistentDataType.FLOAT) ||
            has(key, PersistentDataType.DOUBLE) ||
            has(key, PersistentDataType.STRING) ||
            has(key, PersistentDataType.BYTE_ARRAY) ||
            has(key, PersistentDataType.INTEGER_ARRAY) ||
            has(key, PersistentDataType.LONG_ARRAY) ||
            has(key, PersistentDataType.TAG_CONTAINER)

    private companion object {
        const val SOURCE_ROLE = 1
        const val OUTPUT_ROLE = 2
        val ZERO_UUID: UUID = UUID(0, 0)

        @Suppress("DEPRECATION")
        val BATCH_ID_KEY: NamespacedKey = NamespacedKey("itemdropv2", "compatibility-batch-id")

        @Suppress("DEPRECATION")
        val ROLE_KEY: NamespacedKey = NamespacedKey("itemdropv2", "compatibility-batch-role")

        @Suppress("DEPRECATION")
        val OUTPUT_INDEX_KEY: NamespacedKey = NamespacedKey("itemdropv2", "compatibility-output-index")
    }
}

private data class MarkerPresence(
    val batch: Boolean,
    val role: Boolean,
    val index: Boolean,
) {
    val isAbsent: Boolean = !batch && !role && !index
    val isIncomplete: Boolean = !batch || !role
}
