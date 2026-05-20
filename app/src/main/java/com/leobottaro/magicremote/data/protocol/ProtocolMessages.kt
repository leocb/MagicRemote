package com.leobottaro.magicremote.data.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream

// ── Varint helpers ──

fun writeVarint(value: Int): ByteArray {
    val buf = ByteArrayOutputStream()
    var v = value
    while (v >= 0x80) {
        buf.write((v and 0x7F) or 0x80)
        v = v ushr 7
    }
    buf.write(v and 0x7F)
    return buf.toByteArray()
}

fun readVarint(stream: DataInputStream): Int {
    var result = 0
    var shift = 0
    while (true) {
        val byte = stream.readByte().toInt() and 0xFF
        result = result or ((byte and 0x7F) shl shift)
        if (byte and 0x80 == 0) return result
        shift += 7
        if (shift >= 32) throw IllegalArgumentException("Varint too long")
    }
}

// ── Wire format helpers ──

fun varintField(fieldNumber: Int, value: Int): ByteArray {
    val tag = (fieldNumber shl 3) or 0 // wire type 0 = varint
    return writeVarint(tag) + writeVarint(value)
}

fun lengthDelimitedField(fieldNumber: Int, value: ByteArray): ByteArray {
    val tag = (fieldNumber shl 3) or 2 // wire type 2 = length-delimited
    return writeVarint(tag) + writeVarint(value.size) + value
}

// ── Message framing (varint length prefix) ──

fun frameMessage(payload: ByteArray): ByteArray {
    return writeVarint(payload.size) + payload
}

/** Read a varint-length-prefixed message from an InputStream. Returns null if not enough data. */
fun readFrame(inputStream: InputStream): ByteArray? {
    try {
        val dataStream = if (inputStream is DataInputStream) inputStream else DataInputStream(inputStream)
        // Read varint length byte-by-byte to avoid blocking on partial data
        var len = 0
        var shift = 0
        while (true) {
            val byte = dataStream.readByte().toInt() and 0xFF
            len = len or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
            if (shift >= 32) return null
        }
        if (len <= 0 || len > 1024 * 1024) return null
        val data = ByteArray(len)
        dataStream.readFully(data)
        return data
    } catch (_: Exception) {
        return null
    }
}

// ── Pairing protocol messages (port 6467) ──
//
// Field layout from wiki:
//   1: protocol_version (varint, 2)
//   2: status (varint, 200=OK, 400=ERROR)
//  10: payload (bytes) — nested { 1: service_name, 2: client_name }
//  11: acknowledgment (bytes, empty)
//  20: options (bytes) — nested { 1: { 1: type, 2: symbol_len }, 3: role }
//  30: configuration (bytes) — nested { 1: { 1: type, 2: symbol_len }, 2: role }
//  40: secret (bytes) — nested { 1: sha256_hash }

object PairingEncoder {

    /** Message 1: client config with service_name and client_name. */
    fun encodeClientConfig(serviceName: String, clientName: String): ByteArray {
        val payload = lengthDelimitedField(1, serviceName.toByteArray()) +
                lengthDelimitedField(2, clientName.toByteArray())
        val msg = varintField(1, 2) +           // protocol_version = 2
                varintField(2, 200) +            // status = OK
                lengthDelimitedField(10, payload)
        return frameMessage(msg)
    }

    /** Message 2: options — encoding type, symbol length, role. */
    fun encodeOptions(): ByteArray {
        // Sub-message for encoding: { type=3 (HEX), symbol_length=6 }
        val encodingSub = varintField(1, 3) + varintField(2, 6)
        val optionsContent = lengthDelimitedField(1, encodingSub) +
                varintField(3, 1)   // preferred_role = ROLE_TYPE_INPUT
        val msg = varintField(1, 2) +
                varintField(2, 200) +
                lengthDelimitedField(20, optionsContent)
        return frameMessage(msg)
    }

    /** Message 3: configuration — encoding type, symbol length, role. */
    fun encodeConfiguration(): ByteArray {
        // Sub-message for encoding: { type=3 (HEX), symbol_length=6 }
        val encodingSub = varintField(1, 3) + varintField(2, 6)
        val configContent = lengthDelimitedField(1, encodingSub) +
                varintField(2, 1)   // preferred_role = ROLE_TYPE_INPUT (field 2 here, vs field 3 in options)
        val msg = varintField(1, 2) +
                varintField(2, 200) +
                lengthDelimitedField(30, configContent)
        return frameMessage(msg)
    }

