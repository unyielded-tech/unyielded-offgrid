package tech.unyielded.offgrid.crypto

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * V3 session cryptography for UNYIELDED // OFFGRID
 *
 * - Ephemeral ECDH on P-256 (secp256r1)
 * - HKDF-SHA-256 (extract + expand)
 * - AES-256-GCM (128-bit tag, fresh 96-bit nonce)
 * - Fingerprint from both public keys
 *
 * Unauthenticated ECDH does NOT prevent MITM.
 * Users MUST compare fingerprints in person.
 */
object SessionCrypto {

    const val PROTOCOL_VERSION: Byte = 0x04
    private const val CURVE = "secp256r1"
    private const val GCM_TAG_BITS = 128
    private const val GCM_NONCE_BYTES = 12
    private const val SESSION_KEY_BYTES = 32
    private const val MAX_PLAINTEXT_BYTES = 4096

    private val secureRandom = SecureRandom()

    private val HKDF_SALT_LABEL = "OFFGRID-V3-HKDF-SALT".toByteArray(Charsets.UTF_8)
    private val HKDF_INFO_LABEL = "OFFGRID-V3-SESSION-KEY".toByteArray(Charsets.UTF_8)
    private val FINGERPRINT_LABEL = "OFFGRID-V3-FINGERPRINT".toByteArray(Charsets.UTF_8)

    data class KeyPairBytes(
        val publicKeyEncoded: ByteArray,
        val privateKey: ECPrivateKey
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KeyPairBytes) return false
            return publicKeyEncoded.contentEquals(other.publicKeyEncoded)
        }
        override fun hashCode(): Int = publicKeyEncoded.contentHashCode()
    }

    data class SessionKeys(
        val encryptionKey: ByteArray,
        val fingerprint: String,
        val fingerprintFull: String
    ) {
        fun clear() {
            encryptionKey.fill(0)
        }
    }

    data class EncryptedFrame(
        val nonce: ByteArray,
        val ciphertext: ByteArray
    )

    fun generateEphemeralKeyPair(): KeyPairBytes {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(CURVE), secureRandom)
        val kp: KeyPair = kpg.generateKeyPair()
        return KeyPairBytes(
            publicKeyEncoded = kp.public.encoded,
            privateKey = kp.private as ECPrivateKey
        )
    }

    fun deriveSessionKeys(
        myPrivate: ECPrivateKey,
        peerPublicEncoded: ByteArray,
        myPublicEncoded: ByteArray,
        isInitiator: Boolean
    ): SessionKeys {
        val kf = KeyFactory.getInstance("EC")
        val peerPublic = kf.generatePublic(X509EncodedKeySpec(peerPublicEncoded)) as ECPublicKey

        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivate)
        ka.doPhase(peerPublic, true)
        val sharedSecret = ka.generateSecret()

        try {
            val (firstPub, secondPub) = if (compareByteArrays(myPublicEncoded, peerPublicEncoded) < 0) {
                myPublicEncoded to peerPublicEncoded
            } else {
                peerPublicEncoded to myPublicEncoded
            }

            val transcript = ByteArray(1 + firstPub.size + secondPub.size)
            transcript[0] = PROTOCOL_VERSION
            System.arraycopy(firstPub, 0, transcript, 1, firstPub.size)
            System.arraycopy(secondPub, 0, transcript, 1 + firstPub.size, secondPub.size)

            val ikm = sharedSecret + transcript
            val prk = hkdfExtract(HKDF_SALT_LABEL, ikm)
            val okm = hkdfExpand(prk, HKDF_INFO_LABEL, SESSION_KEY_BYTES)

            val fpHash = MessageDigest.getInstance("SHA-256").digest(
                FINGERPRINT_LABEL + firstPub + secondPub
            )
            val fingerprintFull = fpHash.joinToString("") { "%02X".format(it) }
            val fingerprint = formatFingerprint(fpHash)

            return SessionKeys(
                encryptionKey = okm,
                fingerprint = fingerprint,
                fingerprintFull = fingerprintFull
            )
        } finally {
            sharedSecret.fill(0)
        }
    }

    fun encrypt(plaintext: ByteArray, key: ByteArray): EncryptedFrame {
        require(plaintext.size <= MAX_PLAINTEXT_BYTES) {
            "Message exceeds maximum size of $MAX_PLAINTEXT_BYTES bytes"
        }
        require(key.size == SESSION_KEY_BYTES)

        val nonce = ByteArray(GCM_NONCE_BYTES)
        secureRandom.nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_BITS, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), spec)

        val ciphertext = cipher.doFinal(plaintext)
        return EncryptedFrame(nonce, ciphertext)
    }

    fun decrypt(frame: EncryptedFrame, key: ByteArray): ByteArray {
        require(key.size == SESSION_KEY_BYTES)
        require(frame.nonce.size == GCM_NONCE_BYTES)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_BITS, frame.nonce)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)

        return cipher.doFinal(frame.ciphertext)
    }

    private fun hkdfExtract(salt: ByteArray, ikm: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))

        val hashLen = 32
        val n = (length + hashLen - 1) / hashLen
        require(n <= 255)

        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0

        for (i in 1..n) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(i.toByte())
            t = mac.doFinal()
            val toCopy = minOf(hashLen, length - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
        }
        return okm
    }

    private fun compareByteArrays(a: ByteArray, b: ByteArray): Int {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val cmp = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return a.size - b.size
    }

    private fun formatFingerprint(hash: ByteArray): String {
        val hex = hash.take(8).joinToString("") { "%02X".format(it) }
        return hex.chunked(4).joinToString(" ")
    }

    fun clearByteArray(arr: ByteArray) {
        arr.fill(0)
    }
}
