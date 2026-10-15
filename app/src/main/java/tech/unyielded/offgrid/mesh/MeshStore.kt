package tech.unyielded.offgrid.mesh

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * V5 store-and-forward message queue.
 * Messages sit here until a Bluetooth path to the recipient appears.
 */
class MeshStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class MeshMessage(
        val msgId: String,
        val fromId: String,
        val toId: String,
        val hop: Int,
        val ttl: Long,           // epoch millis when it expires
        val payloadB64: String,  // already encrypted ciphertext
        val createdAt: Long = System.currentTimeMillis()
    )

    fun add(msg: MeshMessage): Boolean {
        if (isExpired(msg) || hasSeen(msg.msgId)) return false
        val list = getPending().toMutableList()
        if (list.any { it.msgId == msg.msgId }) return false
        list.add(msg)
        // Cap queue size
        val trimmed = list.sortedByDescending { it.createdAt }.take(MAX_QUEUE)
        savePending(trimmed)
        markSeen(msg.msgId)
        return true
    }

    fun getPending(): List<MeshMessage> {
        val raw = prefs.getString(KEY_PENDING, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                MeshMessage(
                    msgId = o.getString("msgId"),
                    fromId = o.getString("fromId"),
                    toId = o.getString("toId"),
                    hop = o.getInt("hop"),
                    ttl = o.getLong("ttl"),
                    payloadB64 = o.getString("payloadB64"),
                    createdAt = o.optLong("createdAt", 0L)
                )
            }.filter { !isExpired(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Messages destined for a specific shortId (or that we should relay). */
    fun getForPeer(peerShortId: String, myShortId: String): List<MeshMessage> {
        return getPending().filter { msg ->
            msg.toId.equals(peerShortId, true) ||
            (!msg.toId.equals(myShortId, true) && msg.hop < MAX_HOP)
        }
    }

    fun remove(msgId: String) {
        savePending(getPending().filterNot { it.msgId == msgId })
    }

    fun createOutgoing(
        fromId: String,
        toId: String,
        encryptedPayload: ByteArray
    ): MeshMessage {
        return MeshMessage(
            msgId = UUID.randomUUID().toString(),
            fromId = fromId,
            toId = toId,
            hop = 0,
            ttl = System.currentTimeMillis() + TTL_MS,
            payloadB64 = Base64.encodeToString(encryptedPayload, Base64.NO_WRAP)
        )
    }

    fun incrementHop(msg: MeshMessage): MeshMessage? {
        if (msg.hop + 1 >= MAX_HOP) return null
        if (isExpired(msg)) return null
        return msg.copy(hop = msg.hop + 1)
    }

    private fun isExpired(msg: MeshMessage): Boolean {
        return System.currentTimeMillis() > msg.ttl
    }

    private fun hasSeen(msgId: String): Boolean {
        val seen = prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()
        return seen.contains(msgId)
    }

    private fun markSeen(msgId: String) {
        val seen = (prefs.getStringSet(KEY_SEEN, emptySet()) ?: emptySet()).toMutableSet()
        seen.add(msgId)
        // Keep seen set bounded
        val trimmed = seen.toList().takeLast(MAX_SEEN).toSet()
        prefs.edit().putStringSet(KEY_SEEN, trimmed).apply()
    }

    private fun savePending(list: List<MeshMessage>) {
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().apply {
                put("msgId", m.msgId)
                put("fromId", m.fromId)
                put("toId", m.toId)
                put("hop", m.hop)
                put("ttl", m.ttl)
                put("payloadB64", m.payloadB64)
                put("createdAt", m.createdAt)
            })
        }
        prefs.edit().putString(KEY_PENDING, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "offgrid_mesh_v5"
        private const val KEY_PENDING = "pending"
        private const val KEY_SEEN = "seen"
        const val MAX_HOP = 4
        const val TTL_MS = 24 * 60 * 60 * 1000L  // 24 hours
        private const val MAX_QUEUE = 100
        private const val MAX_SEEN = 500
    }
}
