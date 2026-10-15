package tech.unyielded.offgrid.call

import android.content.Context
import android.os.SystemClock
import android.util.Log

class CallController(
    context: Context,
    private val sendControl: (String) -> Unit,
    private val sendAudio: (ByteArray) -> Unit
) {
    enum class State { IDLE, OUTGOING, INCOMING, IN_CALL }

    var state: State = State.IDLE
        private set

    var onStateChanged: ((State) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onDurationTick: ((Long) -> Unit)? = null  // seconds

    private val audio = CallAudioEngine(context)
    private var callStartedAt: Long = 0L
    private var durationThread: Thread? = null

    val isMuted: Boolean get() = audio.muted
    val isSpeakerOn: Boolean get() = audio.speakerOn

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

    fun toggleMute() {
        audio.muted = !audio.muted
        onStatus?.invoke(if (audio.muted) "Muted" else "Unmuted")
    }

    fun toggleSpeaker() {
        audio.speakerOn = !audio.speakerOn
        onStatus?.invoke(if (audio.speakerOn) "Speaker on" else "Earpiece")
    }

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
                reset(
                    when (cmd) {
                        CallProtocol.REJECT -> "Call rejected"
                        CallProtocol.BUSY -> "Busy"
                        else -> "Call ended"
                    }
                )
            }
        }
        return true
    }

    fun onAudioReceived(pcm: ByteArray) {
        if (state == State.IN_CALL) audio.playFrame(pcm)
    }

    private fun beginAudio() {
        callStartedAt = SystemClock.elapsedRealtime()
        audio.onAudioFrame = { frame ->
            if (state == State.IN_CALL) {
                try { sendAudio(frame) } catch (e: Exception) {
                    Log.e("CallController", "send audio failed", e)
                }
            }
        }
        audio.start()
        durationThread = Thread {
            while (state == State.IN_CALL) {
                val sec = (SystemClock.elapsedRealtime() - callStartedAt) / 1000L
                onDurationTick?.invoke(sec)
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }.also { it.name = "CallDuration"; it.start() }
    }

    private fun reset(reason: String) {
        audio.stop()
        durationThread?.interrupt()
        durationThread = null
        state = State.IDLE
        onStateChanged?.invoke(state)
        onStatus?.invoke(reason)
        onDurationTick?.invoke(0)
    }

    fun release() {
        audio.stop()
        durationThread?.interrupt()
        state = State.IDLE
    }
}
