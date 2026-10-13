package tech.unyielded.offgrid.crypto

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object OffgridProtocol {

    const val VERSION: Byte = SessionCrypto.PROTOCOL_VERSION
    const val MAX_PAYLOAD = 4096 + 12 + 16

    object Type {
        const val HANDSHAKE_PUBKEY: Byte = 0x01
        const val ENCRYPTED_MESSAGE: Byte = 0x02
        const val PROTOCOL_ERROR: Byte = 0x03
        const val DISCONNECT: Byte = 0x04
    }

    sealed class Frame {
        data class HandshakePubkey(val publicKey: ByteArray) : Frame() {
            override fun equals(other: Any?) = other is HandshakePubkey && publicKey.contentEquals(other.publicKey)
            override fun hashCode() = publicKey.contentHashCode()
        }
        data class EncryptedMessage(val nonce: ByteArray, val ciphertext: ByteArray) : Frame() {
            override fun equals(other: Any?) =
                other is EncryptedMessage &&
                nonce.contentEquals(other.nonce) &&
                ciphertext.contentEquals(other.ciphertext)
            override fun hashCode() = nonce.contentHashCode() xor ciphertext.contentHashCode()
        }
        data class ProtocolError(val message: String) : Frame()
        object Disconnect : Frame()
    }

    fun writeFrame(out: OutputStream, type: Byte, payload: ByteArray) {
        if (payload.size > MAX_PAYLOAD) {
            throw IOException("Payload too large: ${payload.size}")
        }
        val header = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        header.put(VERSION)
        header.put(type)
        header.putShort(payload.size.toShort())
        out.write(header.array())
        if (payload.isNotEmpty()) {
            out.write(payload)
        }
        out.flush()
    }

    fun writeHandshakePubkey(out: OutputStream, publicKey: ByteArray) {
        writeFrame(out, Type.HANDSHAKE_PUBKEY, publicKey)
    }

    fun writeEncryptedMessage(out: OutputStream, frame: SessionCrypto.EncryptedFrame) {
        val payload = frame.nonce + frame.ciphertext
        writeFrame(out, Type.ENCRYPTED_MESSAGE, payload)
    }

    fun writeDisconnect(out: OutputStream) {
        writeFrame(out, Type.DISCONNECT, ByteArray(0))
    }

    fun writeProtocolError(out: OutputStream, message: String) {
        writeFrame(out, Type.PROTOCOL_ERROR, message.toByteArray(Charsets.UTF_8))
    }

    fun readFrame(input: InputStream): Frame {
        val header = ByteArray(4)
        readFully(input, header)

        val version = header[0]
        val type = header[1]
        val length = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)

        if (version != VERSION) {
            throw ProtocolException("Unsupported protocol version: 0x${version.toString(16)}")
        }
        if (length < 0 || length > MAX_PAYLOAD) {
            throw ProtocolException("Invalid payload length: $length")
        }

        val payload = if (length > 0) {
            val buf = ByteArray(length)
            readFully(input, buf)
            buf
        } else {
            ByteArray(0)
        }

        return when (type) {
            Type.HANDSHAKE_PUBKEY -> {
                if (payload.isEmpty()) throw ProtocolException("Empty public key")
                Frame.HandshakePubkey(payload)
            }
            Type.ENCRYPTED_MESSAGE -> {
                if (payload.size < 12 + 16) {
                    throw ProtocolException("Encrypted message too short")
                }
                val nonce = payload.copyOfRange(0, 12)
                val ciphertext = payload.copyOfRange(12, payload.size)
                Frame.EncryptedMessage(nonce, ciphertext)
            }
            Type.PROTOCOL_ERROR -> {
                Frame.ProtocolError(String(payload, Charsets.UTF_8))
            }
            Type.DISCONNECT -> Frame.Disconnect
            else -> throw ProtocolException("Unknown frame type: 0x${type.toString(16)}")
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) {
                throw EOFException("Connection closed while reading frame")
            }
            offset += read
        }
    }

    class ProtocolException(message: String) : IOException(message)
}
