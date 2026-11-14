package com.omran.caption

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class CaptionService : Service() {
    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val slots = Semaphore(2)
    private val main = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    private val sampleRate = 16000
    private var sequence = 0L
    private var applied = 0L
    private var silentSince = 0L
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() { stopSelf() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            OverlayService.hide(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (recorder != null) return START_NOT_STICKY
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("captions", "Omran Caption", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0,
            Intent(this, CaptionService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, "captions")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Omran Caption")
            .setContentText("ترجمة صوت الفيديو — الكلام الأصلي والترجمة")
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stop)
            .setOngoing(true).build()
        startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val data = intent?.getParcelableExtra<Intent>("captureData")
        val code = intent?.getIntExtra("captureCode", Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        if (data == null || code != Activity.RESULT_OK) { stopSelf(); return START_NOT_STICKY }
        val source = intent?.getStringExtra("speaking") ?: "auto"
        val target = intent?.getStringExtra("translate") ?: "ar"
        try {
            val mp = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data)
            projection = mp
            mp.registerCallback(projectionCallback, main)
            val capture = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
            val format = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val minimum = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audio = AudioRecord.Builder().setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(minimum * 4, 64000))
                .setAudioPlaybackCaptureConfig(capture).build()
            recorder = audio
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            audio.startRecording()
            isRunning = true
            OverlayService.updateText(this, "شغّل الفيديو الآن…\nسيظهر الكلام الأصلي والترجمة هنا")
            scope.launch { captureLoop(audio, source, target) }
        } catch (_: Exception) {
            OverlayService.updateText(this, "⚠ تعذر بدء التقاط صوت الفيديو. أعد السماح بالتقاط الشاشة والصوت.")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private suspend fun captureLoop(audio: AudioRecord, source: String, target: String) {
        val buffer = ByteArray(3200)
        try {
            while (currentCoroutineContext().isActive && isRunning) {
                val chunk = ByteArrayOutputStream()
                while (chunk.size() < 38400 && isRunning) {
                    val count = audio.read(buffer, 0, buffer.size())
                    if (count < 0) error("Audio capture stopped")
                    if (count == 0) { delay(10); continue }
                    chunk.write(buffer, 0, count)
                }
                if (!isRunning) break
                val pcm = chunk.toByteArray()
                if (!CaptionText.hasSound(pcm)) {
                    if (silentSince == 0L) silentSince = System.currentTimeMillis()
                    if (System.currentTimeMillis() - silentSince > 8000) {
                        val seq = ++sequence
                        show(seq, "لا يصل صوت من الفيديو.\nشغّله وارفع صوته؛ بعض التطبيقات تمنع التقاط الصوت.")
                        silentSince = System.currentTimeMillis()
                    }
                    continue
                }
                silentSince = 0
                val seq = ++sequence
                if (slots.tryAcquire()) scope.launch {
                    try { send(pcm, source, target, seq) } finally { slots.release() }
                } else {
                    show(seq, "⚠ خدمة الترجمة بطيئة حاليًا؛ بعض المقاطع لم تُرسل")
                }
            }
        } catch (_: Exception) {
            if (isRunning) show(++sequence, "⚠ انقطع التقاط الصوت. أوقف الترجمة وابدأها من جديد.")
        }
    }

    private fun send(pcm: ByteArray, source: String, target: String, seq: Long) {
        try {
            val payload = JSONObject().put("audioBase64", Base64.encodeToString(PcmToWav.wrap(pcm, sampleRate), Base64.NO_WRAP))
                .put("mimeType", "audio/wav").put("sourceLang", source)
                .put("targetLang", target).put("twoWay", false)
            val request = Request.Builder().url("https://omran-caption.vercel.app/api/caption")
                .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                val body = JSONObject(response.body?.string() ?: "{}")
                if (!response.isSuccessful) {
                    show(seq, "⚠ " + body.optString("error", "HTTP ${response.code}"))
                    return
                }
                val text = CaptionText.format(body.optString("original"), body.optString("translated"), body.optString("translationError"))
                if (text.isNotBlank()) show(seq, text)
            }
        } catch (_: Exception) {
            show(seq, "⚠ تعذر الاتصال بخدمة الترجمة. تحقق من الإنترنت.")
        }
    }

    private fun show(seq: Long, text: String) {
        main.post {
            if (isRunning && seq > applied) {
                applied = seq
                OverlayService.updateText(this, text)
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        client.dispatcher.cancelAll()
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        recorder = null
        projection?.unregisterCallback(projectionCallback)
        projection?.stop()
        projection = null
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        @Volatile var isRunning = false
        fun start(context: Context, speaking: String, translate: String, code: Int, data: Intent) {
            context.startForegroundService(Intent(context, CaptionService::class.java)
                .putExtra("speaking", speaking).putExtra("translate", translate)
                .putExtra("captureCode", code).putExtra("captureData", data))
        }
        fun stop(context: Context) { context.stopService(Intent(context, CaptionService::class.java)) }
    }
}
