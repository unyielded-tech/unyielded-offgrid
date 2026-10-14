package tech.unyielded.offgrid.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null
    private var player: MediaPlayer? = null

    val isRecording: Boolean
        get() = recorder != null

    fun startRecording(): Boolean {
        if (recorder != null) return false
        val file = File(context.cacheDir, "voice_${UUID.randomUUID()}.m4a")
        currentFile = file

        return try {
            val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(32000)
            r.setAudioSamplingRate(16000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            true
        } catch (e: Exception) {
            file.delete()
            currentFile = null
            false
        }
    }

    fun stopRecording(): ByteArray? {
        val r = recorder ?: return null
        val file = currentFile
        return try {
            r.stop()
            r.release()
            recorder = null
            val bytes = file?.readBytes()
            file?.delete()
            currentFile = null
            bytes
        } catch (e: Exception) {
            try { r.release() } catch (_: Exception) {}
            recorder = null
            file?.delete()
            currentFile = null
            null
        }
    }

    fun cancelRecording() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        currentFile?.delete()
        currentFile = null
    }

    fun play(bytes: ByteArray, onComplete: (() -> Unit)? = null) {
        stopPlayback()
        try {
            val tmp = File(context.cacheDir, "play_${UUID.randomUUID()}.m4a")
            tmp.writeBytes(bytes)
            val p = MediaPlayer()
            p.setDataSource(tmp.absolutePath)
            p.setOnCompletionListener {
                tmp.delete()
                player = null
                onComplete?.invoke()
            }
            p.setOnErrorListener { _, _, _ ->
                tmp.delete()
                player = null
                true
            }
            p.prepare()
            p.start()
            player = p
        } catch (_: Exception) {}
    }

    fun stopPlayback() {
        try {
            player?.stop()
            player?.release()
        } catch (_: Exception) {}
        player = null
    }

    fun release() {
        cancelRecording()
        stopPlayback()
    }
}
