package tech.unyielded.offgrid.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Improved PCM engine for nearby Bluetooth calls.
 * 16 kHz mono 16-bit – better clarity than 8 kHz while still fitting RFCOMM.
 */
class CallAudioEngine(private val context: Context) {

    companion object {
        private const val TAG = "CallAudio"
        const val SAMPLE_RATE = 16000
        private val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private val running = AtomicBoolean(false)
    private var recordThread: Thread? = null
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @Volatile var muted: Boolean = false
    @Volatile var speakerOn: Boolean = false
        set(value) {
            field = value
            try {
                audioManager.isSpeakerphoneOn = value
            } catch (_: Exception) {}
        }

    var onAudioFrame: ((ByteArray) -> Unit)? = null

    fun start() {
        if (running.getAndSet(true)) return
        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = speakerOn

            val minRec = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            val recBuf = (minRec * 2).coerceAtLeast(1280)
            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_IN, ENCODING, recBuf
            )

            val minPlay = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
            val playBuf = (minPlay * 2).coerceAtLeast(1280)
            player = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(CHANNEL_OUT)
                            .setEncoding(ENCODING)
                            .build()
                    )
                    .setBufferSizeInBytes(playBuf)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                AudioTrack(
                    AudioManager.STREAM_VOICE_CALL,
                    SAMPLE_RATE, CHANNEL_OUT, ENCODING, playBuf,
                    AudioTrack.MODE_STREAM
                )
            }

            recorder?.startRecording()
            player?.play()

            val frameSize = 640 // 20 ms at 16 kHz mono 16-bit
            recordThread = Thread {
                val buf = ByteArray(frameSize)
                while (running.get()) {
                    val n = try {
                        recorder?.read(buf, 0, buf.size) ?: -1
                    } catch (_: Exception) { -1 }
                    if (n > 0 && !muted) {
                        onAudioFrame?.invoke(buf.copyOf(n))
                    }
                }
            }.also {
                it.name = "OffgridCallRecord"
                it.priority = Thread.MAX_PRIORITY
                it.start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio", e)
            stop()
        }
    }

    fun playFrame(pcm: ByteArray) {
        if (!running.get()) return
        try {
            player?.write(pcm, 0, pcm.size)
        } catch (_: Exception) {}
    }

    fun stop() {
        running.set(false)
        try { recordThread?.join(400) } catch (_: Exception) {}
        recordThread = null
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        try {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
        } catch (_: Exception) {}
        muted = false
        speakerOn = false
    }

    val isRunning: Boolean get() = running.get()
}
