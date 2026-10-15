package tech.unyielded.offgrid.call

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal PCM audio engine for nearby Bluetooth calls.
 * 8 kHz mono 16-bit – low bandwidth so RFCOMM can keep up.
 */
class CallAudioEngine {

    companion object {
        private const val TAG = "CallAudio"
        const val SAMPLE_RATE = 8000
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        val FRAME_BYTES: Int = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            .coerceAtLeast(320)
    }

    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private val running = AtomicBoolean(false)
    private var recordThread: Thread? = null

    var onAudioFrame: ((ByteArray) -> Unit)? = null

    fun start() {
        if (running.getAndSet(true)) return
        try {
            val minRec = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_IN, ENCODING, minRec * 2
            )
            val minPlay = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
            player = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .setEncoding(ENCODING)
                        .build()
                )
                .setBufferSizeInBytes(minPlay * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            recorder?.startRecording()
            player?.play()

            recordThread = Thread {
                val buf = ByteArray(FRAME_BYTES)
                while (running.get()) {
                    val n = try {
                        recorder?.read(buf, 0, buf.size) ?: -1
                    } catch (_: Exception) { -1 }
                    if (n > 0) {
                        onAudioFrame?.invoke(buf.copyOf(n))
                    }
                }
            }.also { it.name = "OffgridCallRecord"; it.start() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio", e)
            stop()
        }
    }

    fun playFrame(pcm: ByteArray) {
        try {
            player?.write(pcm, 0, pcm.size)
        } catch (_: Exception) {}
    }

    fun stop() {
        running.set(false)
        try { recordThread?.join(500) } catch (_: Exception) {}
        recordThread = null
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    val isRunning: Boolean get() = running.get()
}
