package com.leobottaro.magicremote.data.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream

/**
 * Minimal protobuf wire format for Android TV remote v2 protocol.
 *
 * PairingMessage fields:
 *   1: protocol_version (int32)
 *   2: encoding (enum = int32): 0=UNUSED, 1=PASSCODE
 *   3: pairing_type (enum = int32): 0=NEW_PAIRING, 1=PAIRING_CODE
 *   4: client_name (bytes)
 *   5: secret (bytes)
 *   6: status (enum = int32): 0=OK, 1=ERROR
 *   7: service_name (bytes)
 *   8: server_name (bytes)
 *   9: server_certificate (bytes)
 *  10: client_certificate (bytes)
 *
 * RemoteMessage fields:
 *   1: message_type (enum = int32): 0=FLING, 1=KEY_EVENT, 2=SET_ACTIVE, 6=SET_VOLUME, 7=VOLUME_CHANGED
 *   2: key_code (int32)
 *   3: key_action (enum = int32): 0=DOWN, 1=UP
 *   6: obfuscated_volume (int32)
 */

// Wire types
private const val WIRE_VARINT = 0
private const val WIRE_LENGTH_DELIMITED = 2

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

// ── Field writers ──

fun writeVarintField(fieldNumber: Int, value: Int): ByteArray {
    val tag = (fieldNumber shl 3) or WIRE_VARINT
    return writeVarint(tag) + writeVarint(value)
}

fun writeLengthDelimitedField(fieldNumber: Int, value: ByteArray): ByteArray {
    val tag = (fieldNumber shl 3) or WIRE_LENGTH_DELIMITED
    return writeVarint(tag) + writeVarint(value.size) + value
}

// ── PairingMessage ──

data class PairingMessage(
    val protocolVersion: Int = 0,
    val encoding: Int = 0,
    val pairingType: Int = 0,
    val clientName: ByteArray? = null,
    val secret: ByteArray? = null,
    val status: Int = 0,
    val serviceName: ByteArray? = null,
    val serverName: ByteArray? = null,
    val serverCertificate: ByteArray? = null,
    val clientCertificate: ByteArray? = null
) {
    fun encode(): ByteArray {
        val buf = ByteArrayOutputStream()
        if (protocolVersion != 0) buf.write(writeVarintField(1, protocolVersion))
        if (encoding != 0) buf.write(writeVarintField(2, encoding))
        if (pairingType != 0) buf.write(writeVarintField(3, pairingType))
        clientName?.let { buf.write(writeLengthDelimitedField(4, it)) }
        secret?.let { buf.write(writeLengthDelimitedField(5, it)) }
        if (status != 0) buf.write(writeVarintField(6, status))
        serviceName?.let { buf.write(writeLengthDelimitedField(7, it)) }
        serverName?.let { buf.write(writeLengthDelimitedField(8, it)) }
        serverCertificate?.let { buf.write(writeLengthDelimitedField(9, it)) }
        clientCertificate?.let { buf.write(writeLengthDelimitedField(10, it)) }
        return buf.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): PairingMessage {
            val stream = DataInputStream(data.inputStream())
            var protocolVersion = 0
            var encoding = 0
            var pairingType = 0
            var clientName: ByteArray? = null
            var secret: ByteArray? = null
            var status = 0
            var serviceName: ByteArray? = null
            var serverName: ByteArray? = null
            var serverCertificate: ByteArray? = null
            var clientCertificate: ByteArray? = null

            while (stream.available() > 0) {
                val tag = readVarint(stream)
                val fieldNum = tag ushr 3
                val wireType = tag and 0x07
                when (fieldNum) {
                    1 -> protocolVersion = readVarint(stream)
                    2 -> encoding = readVarint(stream)
                    3 -> pairingType = readVarint(stream)
                    4 -> clientName = readLengthDelimited(stream)
                    5 -> secret = readLengthDelimited(stream)
                    6 -> status = readVarint(stream)
                    7 -> serviceName = readLengthDelimited(stream)
                    8 -> serverName = readLengthDelimited(stream)
                    9 -> serverCertificate = readLengthDelimited(stream)
                    10 -> clientCertificate = readLengthDelimited(stream)
                    else -> skipField(stream, wireType)
                }
            }

            return PairingMessage(
                protocolVersion = protocolVersion,
                encoding = encoding,
                pairingType = pairingType,
                clientName = clientName,
                secret = secret,
                status = status,
                serviceName = serviceName,
                serverName = serverName,
                serverCertificate = serverCertificate,
                clientCertificate = clientCertificate
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairingMessage) return false
        return protocolVersion == other.protocolVersion &&
                encoding == other.encoding &&
                pairingType == other.pairingType &&
                status == other.status &&
                clientName.contentEquals(other.clientName ?: ByteArray(0)) &&
                secret.contentEquals(other.secret ?: ByteArray(0)) &&
                serviceName.contentEquals(other.serviceName ?: ByteArray(0)) &&
                serverName.contentEquals(other.serverName ?: ByteArray(0)) &&
                serverCertificate.contentEquals(other.serverCertificate ?: ByteArray(0)) &&
                clientCertificate.contentEquals(other.clientCertificate ?: ByteArray(0))
    }

    override fun hashCode(): Int {
        var result = protocolVersion
        result = 31 * result + encoding
        result = 31 * result + pairingType
        result = 31 * result + status
        result = 31 * result + (clientName?.contentHashCode() ?: 0)
        result = 31 * result + (secret?.contentHashCode() ?: 0)
        result = 31 * result + (serviceName?.contentHashCode() ?: 0)
        result = 31 * result + (serverName?.contentHashCode() ?: 0)
        result = 31 * result + (serverCertificate?.contentHashCode() ?: 0)
        result = 31 * result + (clientCertificate?.contentHashCode() ?: 0)
        return result
    }
}

