package com.github.command1264.itemdropv2.platform.bukkit.journal

import com.github.command1264.itemdropv2.core.ItemOwnership
import com.github.command1264.itemdropv2.core.ItemState
import com.github.command1264.itemdropv2.core.ItemStateJournalChunk
import com.github.command1264.itemdropv2.core.ItemStateJournalFingerprint
import com.github.command1264.itemdropv2.core.ItemStateJournalIdentity
import com.github.command1264.itemdropv2.core.ItemStateJournalPresentation
import com.github.command1264.itemdropv2.core.ItemStateJournalRecord
import com.github.command1264.itemdropv2.core.ItemStateJournalRecordType
import com.github.command1264.itemdropv2.core.VirtualItemAmount
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.zip.CRC32

public class ItemStateJournalCodec {
    private val payloadCodec = ItemStateJournalPayloadCodec()

    public fun write(
        output: OutputStream,
        records: Iterable<ItemStateJournalRecord>,
    ) {
        val data = DataOutputStream(output)
        records.forEach { record ->
            val payload = payloadCodec.encode(record)
            require(payload.size <= MAX_PAYLOAD_BYTES) { "journal payload exceeds supported size" }
            data.writeInt(MAGIC)
            data.writeShort(FORMAT_VERSION)
            data.writeByte(record.type.ordinal)
            data.writeInt(payload.size)
            data.write(payload)
            data.writeInt(crc32(payload))
        }
        data.flush()
    }

    public fun read(
        input: InputStream,
        maximumRecords: Int = DEFAULT_MAXIMUM_RECORDS,
    ): JournalDecodeResult {
        require(maximumRecords > 0) { "maximum journal records must be positive" }
        val data = DataInputStream(input)
        val records = mutableListOf<ItemStateJournalRecord>()
        var validBytes = 0L
        var result: JournalDecodeResult? = null
        while (result == null) {
            when (val frame = readFrame(data)) {
                FrameRead.End -> result = JournalDecodeResult.Loaded(records, validBytes, truncatedTail = false)
                FrameRead.Torn -> result = JournalDecodeResult.Loaded(records, validBytes, truncatedTail = true)
                is FrameRead.Invalid -> result = JournalDecodeResult.Invalid(frame.reason, validBytes)
                is FrameRead.Record -> {
                    if (records.size >= maximumRecords) {
                        result = JournalDecodeResult.Invalid("journal record count exceeds supported limit", validBytes)
                    } else {
                        records += frame.record
                        validBytes += frame.bytes
                    }
                }
            }
        }
        return requireNotNull(result)
    }

    private fun readFrame(input: DataInputStream): FrameRead {
        val header = ByteArray(HEADER_BYTES)
        val headerRead = input.readUpTo(header)
        return when {
            headerRead == 0 -> FrameRead.End
            headerRead < HEADER_BYTES -> FrameRead.Torn
            else -> readPayload(input, decodeHeader(header))
        }
    }

    private fun decodeHeader(bytes: ByteArray): FrameHeader {
        val data = DataInputStream(ByteArrayInputStream(bytes))
        return FrameHeader(
            magic = data.readInt(),
            version = data.readUnsignedShort(),
            typeIndex = data.readUnsignedByte(),
            payloadLength = data.readInt(),
        )
    }

    private fun readPayload(
        input: DataInputStream,
        header: FrameHeader,
    ): FrameRead {
        val headerError = header.validationError()
        if (headerError != null) return FrameRead.Invalid(headerError)
        val payload = ByteArray(header.payloadLength)
        val payloadRead = input.readUpTo(payload)
        val checksumBytes = ByteArray(Int.SIZE_BYTES)
        val checksumRead = if (payloadRead == payload.size) input.readUpTo(checksumBytes) else 0
        return when {
            payloadRead < payload.size || checksumRead < checksumBytes.size -> FrameRead.Torn
            checksum(checksumBytes) != crc32(payload) -> FrameRead.Invalid("journal checksum mismatch")
            else -> decodeRecord(header, payload)
        }
    }

