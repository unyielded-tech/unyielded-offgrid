package tech.unyielded.offgrid.mesh

import android.content.Context
import android.util.Base64

/**
 * High-level V5 mesh logic.
 * Call onConnected / onTextReceived from the Bluetooth transport.
 */
class MeshCoordinator(
    context: Context,
    private val myShortId: String
) {
    private val store = MeshStore(context)

    var onDeliveredToMe: ((fromId: String, plaintext: ByteArray) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null

    fun pendingCount(): Int = store.getPending().size

    /** Queue an encrypted payload for a remote shortId (may be offline). */
    fun queueFor(toId: String, encryptedPayload: ByteArray): String {
        val msg = store.createOutgoing(myShortId, toId, encryptedPayload)
        store.add(msg)
        onStatus?.invoke("Queued for $toId (pending ${store.getPending().size})")
        return msg.msgId
    }

    /** After a secure session is up, start the relay handshake. */
    fun onConnected(peerShortId: String?, sendText: (String) -> Unit) {
        val pending = store.getPending()
        if (pending.isEmpty()) return
        val ids = pending.map { it.msgId }
        sendText(MeshProtocol.encodeOffer(ids))
        onStatus?.invoke("Offered ${ids.size} pending mesh message(s)")
    }

    /**
     * Handle an incoming plaintext chat line.
     * Returns true if it was a mesh control/payload message (already handled).
     */
    fun onIncomingText(
        text: String,
        peerShortId: String?,
        sendText: (String) -> Unit
    ): Boolean {
        val parsed = MeshProtocol.parse(text) ?: return false

        when (parsed) {
            is MeshProtocol.Parsed.Offer -> {
                // Ask for messages we don't have yet and that are for us or relayable
                val wanted = parsed.msgIds.filter { id ->
                    store.getPending().none { it.msgId == id }
                }
                if (wanted.isNotEmpty()) {
                    sendText(MeshProtocol.encodeRequest(wanted))
                }
            }
            is MeshProtocol.Parsed.Request -> {
                val toSend = store.getPending().filter { it.msgId in parsed.msgIds }
                toSend.forEach { msg ->
                    val next = store.incrementHop(msg) ?: return@forEach
                    sendText(MeshProtocol.encodePayload(next))
                }
            }
            is MeshProtocol.Parsed.Payload -> {
                handlePayload(parsed.msg, peerShortId, sendText)
            }
        }
        return true
    }

    private fun handlePayload(
        msg: MeshStore.MeshMessage,
        peerShortId: String?,
        sendText: (String) -> Unit
    ) {
        // For us?
        if (msg.toId.equals(myShortId, ignoreCase = true)) {
            try {
                val bytes = Base64.decode(msg.payloadB64, Base64.NO_WRAP)
                onDeliveredToMe?.invoke(msg.fromId, bytes)
                store.remove(msg.msgId)
                onStatus?.invoke("Mesh message delivered from ${msg.fromId}")
            } catch (_: Exception) {
                onStatus?.invoke("Bad mesh payload")
            }
            return
        }

        // Relay further
        val next = store.incrementHop(msg)
        if (next != null) {
            store.add(next)
            onStatus?.invoke("Relayed msg (hop ${next.hop}) toward ${msg.toId}")
        }
    }
}
