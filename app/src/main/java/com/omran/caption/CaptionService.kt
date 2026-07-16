package com.omran.caption

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import android.app.Service

class CaptionService : Service() {

    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val sampleRate = 16000
    private lateinit var guestId: String

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val speaking = intent?.getStringExtra(EXTRA_SPEAKING) ?: "ar"
        val translate = intent?.getStringExtra(EXTRA_TRANSLATE) ?: "en"

        val prefs = getSharedPreferences("omran_caption_prefs", Context.MODE_PRIVATE)
        guestId = prefs.getString("guest_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("guest_id", it).apply()
        }

        startForeground(NOTIF_ID, buildNotification())
        startCapture(speaking, translate)
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val channelId = "omran_caption_channel"
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(channelId) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(channelId, "Omran Caption", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun startCapture(speaking: String, translate: String) {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBuf > 0) minBuf * 2 else sampleRate * 2

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
        } catch (e: SecurityException) {
            OverlayService.updateText(this, "خطأ بالإذن / Permission error")
            return
        }

        audioRecord?.startRecording()

        job = CoroutineScope(Dispatchers.IO).launch {
            val chunkMillis = 2000L
            val bytesPerChunk = (sampleRate * 2 * chunkMillis / 1000).toInt()
            val readBuf = ByteArray(4096)

            while (isActiveSafe()) {
                val out = ByteArrayOutputStream()
                val startTime = System.currentTimeMillis()
                while (out.size() < bytesPerChunk && System.currentTimeMillis() - startTime < chunkMillis + 300) {
                    val rec = audioRecord ?: break
                    val n = rec.read(readBuf, 0, readBuf.size)
                    if (n > 0) out.write(readBuf, 0, n)
                }
                if (out.size() > 0) {
                    // Fire off the network request on its own coroutine so capture of the
                    // NEXT chunk starts immediately instead of waiting for the HTTP round
                    // trip to finish. This removes the growing delay/lag.
                    val data = out.toByteArray()
                    val seq = ++chunkSeq
                    CoroutineScope(Dispatchers.IO).launch {
                        sendChunk(data, speaking, translate, seq)
                    }
                }
            }
        }
    }

    private fun isActiveSafe(): Boolean = audioRecord != null && job?.isCancelled != true

    private var chunkSeq = 0L
    @Volatile private var lastAppliedSeq = 0L

    private fun sendChunk(pcm: ByteArray, speaking: String, translate: String, seq: Long) {
        try {
            val wav = PcmToWav.wrap(pcm, sampleRate)
            val audioBase64 = Base64.encodeToString(wav, Base64.NO_WRAP)

            val json = JSONObject().apply {
                put("audioBase64", audioBase64)
                put("mimeType", "audio/wav")
                put("sourceLang", speaking)
                put("targetLang", translate)
                put("guestId", guestId)
            }

            val request = Request.Builder()
                .url("$API_BASE/api/caption")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string() ?: return
                val body = JSONObject(text)
                // Chunks run in parallel, so responses can arrive out of order.
                // Only apply this result if it's the newest one seen so far.
                synchronized(this) {
                    if (seq <= lastAppliedSeq) return
                    lastAppliedSeq = seq
                }
                if (!resp.isSuccessful) {
                    val err = body.optString("error", "HTTP ${resp.code}")
                    OverlayService.updateText(applicationContext, "⚠ $err")
                    return
                }
                val serverError = body.optString("translationError", "")
                val translated = body.optString("translated", "")
                when {
                    translated.isNotBlank() -> OverlayService.updateText(applicationContext, translated)
                    serverError.isNotBlank() -> OverlayService.updateText(applicationContext, "⚠ $serverError")
                    // No speech in this chunk (silence) — keep showing the last caption
                    // instead of clearing it.
                    else -> {}
                }
            }
        } catch (e: Exception) {
            OverlayService.updateText(applicationContext, "⚠ ${e.message ?: e.toString()}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        var isRunning = false
        private const val NOTIF_ID = 42
        private const val API_BASE = "https://tarjiman-live.vercel.app"
        private const val EXTRA_SPEAKING = "speaking"
        private const val EXTRA_TRANSLATE = "translate"

        fun start(context: Context, speaking: String, translate: String) {
            val intent = Intent(context, CaptionService::class.java)
                .putExtra(EXTRA_SPEAKING, speaking)
                .putExtra(EXTRA_TRANSLATE, translate)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptionService::class.java))
        }
    }
}