    private fun decodeRecord(
        header: FrameHeader,
        payload: ByteArray,
    ): FrameRead =
        try {
            val type = ItemStateJournalRecordType.entries[header.typeIndex]
            FrameRead.Record(payloadCodec.decode(type, payload), HEADER_BYTES + payload.size + Int.SIZE_BYTES)
        } catch (error: IllegalArgumentException) {
            FrameRead.Invalid("invalid journal payload (${error.message ?: "validation"})")
        } catch (_: EOFException) {
            FrameRead.Invalid("truncated journal payload")
        } catch (_: IOException) {
            FrameRead.Invalid("unreadable journal payload")
        }

    private fun FrameHeader.validationError(): String? =
        when {
            magic != MAGIC -> "invalid journal magic"
            version != FORMAT_VERSION -> "unsupported journal format $version"
            typeIndex !in ItemStateJournalRecordType.entries.indices -> "invalid journal record type"
            payloadLength !in 1..MAX_PAYLOAD_BYTES -> "invalid journal payload length"
            else -> null
        }

    private fun checksum(bytes: ByteArray): Int = DataInputStream(ByteArrayInputStream(bytes)).readInt()

    private data class FrameHeader(
        val magic: Int,
        val version: Int,
        val typeIndex: Int,
        val payloadLength: Int,
    )

    private sealed interface FrameRead {
        data object End : FrameRead

        data object Torn : FrameRead

        data class Invalid(
            val reason: String,
        ) : FrameRead

        data class Record(
            val record: ItemStateJournalRecord,
            val bytes: Int,
        ) : FrameRead
    }

    public companion object {
        public const val MAX_PAYLOAD_BYTES: Int = 64 * 1024
        private const val MAGIC: Int = 0x49444A52
        private const val FORMAT_VERSION: Int = 1
        private const val HEADER_BYTES: Int = Int.SIZE_BYTES + Short.SIZE_BYTES + Byte.SIZE_BYTES + Int.SIZE_BYTES
        private const val DEFAULT_MAXIMUM_RECORDS: Int = 1_000_000
    }
}

