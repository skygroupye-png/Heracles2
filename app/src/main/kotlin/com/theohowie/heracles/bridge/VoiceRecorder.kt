package com.theohowie.heracles.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

class VoiceRecorder(private val context: Context) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_BYTES = 3200
    }

    sealed interface VoiceEvent {
        data class Chunk(val sessionId: String, val seq: Int, val rmsLevel: Float) : VoiceEvent
        data class Ended(val sessionId: String, val seq: Int) : VoiceEvent
    }

    data class StartResult(
        val ok: Boolean,
        val sessionId: String? = null,
        val error: String? = null,
    )

    data class StopResult(
        val ok: Boolean,
        val wavPath: String? = null,
        val fileName: String? = null,
        val size: Long = 0L,
        val durationMs: Long = 0L,
        val error: String? = null,
    )

    private var recorder: AudioRecord? = null
    private var worker: Thread? = null

    @Volatile
    private var capturing = false
    private var pcmFile: File? = null
    private var startedAtMs = 0L

    @Volatile
    private var generation = 0L
    private var sessionId: String? = null
    private var seq = 0

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    val isRecording: Boolean get() = capturing

    fun start(): StartResult {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return StartResult(ok = false, error = "لم يتم منح إذن الميكروفون")
        }
        stopInternal(discard = true)
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            return StartResult(ok = false, error = "هذا الجهاز لا يدعم التسجيل الأحادي بمعدل 16kHz")
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, CHUNK_BYTES * 2),
            )
        } catch (e: Exception) {
            return StartResult(ok = false, error = "تعذر فتح الميكروفون: ${e.message ?: e.javaClass.simpleName}")
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec.release() }
            return StartResult(ok = false, error = "فشلت تهيئة الميكروفون")
        }
        val dir = File(context.cacheDir, "voice").apply { mkdirs() }
        val pcm = File(dir, "rec-${System.currentTimeMillis()}.pcm")
        val gen = ++generation
        val session = "v${System.currentTimeMillis()}-$gen"
        sessionId = session
        seq = 0
        recorder = rec
        pcmFile = pcm
        capturing = true
        startedAtMs = System.currentTimeMillis()
        rec.startRecording()
        worker = thread(name = "heracles-voice-recorder") {
            val out = FileOutputStream(pcm)
            val buffer = ByteArray(CHUNK_BYTES)
            try {
                while (capturing) {
                    val n = rec.read(buffer, 0, buffer.size)
                    if (n > 0) {
                        out.write(buffer, 0, n)
                        if (gen == generation) {
                            _events.tryEmit(VoiceEvent.Chunk(session, seq++, computeRms(buffer, n)))
                        }
                    } else if (n < 0) {
                        break
                    }
                }
            } catch (_: Exception) {
            } finally {
                runCatching { out.flush() }
                runCatching { out.close() }
            }
        }
        return StartResult(ok = true, sessionId = session)
    }

    fun stop(): StopResult {
        val pcm = pcmFile
        if (!capturing || pcm == null) {
            return StopResult(ok = false, error = "لا يوجد تسجيل قيد التشغيل حالياً")
        }
        val session = sessionId.orEmpty()
        stopInternal(discard = false)
        _events.tryEmit(VoiceEvent.Ended(session, seq))
        sessionId = null
        val durationMs = System.currentTimeMillis() - startedAtMs
        if (!pcm.exists() || pcm.length() < 3200) {
            runCatching { pcm.delete() }
            return StopResult(ok = false, error = "التسجيل قصير جداً، لم يتم التقاط صوت")
        }
        val wav = File(pcm.parentFile, pcm.nameWithoutExtension + ".wav")
        writeWav(pcm, wav)
        runCatching { pcm.delete() }
        return StopResult(
            ok = true,
            wavPath = wav.absolutePath,
            fileName = wav.name,
            size = wav.length(),
            durationMs = durationMs,
        )
    }

    fun cancel() {
        generation++
        sessionId = null
        stopInternal(discard = true)
    }

    private fun computeRms(buf: ByteArray, len: Int): Float {
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < len) {
            val sample = ((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xFF)).toShort()
            sum += sample * sample
            samples++
            i += 2
        }
        if (samples == 0) return 0f
        return (sqrt(sum / samples) / 32768.0).toFloat().coerceIn(0f, 1f)
    }

    private fun stopInternal(discard: Boolean) {
        if (!capturing && recorder == null) return
        capturing = false
        runCatching { recorder?.stop() }
        runCatching { worker?.join(800) }
        runCatching { recorder?.release() }
        recorder = null
        worker = null
        if (discard) {
            runCatching { pcmFile?.delete() }
            pcmFile = null
        }
    }

    private fun writeWav(pcm: File, wav: File) {
        val pcmBytes = pcm.readBytes()
        val dataLen = pcmBytes.size
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataLen)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(1)
        header.putInt(SAMPLE_RATE)
        header.putInt(SAMPLE_RATE * 2)
        header.putShort(2)
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataLen)
        RandomAccessFile(wav, "rw").use { raf ->
            raf.setLength(0)
            raf.write(header.array())
            raf.write(pcmBytes)
        }
    }
}
