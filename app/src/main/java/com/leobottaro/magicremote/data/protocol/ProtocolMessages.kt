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

// ── Wire helpers ──

fun varintField(fieldNumber: Int, value: Int): ByteArray {
    return writeVarint((fieldNumber shl 3) or 0) + writeVarint(value)
}

fun lengthDelimitedField(fieldNumber: Int, value: ByteArray): ByteArray {
    return writeVarint((fieldNumber shl 3) or 2) + writeVarint(value.size) + value
}

// ── Frame helper (varint length prefix) ──

fun frameMessage(payload: ByteArray): ByteArray {
    return writeVarint(payload.size) + payload
}

/** Read a varint-length-prefixed message from InputStream. */
fun readFrame(inputStream: InputStream): ByteArray? {
    return try {
        val ds = if (inputStream is DataInputStream) inputStream else DataInputStream(inputStream)
        var len = 0
        var shift = 0
        while (true) {
            val byte = ds.readByte().toInt() and 0xFF
            len = len or ((byte and 0x7F) shl shift)
            if (byte and 0x80 == 0) break
            shift += 7
            if (shift >= 32) return null
        }
        if (len <= 0 || len > 1024 * 1024) return null
        val data = ByteArray(len)
        ds.readFully(data)
        data
    } catch (_: Exception) {
        null
    }
}

// ── Status values (from polo.proto) ──

object StatusCode {
    const val OK = 200
    const val ERROR = 400
    const val BAD_CONFIGURATION = 401
    const val BAD_SECRET = 402
}

// ── Pairing protocol submessages ──

object PairingRequest {
    fun encode(serviceName: String, clientName: String): ByteArray {
        return lengthDelimitedField(1, serviceName.toByteArray()) +
                lengthDelimitedField(2, clientName.toByteArray())
    }
}

object OptionsMsg {
    fun encode(encodingType: Int = 3, symbolLength: Int = 6, role: Int = 1): ByteArray {
        val encoding = varintField(1, encodingType) + varintField(2, symbolLength)
        return lengthDelimitedField(1, encoding) +  // input_encodings
                varintField(3, role)   // preferred_role (field 3 = RoleType)
    }
}

object ConfigurationMsg {
    fun encode(encodingType: Int = 3, symbolLength: Int = 6, clientRole: Int = 1): ByteArray {
        val encoding = varintField(1, encodingType) + varintField(2, symbolLength)
        return lengthDelimitedField(1, encoding) +  // encoding
                varintField(2, clientRole)           // client_role
    }
}

object SecretMsg {
    fun encode(secretBytes: ByteArray): ByteArray {
        require(secretBytes.size == 32) { "SHA-256 hash must be 32 bytes" }
        return lengthDelimitedField(1, secretBytes)  // secret
    }
}

// ── Wiki-style pairing protocol (port 6467) ──

object PoloMessage {
    fun encodeRequest(payload: ByteArray): ByteArray {
        return frameMessage(
            varintField(1, 2) + varintField(2, StatusCode.OK) + lengthDelimitedField(10, payload)
        )
    }

    fun encodeOptions(payload: ByteArray): ByteArray {
        return frameMessage(
            varintField(1, 2) + varintField(2, StatusCode.OK) + lengthDelimitedField(20, payload)
        )
    }

    fun encodeConfiguration(payload: ByteArray): ByteArray {
        return frameMessage(
            varintField(1, 2) + varintField(2, StatusCode.OK) + lengthDelimitedField(30, payload)
        )
    }

    fun encodeSecret(payload: ByteArray): ByteArray {
        return frameMessage(
            varintField(1, 2) + varintField(2, StatusCode.OK) + lengthDelimitedField(40, payload)
        )
    }

    data class Reply(val status: Int, val fieldNum: Int, val payload: ByteArray?)

    fun decode(data: ByteArray): Reply {
        val stream = DataInputStream(data.inputStream())
        var status = 0
        var fieldNum = 0
        var payload: ByteArray? = null
        while (stream.available() > 0) {
            val tag = readVarint(stream)
            val fn = tag ushr 3
            val wt = tag and 0x07
            when (fn) {
                1 -> readVarint(stream)
                2 -> status = readVarint(stream)
                10, 11, 20, 30, 31, 40, 41 -> {
                    fieldNum = fn
                    if (wt == 2) {
                        val len = readVarint(stream)
                        payload = if (len > 0) { val d = ByteArray(len); stream.readFully(d); d } else ByteArray(0)
                    } else skipField(stream, wt)
                }
                else -> skipField(stream, wt)
            }
        }
        return Reply(status, fieldNum, payload)
    }
}

// ── Remote control protocol (port 6466) ──
//
// Key command: field 10 = KeyEvent { field 1: key_code, field 2: action }
//   action 1 = press/down, 2 = release/up

object RemoteKeyEvent {
    fun keyPress(keyCode: Int): ByteArray {
        val inner = varintField(1, keyCode) + varintField(2, 1)
        val msg = lengthDelimitedField(10, inner)
        return frameMessage(msg)
    }

    fun keyRelease(keyCode: Int): ByteArray {
        val inner = varintField(1, keyCode) + varintField(2, 2)
        val msg = lengthDelimitedField(10, inner)
        return frameMessage(msg)
    }
}

object RemoteConfig {
    /** 1st config: field 1 = { 622, subInner{3:1, 4:"1", 5:"androidtv-remote", 6:"1.0.0"} } */
    fun encodeConfig(): ByteArray {
        val subInner = varintField(3, 1) +
                lengthDelimitedField(4, "1".toByteArray()) +
                lengthDelimitedField(5, "androidtv-remote".toByteArray()) +
                lengthDelimitedField(6, "1.0.0".toByteArray())
        val inner = varintField(1, 622) + lengthDelimitedField(2, subInner)
        val msg = lengthDelimitedField(1, inner)
        return frameMessage(msg)
    }

    /** 2nd config ACK: field 2 = { 622 } per wiki: [18, 3, 8, 238, 4] */
    fun encodeConfigAck(): ByteArray {
        val inner = varintField(1, 622)
        val msg = lengthDelimitedField(2, inner)
        return frameMessage(msg)
    }
}

private fun skipField(stream: DataInputStream, wireType: Int) {
    when (wireType) {
        0 -> readVarint(stream)
        2 -> { val len = readVarint(stream); stream.skipBytes(len) }
        else -> throw IllegalArgumentException("Unknown wire type: $wireType")
    }
}

// Keep unused PoloMessage definitions for the pairing protocol
fun readLengthDelimited(stream: DataInputStream): ByteArray {
    val len = readVarint(stream)
    val data = ByteArray(len)
    stream.readFully(data)
    return data
}