private class ItemStateJournalPayloadCodec {
    fun encode(record: ItemStateJournalRecord): ByteArray =
        ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use { data ->
                data.writeUuid(record.identity.worldUuid)
                data.writeUuid(record.identity.entityUuid)
                data.writeLong(record.revision)
                data.writeInt(record.chunk.x)
                data.writeInt(record.chunk.z)
                data.writeUuid(record.sessionId)
                data.writeBoundedString(record.fingerprint.materialKey)
                data.writeBoundedString(record.fingerprint.metadataSha256)
                if (record.type == ItemStateJournalRecordType.UPSERT) {
                    writeState(data, requireNotNull(record.state))
                    writePresentation(data, requireNotNull(record.presentation))
                }
            }
            buffer.toByteArray()
        }

    fun decode(
        type: ItemStateJournalRecordType,
        payload: ByteArray,
    ): ItemStateJournalRecord {
        val data = DataInputStream(ByteArrayInputStream(payload))
        val identity = ItemStateJournalIdentity(data.readUuid(), data.readUuid())
        val revision = data.readLong()
        val chunk = ItemStateJournalChunk(data.readInt(), data.readInt())
        val sessionId = data.readUuid()
        val fingerprint = ItemStateJournalFingerprint(data.readBoundedString(), data.readBoundedString())
        val record =
            when (type) {
                ItemStateJournalRecordType.UPSERT ->
                    ItemStateJournalRecord.upsert(
                        identity,
                        revision,
                        chunk,
                        sessionId,
                        fingerprint,
                        readState(data),
                        readPresentation(data),
                    )
                ItemStateJournalRecordType.TOMBSTONE ->
                    ItemStateJournalRecord.tombstone(identity, revision, chunk, sessionId, fingerprint)
            }
        require(data.available() == 0) { "journal payload contains trailing bytes" }
        return record
    }

    private fun writeState(
        data: DataOutputStream,
        state: ItemState,
    ) {
        data.writeBoolean(state.ownership != null)
        state.ownership?.let { ownership ->
            data.writeUuid(ownership.ownerUuid)
            data.writeLong(ownership.protectionSecondsRemaining)
            data.writeInt(ownership.eligibleOwnerUuids.size)
            ownership.eligibleOwnerUuids.forEach { data.writeUuid(it) }
        }
        data.writeLong(state.elapsedLifetimeSeconds)
        data.writeBoolean(state.originalLifetimeSeconds != null)
        state.originalLifetimeSeconds?.let { data.writeLong(it) }
        data.writeBoolean(state.virtualAmount != null)
        state.virtualAmount?.let { data.writeLong(it.value) }
    }

    private fun readState(data: DataInputStream): ItemState {
        val ownership =
            if (data.readStrictBoolean()) {
                val owner = data.readUuid()
                val protection = data.readLong()
                val count = data.readInt()
                require(count in 1..ItemOwnership.MAX_ELIGIBLE_OWNERS) { "eligible owner count is invalid" }
                ItemOwnership(owner, protection, List(count) { data.readUuid() })
            } else {
                null
            }
        val elapsed = data.readLong()
        val original = if (data.readStrictBoolean()) data.readLong() else null
        val amount = if (data.readStrictBoolean()) VirtualItemAmount.of(data.readLong()) else null
        return ItemState(ownership, elapsed, original, amount)
    }

    private fun writePresentation(
        data: DataOutputStream,
        presentation: ItemStateJournalPresentation,
    ) {
        data.writeNullableString(presentation.managedName)
        data.writeBoolean(presentation.originalNamePresent)
        data.writeNullableString(presentation.originalName)
        data.writeBoolean(presentation.customNameVisible)
    }

    private fun readPresentation(data: DataInputStream): ItemStateJournalPresentation =
        ItemStateJournalPresentation(
            managedName = data.readNullableString(),
            originalNamePresent = data.readStrictBoolean(),
            originalName = data.readNullableString(),
            customNameVisible = data.readStrictBoolean(),
        )
}

private fun DataOutputStream.writeNullableString(value: String?) {
    writeBoolean(value != null)
    value?.let { writeBoundedString(it) }
}

private fun DataInputStream.readNullableString(): String? = if (readStrictBoolean()) readBoundedString() else null

private fun DataInputStream.readStrictBoolean(): Boolean =
    when (val value = readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("journal boolean value $value is invalid")
    }

private fun DataOutputStream.writeBoundedString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= MAX_STRING_BYTES) { "journal string exceeds supported size" }
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.readBoundedString(): String {
    val length = readInt()
    require(length in 0..MAX_STRING_BYTES) { "journal string length is invalid" }
    val bytes = ByteArray(length).also { readFully(it) }
    return Charsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
}

private fun DataOutputStream.writeUuid(value: UUID) {
    writeLong(value.mostSignificantBits)
    writeLong(value.leastSignificantBits)
}

private fun DataInputStream.readUuid(): UUID = UUID(readLong(), readLong())

private fun InputStream.readUpTo(bytes: ByteArray): Int {
    var offset = 0
    var read = read(bytes, offset, bytes.size - offset)
    while (read > 0) {
        offset += read
        read = if (offset < bytes.size) read(bytes, offset, bytes.size - offset) else -1
    }
    return offset
}

private fun crc32(payload: ByteArray): Int = CRC32().apply { update(payload) }.value.toInt()

private const val MAX_STRING_BYTES: Int = 16 * 1024

public sealed interface JournalDecodeResult {
    public data class Loaded(
        public val records: List<ItemStateJournalRecord>,
        public val validBytes: Long,
        public val truncatedTail: Boolean,
    ) : JournalDecodeResult

    public data class Invalid(
        public val reason: String,
        public val validBytes: Long,
    ) : JournalDecodeResult
}
