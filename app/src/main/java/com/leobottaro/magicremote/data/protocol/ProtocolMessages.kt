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

fun varintField(fieldNumber: Int, value: Int): ByteArray {
    return writeVarint((fieldNumber shl 3) or 0) + writeVarint(value)
}

fun lengthDelimitedField(fieldNumber: Int, value: ByteArray): ByteArray {
    return writeVarint((fieldNumber shl 3) or 2) + writeVarint(value.size) + value
}

fun frameMessage(payload: ByteArray): ByteArray {
    return writeVarint(payload.size) + payload
}

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
    } catch (_: Exception) { null }
}

object StatusCode { const val OK = 200; const val ERROR = 400; const val BAD_CONFIGURATION = 401; const val BAD_SECRET = 402 }

// ── Pairing protocol submessages ──

object PairingRequest {
    fun encode(serviceName: String, clientName: String): ByteArray =
        lengthDelimitedField(1, serviceName.toByteArray()) + lengthDelimitedField(2, clientName.toByteArray())
}

object OptionsMsg {
    fun encode(encodingType: Int = 3, symbolLength: Int = 6, role: Int = 1): ByteArray {
        val encoding = varintField(1, encodingType) + varintField(2, symbolLength)
        return lengthDelimitedField(1, encoding) + varintField(3, role)
    }
}

object ConfigurationMsg {
    fun encode(encodingType: Int = 3, symbolLength: Int = 6, clientRole: Int = 1): ByteArray {
        val encoding = varintField(1, encodingType) + varintField(2, symbolLength)
        return lengthDelimitedField(1, encoding) + varintField(2, clientRole)
    }
}

object SecretMsg {
    fun encode(secretBytes: ByteArray): ByteArray {
        require(secretBytes.size == 32)
        return lengthDelimitedField(1, secretBytes)
    }
}

// ── Wiki-style pairing protocol (port 6467) ──

object PoloMessage {
    fun encodeRequest(payload: ByteArray): ByteArray = frameMessage(varintField(1, 2) + varintField(2, StatusCode.OK) + lengthDelimitedField(10, payload))
    fun encodeOptions(payload: ByteArray): ByteArray = frameMessage(varintField(1, 2) + varintField(2, StatusCode.OK) + lengthDelimitedField(20, payload))
    fun encodeConfiguration(payload: ByteArray): ByteArray = frameMessage(varintField(1, 2) + varintField(2, StatusCode.OK) + lengthDelimitedField(30, payload))
    fun encodeSecret(payload: ByteArray): ByteArray = frameMessage(varintField(1, 2) + varintField(2, StatusCode.OK) + lengthDelimitedField(40, payload))

    data class Reply(val status: Int, val fieldNum: Int, val payload: ByteArray?)
    fun decode(data: ByteArray): Reply {
        val stream = DataInputStream(data.inputStream())
        var status = 0; var fieldNum = 0; var payload: ByteArray? = null
        while (stream.available() > 0) {
            val tag = readVarint(stream); val fn = tag ushr 3; val wt = tag and 0x07
            when (fn) {
                1 -> readVarint(stream)
                2 -> status = readVarint(stream)
                10, 11, 20, 30, 31, 40, 41 -> {
                    fieldNum = fn
                    if (wt == 2) { val len = readVarint(stream); payload = if (len > 0) { val d = ByteArray(len); stream.readFully(d); d } else ByteArray(0) }
                    else skipField(stream, wt)
                }
                else -> skipField(stream, wt)
            }
        }
        return Reply(status, fieldNum, payload)
    }
}

// ── Remote control protocol (port 6466) ──

object RemoteKeyEvent {
    fun keyPress(keyCode: Int): ByteArray {
        val inner = varintField(1, keyCode) + varintField(2, 1)
        return frameMessage(lengthDelimitedField(10, inner))
    }
    fun keyRelease(keyCode: Int): ByteArray {
        val inner = varintField(1, keyCode) + varintField(2, 2)
        return frameMessage(lengthDelimitedField(10, inner))
    }
}

object RemoteRelativeEvent {
    /** Relative cursor: message_type=4, field 4=dx, field 5=dy */
    fun encode(dx: Int, dy: Int): ByteArray {
        return frameMessage(varintField(1, 4) + varintField(4, dx) + varintField(5, dy))
    }
}

object RemoteConfig {
    fun encodeConfig(): ByteArray {
        val subInner = varintField(3, 1) + lengthDelimitedField(4, "1".toByteArray()) +
                lengthDelimitedField(5, "androidtv-remote".toByteArray()) + lengthDelimitedField(6, "1.0.0".toByteArray())
        return frameMessage(lengthDelimitedField(1, varintField(1, 622) + lengthDelimitedField(2, subInner)))
    }

    /** field 2 = { 622 } per wiki: [18, 3, 8, 238, 4] */
    fun encodeConfigAck(): ByteArray = frameMessage(lengthDelimitedField(2, varintField(1, 622)))
}

fun readLengthDelimited(stream: DataInputStream): ByteArray {
    val len = readVarint(stream); val data = ByteArray(len); stream.readFully(data); return data
}

private fun skipField(stream: DataInputStream, wireType: Int) {
    when (wireType) { 0 -> readVarint(stream); 2 -> { val len = readVarint(stream); stream.skipBytes(len) }; else -> {} }
}
