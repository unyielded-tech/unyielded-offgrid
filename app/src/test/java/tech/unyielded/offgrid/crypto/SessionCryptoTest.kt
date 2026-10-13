package tech.unyielded.offgrid.crypto

import org.junit.Assert.*
import org.junit.Test

class SessionCryptoTest {

    @Test
    fun bothSidesDeriveIdenticalSessionKeys() {
        val alice = SessionCrypto.generateEphemeralKeyPair()
        val bob = SessionCrypto.generateEphemeralKeyPair()

        val aliceKeys = SessionCrypto.deriveSessionKeys(
            myPrivate = alice.privateKey,
            peerPublicEncoded = bob.publicKeyEncoded,
            myPublicEncoded = alice.publicKeyEncoded,
            isInitiator = true
        )

        val bobKeys = SessionCrypto.deriveSessionKeys(
            myPrivate = bob.privateKey,
            peerPublicEncoded = alice.publicKeyEncoded,
            myPublicEncoded = bob.publicKeyEncoded,
            isInitiator = false
        )

        assertArrayEquals(aliceKeys.encryptionKey, bobKeys.encryptionKey)
        assertEquals(aliceKeys.fingerprint, bobKeys.fingerprint)
        assertEquals(aliceKeys.fingerprintFull, bobKeys.fingerprintFull)
        assertTrue(aliceKeys.fingerprint.matches(Regex("[0-9A-F]{4} [0-9A-F]{4} [0-9A-F]{4} [0-9A-F]{4}")))
    }

    @Test
    fun encryptDecryptRoundTrip() {
        val kp = SessionCrypto.generateEphemeralKeyPair()
        val peer = SessionCrypto.generateEphemeralKeyPair()
        val keys = SessionCrypto.deriveSessionKeys(
            kp.privateKey, peer.publicKeyEncoded, kp.publicKeyEncoded, true
        )

        val plaintext = "Hello OFFGRID V3 — private message".toByteArray(Charsets.UTF_8)
        val frame = SessionCrypto.encrypt(plaintext, keys.encryptionKey)
        val recovered = SessionCrypto.decrypt(frame, keys.encryptionKey)

        assertArrayEquals(plaintext, recovered)
        assertEquals(12, frame.nonce.size)
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val kp = SessionCrypto.generateEphemeralKeyPair()
        val peer = SessionCrypto.generateEphemeralKeyPair()
        val keys = SessionCrypto.deriveSessionKeys(
            kp.privateKey, peer.publicKeyEncoded, kp.publicKeyEncoded, true
        )

        val frame = SessionCrypto.encrypt("secret".toByteArray(), keys.encryptionKey)
        frame.ciphertext[0] = (frame.ciphertext[0].toInt() xor 0x01).toByte()

        try {
            SessionCrypto.decrypt(frame, keys.encryptionKey)
            fail("Expected authentication failure")
        } catch (e: Exception) {
            assertTrue(true)
        }
    }

    @Test
    fun wrongKeyFailsAuthentication() {
        val kp1 = SessionCrypto.generateEphemeralKeyPair()
        val peer1 = SessionCrypto.generateEphemeralKeyPair()
        val keys1 = SessionCrypto.deriveSessionKeys(
            kp1.privateKey, peer1.publicKeyEncoded, kp1.publicKeyEncoded, true
        )

        val kp2 = SessionCrypto.generateEphemeralKeyPair()
        val peer2 = SessionCrypto.generateEphemeralKeyPair()
        val keys2 = SessionCrypto.deriveSessionKeys(
            kp2.privateKey, peer2.publicKeyEncoded, kp2.publicKeyEncoded, true
        )

        val frame = SessionCrypto.encrypt("test".toByteArray(), keys1.encryptionKey)

        try {
            SessionCrypto.decrypt(frame, keys2.encryptionKey)
            fail("Expected authentication failure with wrong key")
        } catch (e: Exception) {
            assertTrue(true)
        }
    }

    @Test
    fun noncesAreUnique() {
        val kp = SessionCrypto.generateEphemeralKeyPair()
        val peer = SessionCrypto.generateEphemeralKeyPair()
        val keys = SessionCrypto.deriveSessionKeys(
            kp.privateKey, peer.publicKeyEncoded, kp.publicKeyEncoded, true
        )

        val nonces = mutableSetOf<String>()
        repeat(50) {
            val frame = SessionCrypto.encrypt("msg$it".toByteArray(), keys.encryptionKey)
            val nonceHex = frame.nonce.joinToString("") { b -> "%02x".format(b) }
            assertTrue("Nonce collision detected", nonces.add(nonceHex))
        }
    }

    @Test
    fun oversizedMessageIsRejected() {
        val kp = SessionCrypto.generateEphemeralKeyPair()
        val peer = SessionCrypto.generateEphemeralKeyPair()
        val keys = SessionCrypto.deriveSessionKeys(
            kp.privateKey, peer.publicKeyEncoded, kp.publicKeyEncoded, true
        )

        val huge = ByteArray(5000) { 0x41 }
        try {
            SessionCrypto.encrypt(huge, keys.encryptionKey)
            fail("Expected size limit exception")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("maximum") == true)
        }
    }
}
