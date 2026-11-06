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
import org.json.JSONArray
import org.json.JSONObject
import android.util.Base64
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

    private val configClient = client.newBuilder().callTimeout(5, TimeUnit.SECONDS).build()

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
            // As in the first version of the app, whose captions kept up with the
            // speech: a short slice about every 1.2 s, each sent at once on its own
            // coroutine without waiting for the previous reply; the newest reply
            // is shown. ChunkSlicer ends each slice in the gap between two words.
            val slicer = ChunkSlicer(sampleRate)
            // Tuning comes from the server (api/config.js), so it can change
            // without a new APK: start with the last values received, then
            // apply fresh ones as soon as they arrive (capture does not wait).
            val prefs = getSharedPreferences("omran_caption_prefs", Context.MODE_PRIVATE)
            settings = RemoteSettings.fromJson(prefs.getString(KEY_SETTINGS, null))
            applySettings(slicer)
            CoroutineScope(Dispatchers.IO).launch { fetchSettings(slicer) }
            val frame = ByteArray(sampleRate * 2 * 50 / 1000) // 50 ms
            // Speech detector (Silero VAD on the phone): slices with only silence,
            // room noise or music are not sent, since Whisper invents text for
            // them ("شكراً لك" on the home screen, lines for instrumental music).
            val detector = createSpeechDetector()
            var lastHadSpeech = false

            try {
                while (isActiveSafe()) {
                    val rec = audioRecord ?: break
                    val n = rec.read(frame, 0, frame.size)
                    if (n <= 0) {
                        Thread.sleep(10)
                        continue
                    }
                    detector.feed(frame, n)
                    val slice = slicer.feed(frame, n) ?: continue
                    val s = settings
                    val speechMs = if (s.vadEnabled) detector.speechMs(slice.startSample, slice.endSample) else Int.MAX_VALUE
                    // Right after a slice with speech, any speech is enough (the end of a word).
                    val hasSpeech = !slice.silent && (speechMs >= s.minSpeechMs || (lastHadSpeech && speechMs > 0))
                    lastHadSpeech = hasSpeech
                    if (!hasSpeech) {
                        // Not sent, but counts towards clearing the caption.
                        noteEmpty()
                        continue
                    }
                    val seq = ++chunkSeq
                    CoroutineScope(Dispatchers.IO).launch {
                        sendChunk(slice.pcm, speaking, translate, seq)
                    }
                }
            } finally {
                detector.close()
            }
        }
    }

    private fun isActiveSafe(): Boolean = audioRecord != null && job?.isCancelled != true

    @Volatile private var settings = RemoteSettings()

    /** Silero VAD from the bundled model, or the simple loudness check if it cannot run here. */
    private fun createSpeechDetector(): SpeechDetector {
        val model = try { assets.open("silero_vad.onnx").use { it.readBytes() } } catch (e: Exception) { null }
        return model?.let { m -> SileroVad.create(m) { settings.vadThreshold.toFloat() } } ?: EnergyGate()
    }

    private fun applySettings(slicer: ChunkSlicer) {
        val s = settings
        slicer.configure(s.sliceMinMs, s.sliceMaxMs, s.silenceRms, s.quietRatio)
    }

    private fun fetchSettings(slicer: ChunkSlicer) {
        try {
            val request = Request.Builder().url("$API_BASE/api/config").build()
            configClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                val text = resp.body?.string() ?: return
                settings = RemoteSettings.fromJson(text)
                applySettings(slicer)
                getSharedPreferences("omran_caption_prefs", Context.MODE_PRIVATE)
                    .edit().putString(KEY_SETTINGS, text).apply()
            }
        } catch (e: Exception) {
            // Offline or server down: keep the last values (or the defaults).
        }
    }

    private var chunkSeq = 0L
    @Volatile private var lastAppliedSeq = 0L
    // Slices in a row with no words (silence, music only): after
    // settings.emptyBeforeClear (3, about 4 s) the caption is cleared instead
    // of staying frozen on the last words.
    private var emptyStreak = 0 // guarded by captionLock
    // Last lines heard, sent as context so the translation of each short slice
    // follows the sentence it belongs to.
    private val recentOriginals = ArrayDeque<String>()

    // The capture loop and the reply coroutines both touch the caption: one
    // lock keeps "reset the count + show" and "count + clear" from interleaving.
    private val captionLock = Any()

    private fun showCaption(text: String, isError: Boolean = false) = synchronized(captionLock) {
        // A warning does not hold the screen: the next empty slices clear it.
        if (!isError) emptyStreak = 0
        OverlayService.updateText(applicationContext, text)
    }

    private fun noteEmpty() = synchronized(captionLock) {
        // ">=" as in the first version: a warning shown after the count passed 3
        // is cleared by the next empty slice.
        val limit = settings.emptyBeforeClear
        if (++emptyStreak >= limit) {
            emptyStreak = limit
            OverlayService.updateText(applicationContext, "")
        }
    }

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
                put("context", JSONArray(synchronized(recentOriginals) { recentOriginals.toList() }))
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
                    showCaption("⚠ $err", isError = true)
                    return
                }
                val original = body.optString("original", "")
                if (original.isNotBlank()) synchronized(recentOriginals) {
                    recentOriginals.addLast(original)
                    while (recentOriginals.size > settings.contextLines) recentOriginals.removeFirst()
                }
                val serverError = body.optString("translationError", "")
                val translated = body.optString("translated", "")
                when {
                    translated.isNotBlank() -> showCaption(translated)
                    serverError.isNotBlank() -> showCaption("⚠ $serverError", isError = true)
                    else -> noteEmpty() // no words in this slice (music, noise)
                }
            }
        } catch (e: Exception) {
            // A failed old request must not cover a newer caption.
            synchronized(this) { if (seq < lastAppliedSeq) return }
            showCaption("⚠ ${e.message ?: e.toString()}", isError = true)
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
        private const val KEY_SETTINGS = "remote_settings"
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
