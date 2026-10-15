package tech.unyielded.offgrid.mesh

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Lightweight JSON envelopes for V5 store-and-forward over the existing
 * encrypted Bluetooth link. The outer RFCOMM session is still AES-GCM.
 * These envelopes only describe *who* the message is for and hop metadata.
 */
object MeshProtocol {

    const val TYPE_RELAY_OFFER = "relay_offer"     // "I have these pending msgIds"
    const val TYPE_RELAY_REQUEST = "relay_request" // "Send me these msgIds"
    const val TYPE_RELAY_PAYLOAD = "relay_payload" // actual MeshMessage
    const val TYPE_DIRECT = "direct"               // normal 1:1 chat (already handled)

    fun encodeOffer(msgIds: List<String>): String {
        return JSONObject().apply {
            put("t", TYPE_RELAY_OFFER)
            put("ids", JSONArray(msgIds))
        }.toString()
    }

    fun encodeRequest(msgIds: List<String>): String {
        return JSONObject().apply {
            put("t", TYPE_RELAY_REQUEST)
            put("ids", JSONArray(msgIds))
        }.toString()
    }

    fun encodePayload(msg: MeshStore.MeshMessage): String {
        return JSONObject().apply {
            put("t", TYPE_RELAY_PAYLOAD)
            put("msgId", msg.msgId)
            put("fromId", msg.fromId)
            put("toId", msg.toId)
            put("hop", msg.hop)
            put("ttl", msg.ttl)
            put("payloadB64", msg.payloadB64)
            put("createdAt", msg.createdAt)
        }.toString()
    }

    fun parse(raw: String): Parsed? {
        return try {
            val o = JSONObject(raw)
            when (o.getString("t")) {
                TYPE_RELAY_OFFER -> {
                    val ids = mutableListOf<String>()
                    val arr = o.getJSONArray("ids")
                    for (i in 0 until arr.length()) ids.add(arr.getString(i))
                    Parsed.Offer(ids)
                }
                TYPE_RELAY_REQUEST -> {
                    val ids = mutableListOf<String>()
                    val arr = o.getJSONArray("ids")
                    for (i in 0 until arr.length()) ids.add(arr.getString(i))
                    Parsed.Request(ids)
                }
                TYPE_RELAY_PAYLOAD -> {
                    Parsed.Payload(
                        MeshStore.MeshMessage(
                            msgId = o.getString("msgId"),
                            fromId = o.getString("fromId"),
                            toId = o.getString("toId"),
                            hop = o.getInt("hop"),
                            ttl = o.getLong("ttl"),
                            payloadB64 = o.getString("payloadB64"),
                            createdAt = o.optLong("createdAt", 0L)
                        )
                    )
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    sealed class Parsed {
        data class Offer(val msgIds: List<String>) : Parsed()
        data class Request(val msgIds: List<String>) : Parsed()
        data class Payload(val msg: MeshStore.MeshMessage) : Parsed()
    }
}
