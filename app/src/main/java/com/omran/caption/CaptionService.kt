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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.util.concurrent.Semaphore
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
    private val networkScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestSlots = Semaphore(2)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    private val sampleRate = 16000
    private lateinit var guestId: String

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (PrivateAccess.get().isEmpty()) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) return START_NOT_STICKY
        val speaking = intent?.getStringExtra(EXTRA_SPEAKING) ?: "ar"
        val translate = intent?.getStringExtra(EXTRA_TRANSLATE) ?: "en"

        val prefs = getSharedPreferences("omran_caption_prefs", Context.MODE_PRIVATE)
        guestId = prefs.getString("guest_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("guest_id", it).apply()
        }

        isRunning = true
        startCapture(speaking, translate)
        return START_NOT_STICKY
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
            val chunkMillis = 1200L
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
                if (out.size() > 0 && requestSlots.tryAcquire()) {
                    // Fire off the network request on its own coroutine so capture of the
                    // NEXT chunk starts immediately instead of waiting for the HTTP round
                    // trip to finish. This removes the growing delay/lag.
                    val data = out.toByteArray()
                    val seq = ++chunkSeq
                    networkScope.launch {
                        try {
                            if (isRunning) sendChunk(data, speaking, translate, seq)
                        } finally {
                            requestSlots.release()
                        }
                    }
                }
            }
        }
    }

    private fun isActiveSafe(): Boolean = audioRecord != null && job?.isCancelled != true

    private var chunkSeq = 0L
    @Volatile private var lastAppliedSeq = 0L
    @Volatile private var silentChunkCount = 0
    private val SILENT_CHUNKS_BEFORE_CLEAR = 3

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
                .header("Authorization", "Bearer ${PrivateAccess.get()}")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { resp ->
                if (!isRunning) return
                if (resp.code == 401 || resp.code == 503) {
                    if (resp.code == 401) PrivateAccess.clear()
                    OverlayService.updateText(applicationContext,
                        if (resp.code == 401) "⚠ افتح التطبيق وأدخل رمز الوصول الصحيح"
                        else "⚠ حماية الخادم غير مهيأة، راجع إعداد رمز الوصول")
                    isRunning = false
                    stopSelf()
                    return
                }
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
                    serverError.isNotBlank() -> {
                        silentChunkCount = 0
                        OverlayService.updateText(applicationContext, "⚠ $serverError")
                    }
                    translated.isNotBlank() -> {
                        silentChunkCount = 0
                        OverlayService.updateText(applicationContext, translated)
                    }
                    else -> {
                        // No speech in this chunk (silence). Keep showing the last caption
                        // briefly, but clear it after a few consecutive silent chunks so it
                        // doesn't stay frozen on screen once talking has stopped.
                        val count = ++silentChunkCount
                        if (count >= SILENT_CHUNKS_BEFORE_CLEAR) {
                            OverlayService.updateText(applicationContext, "")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (isRunning) OverlayService.updateText(applicationContext, "⚠ تعذر الاتصال بخدمة الترجمة")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        job?.cancel()
        networkScope.cancel()
        client.dispatcher.cancelAll()
        try { audioRecord?.stop() } catch (_: IllegalStateException) {}
        audioRecord?.release()
        audioRecord = null
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile var isRunning = false
        private const val NOTIF_ID = 42
        private const val API_BASE = "https://omran-caption.vercel.app"
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
