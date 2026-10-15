package tech.unyielded.offgrid.call

import android.util.Log

/**
 * Simple call state machine for experimental nearby voice calls.
 */
class CallController(
    private val sendControl: (String) -> Unit,
    private val sendAudio: (ByteArray) -> Unit
) {
    enum class State { IDLE, OUTGOING, INCOMING, IN_CALL }

    var state: State = State.IDLE
        private set

    var onStateChanged: ((State) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null

    private val audio = CallAudioEngine()

    fun startOutgoing() {
        if (state != State.IDLE) return
        state = State.OUTGOING
        onStateChanged?.invoke(state)
        onStatus?.invoke("Calling…")
        sendControl(CallProtocol.offer())
    }

    fun acceptIncoming() {
        if (state != State.INCOMING) return
        state = State.IN_CALL
        onStateChanged?.invoke(state)
        onStatus?.invoke("In call")
        sendControl(CallProtocol.accept())
        beginAudio()
    }

    fun rejectIncoming() {
        if (state != State.INCOMING) return
        sendControl(CallProtocol.reject())
        reset("Call rejected")
    }

    fun endCall() {
        if (state == State.IDLE) return
        sendControl(CallProtocol.end())
        reset("Call ended")
    }

    /** Handle a received chat text that might be call control. Returns true if handled. */
    fun onControlMessage(text: String): Boolean {
        val cmd = CallProtocol.parse(text) ?: return false
        when (cmd) {
            CallProtocol.OFFER -> {
                if (state == State.IDLE) {
                    state = State.INCOMING
                    onStateChanged?.invoke(state)
                    onStatus?.invoke("Incoming call")
                } else {
                    sendControl(CallProtocol.busy())
                }
            }
            CallProtocol.ACCEPT -> {
                if (state == State.OUTGOING) {
                    state = State.IN_CALL
                    onStateChanged?.invoke(state)
                    onStatus?.invoke("In call")
                    beginAudio()
                }
            }
            CallProtocol.REJECT, CallProtocol.BUSY, CallProtocol.END -> {
                reset(when (cmd) {
                    CallProtocol.REJECT -> "Call rejected"
                    CallProtocol.BUSY -> "Busy"
                    else -> "Call ended"
                })
            }
        }
        return true
    }

    fun onAudioReceived(pcm: ByteArray) {
        if (state == State.IN_CALL) {
            audio.playFrame(pcm)
        }
    }

    private fun beginAudio() {
        audio.onAudioFrame = { frame ->
            if (state == State.IN_CALL) {
                try {
                    sendAudio(frame)
                } catch (e: Exception) {
                    Log.e("CallController", "send audio failed", e)
                }
            }
        }
        audio.start()
    }

    private fun reset(reason: String) {
        audio.stop()
        state = State.IDLE
        onStateChanged?.invoke(state)
        onStatus?.invoke(reason)
    }

    fun release() {
        audio.stop()
        state = State.IDLE
    }
}
