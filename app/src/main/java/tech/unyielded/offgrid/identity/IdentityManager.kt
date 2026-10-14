package tech.unyielded.offgrid.identity

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

class IdentityManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val secureRandom = SecureRandom()

    data class Identity(
        val displayName: String,
        val shortId: String,
        val publicKeyEncoded: ByteArray,
        val fingerprint: String
    ) {
        val inviteCode: String
            get() = shortId + "|" + displayName
    }

    fun hasIdentity(): Boolean {
        return prefs.contains(KEY_PUBLIC) && prefs.contains(KEY_PRIVATE) && prefs.contains(KEY_NAME)
    }

    fun getIdentity(): Identity? {
        if (!hasIdentity()) return null
        val name = prefs.getString(KEY_NAME, null) ?: return null
        val pubB64 = prefs.getString(KEY_PUBLIC, null) ?: return null
        val pub = Base64.decode(pubB64, Base64.NO_WRAP)
        val shortId = prefs.getString(KEY_SHORT_ID, null) ?: deriveShortId(pub)
        val fingerprint = prefs.getString(KEY_FINGERPRINT, null) ?: deriveFingerprint(pub)
        return Identity(name, shortId, pub, fingerprint)
    }

    fun createIdentity(displayName: String): Identity {
        val cleanName = displayName.trim().take(32).ifBlank { "OFFGRID User" }

        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        val kp = kpg.generateKeyPair()

        val publicEncoded = kp.public.encoded
        val privateEncoded = kp.private.encoded

        val shortId = deriveShortId(publicEncoded)
        val fingerprint = deriveFingerprint(publicEncoded)

        prefs.edit()
            .putString(KEY_NAME, cleanName)
            .putString(KEY_PUBLIC, Base64.encodeToString(publicEncoded, Base64.NO_WRAP))
            .putString(KEY_PRIVATE, Base64.encodeToString(privateEncoded, Base64.NO_WRAP))
            .putString(KEY_SHORT_ID, shortId)
            .putString(KEY_FINGERPRINT, fingerprint)
            .apply()

        return Identity(cleanName, shortId, publicEncoded, fingerprint)
    }

    fun updateDisplayName(newName: String) {
        val clean = newName.trim().take(32).ifBlank { return }
        prefs.edit().putString(KEY_NAME, clean).apply()
    }

    fun getPrivateKey(): ECPrivateKey? {
        val b64 = prefs.getString(KEY_PRIVATE, null) ?: return null
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePrivate(PKCS8EncodedKeySpec(bytes)) as ECPrivateKey
    }

    fun getPublicKey(): ECPublicKey? {
        val b64 = prefs.getString(KEY_PUBLIC, null) ?: return null
        val bytes = Base64.decode(b64, Base64.NO_WRAP)
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(X509EncodedKeySpec(bytes)) as ECPublicKey
    }

    fun parseInviteCode(code: String): Pair<String, String>? {
        val parts = code.trim().split("|", limit = 2)
        if (parts.size != 2) return null
        val shortId = parts[0].trim().uppercase()
        val name = parts[1].trim()
        if (shortId.length < 4 || name.isBlank()) return null
        return shortId to name
    }

    private fun deriveShortId(publicKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(publicKey)
        val hex = hash.take(4).joinToString("") { "%02X".format(it) }
        return hex.substring(0, 4) + "-" + hex.substring(4, 8)
    }

    private fun deriveFingerprint(publicKey: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(
            "OFFGRID-IDENTITY-V4".toByteArray() + publicKey
        )
        return hash.take(8).joinToString("") { "%02X".format(it) }
            .chunked(4).joinToString(" ")
    }

    companion object {
        private const val PREFS_NAME = "offgrid_identity_v4"
        private const val KEY_NAME = "display_name"
        private const val KEY_PUBLIC = "public_key"
        private const val KEY_PRIVATE = "private_key"
        private const val KEY_SHORT_ID = "short_id"
        private const val KEY_FINGERPRINT = "fingerprint"
    }
}