    /** Message 4: the computed SHA-256 secret (32 bytes). */
    fun encodeSecret(sha256Hash: ByteArray): ByteArray {
        require(sha256Hash.size == 32) { "SHA-256 hash must be 32 bytes" }
        val secretContent = lengthDelimitedField(1, sha256Hash)
        val msg = varintField(1, 2) +
                varintField(2, 200) +
                lengthDelimitedField(40, secretContent)
        return frameMessage(msg)
    }

    /** Parse a server response and extract the acknowledgment status. */
    data class PairingResponse(
        val status: Int,            // 200 = OK
        val hasAck: Boolean         // true if field 11 (empty ack) is present
    )

    fun parseResponse(data: ByteArray): PairingResponse {
        val stream = DataInputStream(data.inputStream())
        var status = 0
        var hasAck = false
        while (stream.available() > 0) {
            val tag = readVarint(stream)
            val fieldNum = tag ushr 3
            val wireType = tag and 0x07
            when (fieldNum) {
                1 -> readVarint(stream)  // protocol_version
                2 -> status = readVarint(stream)
                11 -> {
                    if (wireType == 2) {
                        val len = readVarint(stream)
                        // Empty ack: len should be 0
                        hasAck = true
                    }
                }
                10, 20, 30, 40 -> {
                    if (wireType == 2) {
                        val len = readVarint(stream)
                        stream.skipBytes(len)
                    }
                }
                else -> skipField(stream, wireType)
            }
        }
        return PairingResponse(status, hasAck)
    }

    /** Check if this response is the "acknowledgment" containing field 11. */
    fun isAck(data: ByteArray): Boolean = parseResponse(data).hasAck
}

// ── Remote control protocol messages (port 6466) ──
//
// Key command: field 10 = { field 1: key_code, field 2: action }
//   action 1 = press/down, 2 = release/up
//
// 1st config: field 1 = nested { 302, "androidtv-remote", "1", "1.0.0" }
// 2nd config: field 3 = nested { 302 }

object RemoteEncoder {

    /** Encode a key press (action=1) or release (action=2). */
    fun encodeKeyEvent(keyCode: Int, action: Int): ByteArray {
        val inner = varintField(1, keyCode) + varintField(2, action)
        val msg = lengthDelimitedField(10, inner)
        return frameMessage(msg)
    }

    fun keyPress(keyCode: Int): ByteArray = encodeKeyEvent(keyCode, 1)
    fun keyRelease(keyCode: Int): ByteArray = encodeKeyEvent(keyCode, 2)

    /** 1st config message sent after connecting to port 6466. */
    fun encodeRemoteConfig(): ByteArray {
        // Nested sub-message: { 3: 1, 4: "1", 5: "androidtv-remote", 6: "1.0.0" }
        val subInner = varintField(3, 1) +
                lengthDelimitedField(4, "1".toByteArray()) +
                lengthDelimitedField(5, "androidtv-remote".toByteArray()) +
                lengthDelimitedField(6, "1.0.0".toByteArray())
        // Inner: { 1: 622, 2: subInner }
        val inner = varintField(1, 622) + lengthDelimitedField(2, subInner)
        // Wrapper field 1
        val msg = lengthDelimitedField(1, inner)
        return frameMessage(msg)
    }

    /** 2nd config ack message. */
    fun encodeRemoteConfigAck(): ByteArray {
        val inner = varintField(1, 622)
        val msg = lengthDelimitedField(3, inner) // field 3
        return frameMessage(msg)
    }

    /** Parse server's initial info message (ignore content). */
    fun isAck(data: ByteArray): Boolean {
        // An ack is a short message with field 3 (tag 18 or 26 depending)
        return data.size < 10
    }
}

// ── Shared helpers ──

private fun skipField(stream: DataInputStream, wireType: Int) {
    when (wireType) {
        0 -> readVarint(stream)
        2 -> {
            val len = readVarint(stream)
            stream.skipBytes(len)
        }
        else -> throw IllegalArgumentException("Unknown wire type: $wireType")
    }
}
