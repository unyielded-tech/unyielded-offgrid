package tech.unyielded.offgrid.crypto

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OffgridProtocol {

    const val VERSION: Byte = 0x04
    const val MAX_PAYLOAD = 256 * 1024

    object Type {
        const val HANDSHAKE_PUBKEY: Byte = 0x01
        const val ENCRYPTED_TEXT: Byte = 0x02
        const val PROTOCOL_ERROR: Byte = 0x03
        const val DISCONNECT: Byte = 0x04
        const val ENCRYPTED_VOICE: Byte = 0x05
        const val ENCRYPTED_IMAGE: Byte = 0x06
        const val IDENTITY_ANNOUNCE: Byte = 0x07
    }

    sealed class Frame {
        data class HandshakePubkey(val publicKey: ByteArray) : Frame() {
            override fun equals(other: Any?) = other is HandshakePubkey && publicKey.contentEquals(other.publicKey)
            override fun hashCode() = publicKey.contentHashCode()
        }
        data class EncryptedText(val nonce: ByteArray, val ciphertext: ByteArray) : Frame() {
            override fun equals(other: Any?) = other is EncryptedText && nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)
            override fun hashCode() = nonce.contentHashCode() xor ciphertext.contentHashCode()
        }
        data class EncryptedVoice(val nonce: ByteArray, val ciphertext: ByteArray) : Frame() {
            override fun equals(other: Any?) = other is EncryptedVoice && nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)
            override fun hashCode() = nonce.contentHashCode() xor ciphertext.contentHashCode()
        }
        data class EncryptedImage(val nonce: ByteArray, val ciphertext: ByteArray) : Frame() {
            override fun equals(other: Any?) = other is EncryptedImage && nonce.contentEquals(other.nonce) && ciphertext.contentEquals(other.ciphertext)
            override fun hashCode() = nonce.contentHashCode() xor ciphertext.contentHashCode()
        }
        data class IdentityAnnounce(val shortId: String, val displayName: String) : Frame()
        data class ProtocolError(val message: String) : Frame()
        object Disconnect : Frame()
    }

    fun writeFrame(out: OutputStream, type: Byte, payload: ByteArray) {
        if (payload.size > MAX_PAYLOAD) throw IOException("Payload too large: ${payload.size}")
        val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
        header.put(VERSION)
        header.put(type)
        header.putInt(payload.size)
        out.write(header.array())
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    fun writeHandshakePubkey(out: OutputStream, publicKey: ByteArray) {
        writeFrame(out, Type.HANDSHAKE_PUBKEY, publicKey)
    }

    fun writeEncryptedText(out: OutputStream, frame: SessionCrypto.EncryptedFrame) {
        writeFrame(out, Type.ENCRYPTED_TEXT, frame.nonce + frame.ciphertext)
    }

    fun writeEncryptedVoice(out: OutputStream, frame: SessionCrypto.EncryptedFrame) {
        writeFrame(out, Type.ENCRYPTED_VOICE, frame.nonce + frame.ciphertext)
    }

    fun writeEncryptedImage(out: OutputStream, frame: SessionCrypto.EncryptedFrame) {
        writeFrame(out, Type.ENCRYPTED_IMAGE, frame.nonce + frame.ciphertext)
    }

    fun writeIdentityAnnounce(out: OutputStream, shortId: String, displayName: String) {
        writeFrame(out, Type.IDENTITY_ANNOUNCE, "$shortId|$displayName".toByteArray(Charsets.UTF_8))
    }

    fun writeDisconnect(out: OutputStream) {
        writeFrame(out, Type.DISCONNECT, ByteArray(0))
    }

    fun writeProtocolError(out: OutputStream, message: String) {
        writeFrame(out, Type.PROTOCOL_ERROR, message.toByteArray(Charsets.UTF_8))
    }

    fun readFrame(input: InputStream): Frame {
        val header = ByteArray(6)
        readFully(input, header)
        val version = header[0]
        val type = header[1]
        val length = ByteBuffer.wrap(header, 2, 4).order(ByteOrder.BIG_ENDIAN).int

        if (version != VERSION && version != 0x03.toByte()) {
            throw ProtocolException("Unsupported protocol version: 0x${version.toString(16)}")
        }
        if (length < 0 || length > MAX_PAYLOAD) {
            throw ProtocolException("Invalid payload length: $length")
        }

        val payload = if (length > 0) {
            val buf = ByteArray(length)
            readFully(input, buf)
            buf
        } else ByteArray(0)

        return when (type) {
            Type.HANDSHAKE_PUBKEY -> {
                if (payload.isEmpty()) throw ProtocolException("Empty public key")
                Frame.HandshakePubkey(payload)
            }
            Type.ENCRYPTED_TEXT, 0x02.toByte() -> {
                if (payload.size < 28) throw ProtocolException("Encrypted text too short")
                Frame.EncryptedText(payload.copyOfRange(0, 12), payload.copyOfRange(12, payload.size))
            }
            Type.ENCRYPTED_VOICE -> {
                if (payload.size < 28) throw ProtocolException("Voice too short")
                Frame.EncryptedVoice(payload.copyOfRange(0, 12), payload.copyOfRange(12, payload.size))
            }
            Type.ENCRYPTED_IMAGE -> {
                if (payload.size < 28) throw ProtocolException("Image too short")
                Frame.EncryptedImage(payload.copyOfRange(0, 12), payload.copyOfRange(12, payload.size))
            }
            Type.IDENTITY_ANNOUNCE -> {
                val str = String(payload, Charsets.UTF_8)
                val parts = str.split("|", limit = 2)
                if (parts.size != 2) throw ProtocolException("Bad identity announce")
                Frame.IdentityAnnounce(parts[0], parts[1])
            }
            Type.PROTOCOL_ERROR -> Frame.ProtocolError(String(payload, Charsets.UTF_8))
            Type.DISCONNECT -> Frame.Disconnect
            else -> throw ProtocolException("Unknown frame type: 0x${type.toString(16)}")
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) throw EOFException("Connection closed while reading frame")
            offset += read
        }
    }

    class ProtocolException(message: String) : IOException(message)
}