// ── RemoteMessage ──

data class RemoteMessage(
    val messageType: Int = 0,
    val keyCode: Int = 0,
    val keyAction: Int = 0,
    val obfuscatedVolume: Int = 0
) {
    fun encode(): ByteArray {
        val buf = ByteArrayOutputStream()
        if (messageType != 0) buf.write(writeVarintField(1, messageType))
        if (keyCode != 0) buf.write(writeVarintField(2, keyCode))
        if (keyAction != 0) buf.write(writeVarintField(3, keyAction))
        if (obfuscatedVolume != 0) buf.write(writeVarintField(6, obfuscatedVolume))
        return buf.toByteArray()
    }

    companion object {
        fun decode(data: ByteArray): RemoteMessage {
            val stream = DataInputStream(data.inputStream())
            var messageType = 0
            var keyCode = 0
            var keyAction = 0
            var obfuscatedVolume = 0

            while (stream.available() > 0) {
                val tag = readVarint(stream)
                val fieldNum = tag ushr 3
                val wireType = tag and 0x07
                when (fieldNum) {
                    1 -> messageType = readVarint(stream)
                    2 -> keyCode = readVarint(stream)
                    3 -> keyAction = readVarint(stream)
                    6 -> obfuscatedVolume = readVarint(stream)
                    else -> skipField(stream, wireType)
                }
            }

            return RemoteMessage(
                messageType = messageType,
                keyCode = keyCode,
                keyAction = keyAction,
                obfuscatedVolume = obfuscatedVolume
            )
        }
    }
}

object MessageTypes {
    const val PAIRING_REQUEST = 0
    const val PAIRING_REQUEST_ACK = 1
    const val PAIRING_RESPONSE = 2
    const val PAIRING_RESPONSE_ACK = 3

    const val REMOTE_FLING = 0
    const val REMOTE_KEY_EVENT = 1
    const val REMOTE_SET_ACTIVE = 2
    const val REMOTE_SET_VOLUME = 6
    const val REMOTE_VOLUME_CHANGED = 7

    const val KEY_ACTION_DOWN = 0
    const val KEY_ACTION_UP = 1

    const val PAIRING_ENCODING_PASSCODE = 1
    const val PAIRING_TYPE_NEW = 0
    const val PAIRING_TYPE_CODE = 1
    const val PAIRING_STATUS_OK = 0
    const val PAIRING_STATUS_ERROR = 1
}

// ── Shared helpers ──

private fun readLengthDelimited(stream: DataInputStream): ByteArray {
    val len = readVarint(stream)
    val data = ByteArray(len)
    stream.readFully(data)
    return data
}

private fun skipField(stream: DataInputStream, wireType: Int) {
    when (wireType) {
        WIRE_VARINT -> readVarint(stream)
        WIRE_LENGTH_DELIMITED -> {
            val len = readVarint(stream)
            stream.skipBytes(len)
        }
        else -> throw IllegalArgumentException("Unknown wire type: $wireType")
    }
}

/** Write a length-prefixed protobuf message. */
fun writeMessage(message: ByteArray): ByteArray {
    val len = message.size
    val header = byteArrayOf(
        (len shr 24).toByte(),
        (len shr 16).toByte(),
        (len shr 8).toByte(),
        len.toByte()
    )
    return header + message
}

/** Read a length-prefixed protobuf message from an InputStream. Returns null if not enough data. */
fun readMessage(inputStream: InputStream): ByteArray? {
    val dataStream = DataInputStream(inputStream)

    // Read 4-byte length header
    return try {
        val len = dataStream.readInt()
        if (len <= 0 || len > 1024 * 1024) return null
        val data = ByteArray(len)
        dataStream.readFully(data)
        data
    } catch (e: Exception) {
        null
    }
}
