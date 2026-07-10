package com.omran.caption

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
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

    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var job: Job? = null
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val sampleRate = 16000
    private lateinit var guestId: String
    private var silentStreak = 0

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, -1) ?: -1
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val speaking = intent?.getStringExtra(EXTRA_SPEAKING) ?: "ar"
        val translate = intent?.getStringExtra(EXTRA_TRANSLATE) ?: "en"

        val prefs = getSharedPreferences("omran_caption_prefs", Context.MODE_PRIVATE)
        guestId = prefs.getString("guest_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("guest_id", it).apply()
        }

        startForeground(NOTIF_ID, buildNotification())

        if (data != null) {
            val pm = getSystemService(MediaProjectionManager::class.java)
            mediaProjection = pm.getMediaProjection(resultCode, data)
            startCapture(speaking, translate)
        }
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

    private var projectionStopped = false

    private fun buildAudioRecord(projection: MediaProjection): AudioRecord? {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = if (minBuf > 0) minBuf * 2 else sampleRate * 2

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        return try {
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                rec.release()
                null
            } else {
                rec
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun startCapture(speaking: String, translate: String) {
        val projection = mediaProjection ?: return

        // Detect when the system itself force-stops the capture session
        // (e.g. Huawei/EMUI killing it after some time). We can't silently
        // resume in that case (Android requires a fresh user consent), so we
        // just tell the user clearly instead of leaving a broken silent state.
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                projectionStopped = true
                OverlayService.updateText(
                    applicationContext,
                    "⚠ انقطع الاتصال بالنظام، افتح التطبيق وابدأ من جديد / Connection lost, please reopen the app"
                )
            }
        }, null)

        audioRecord = buildAudioRecord(projection)
        if (audioRecord == null) {
            OverlayService.updateText(this, "خطأ بالإذن / Permission error")
            return
        }
        audioRecord?.startRecording()

        job = CoroutineScope(Dispatchers.IO).launch {
            val chunkMillis = 4500L
            val bytesPerChunk = (sampleRate * 2 * chunkMillis / 1000).toInt()
            val readBuf = ByteArray(4096)
            var consecutiveBadReads = 0
            var reconnectAttempts = 0

            while (isActiveSafe()) {
                if (projectionStopped) break

                val out = ByteArrayOutputStream()
                val startTime = System.currentTimeMillis()
                var badReadThisChunk = false

                while (out.size() < bytesPerChunk && System.currentTimeMillis() - startTime < chunkMillis + 500) {
                    val rec = audioRecord
                    if (rec == null || rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        badReadThisChunk = true
                        break
                    }
                    val n = rec.read(readBuf, 0, readBuf.size)
                    if (n > 0) {
                        out.write(readBuf, 0, n)
                        consecutiveBadReads = 0
                    } else if (n < 0) {
                        // Negative return = AudioRecord error (e.g. ERROR_DEAD_OBJECT),
                        // which is exactly the kind of glitch some OEMs (Huawei) cause.
                        badReadThisChunk = true
                        break
                    }
                }

                if (badReadThisChunk) {
                    consecutiveBadReads++
                    if (consecutiveBadReads >= 2 && !projectionStopped) {
                        reconnectAttempts++
                        if (reconnectAttempts >= 2) {
                            OverlayService.updateText(applicationContext, "🔄 إعادة الاتصال... / Reconnecting...")
                        }
                        // Try to rebuild the AudioRecord from the same still-valid
                        // MediaProjection, without asking the user for anything.
                        audioRecord?.let { old ->
                            try { old.stop() } catch (_: Exception) {}
                            try { old.release() } catch (_: Exception) {}
                        }
                        audioRecord = null
                        kotlinx.coroutines.delay(1200)
                        if (!projectionStopped) {
                            val fresh = buildAudioRecord(projection)
                            if (fresh != null) {
                                fresh.startRecording()
                                audioRecord = fresh
                                consecutiveBadReads = 0
                                if (reconnectAttempts >= 2) {
                                    OverlayService.updateText(applicationContext, "")
                                }
                                reconnectAttempts = 0
                            }
                        }
                    }
                    continue
                }

                if (out.size() > 0) {
                    sendChunk(out.toByteArray(), speaking, translate)
                }
            }
        }
    }

    private fun isActiveSafe(): Boolean = job?.isCancelled != true

    private fun sendChunk(pcm: ByteArray, speaking: String, translate: String) {
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
                if (!resp.isSuccessful) {
                    val err = body.optString("error", "HTTP ${resp.code}")
                    OverlayService.updateText(applicationContext, "⚠ $err")
                    return
                }
                val serverError = body.optString("translationError", "")
                val translated = body.optString("translated", "")
                when {
                    translated.isNotBlank() -> {
                        silentStreak = 0
                        OverlayService.updateText(applicationContext, translated)
                    }
                    serverError.isNotBlank() -> OverlayService.updateText(applicationContext, "⚠ $serverError")
                    else -> {
                        // Empty original/translated with no error means silence in this
                        // chunk. A single silent chunk can just be a natural pause between
                        // words/sentences, so don't clear immediately — only clear after
                        // 2 consecutive silent chunks (~9s of real silence).
                        silentStreak++
                        if (silentStreak >= 2) {
                            OverlayService.updateText(applicationContext, "")
                        }
                    }
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
        mediaProjection?.stop()
        mediaProjection = null
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        var isRunning = false
        private const val NOTIF_ID = 42
        private const val API_BASE = "https://tarjiman-live.vercel.app"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_SPEAKING = "speaking"
        private const val EXTRA_TRANSLATE = "translate"

        fun start(context: Context, resultCode: Int, data: Intent, speaking: String, translate: String) {
            val intent = Intent(context, CaptionService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
                .putExtra(EXTRA_SPEAKING, speaking)
                .putExtra(EXTRA_TRANSLATE, translate)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CaptionService::class.java))
        }
    }
}
