package com.omran.caption

import android.content.Context
import android.media.*
import android.net.Uri
import android.util.Base64
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Decodes the selected video, never the microphone. Playback starts only after preparation. */
class VideoCaptions(private val context: Context) {
    @Volatile var cancelled = false
    private val client = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()
    fun cancel() { cancelled = true; client.dispatcher.cancelAll() }

    fun prepare(uri: Uri, source: String, target: String, progress: (Long) -> Unit): List<VideoCue> {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val cues = mutableListOf<VideoCue>()
        val history = mutableListOf<String>()
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("الفيديو لا يحتوي على مسار صوت يمكن قراءته")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            decoder = codec
            codec.configure(format, null, null, 0)
            codec.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var inputEnd = false
            var outputEnd = false
            var startMs = -1L
            var endMs = 0L
            val pcm = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            fun flush() {
                if (pcm.size() == 0) return
                check(!cancelled) { "أُلغيت المعالجة" }
                val bytes = pcm.toByteArray()
                if (CaptionText.hasSound(bytes)) {
                    val payload = JSONObject()
                        .put("audioBase64", Base64.encodeToString(PcmToWav.wrap(bytes, rate), Base64.NO_WRAP))
                        .put("mimeType", "audio/wav").put("sourceLang", source).put("targetLang", target)
                        .put("twoWay", false).put("context", JSONArray(history.takeLast(2)))
                    val request = Request.Builder().url("https://omran-caption.vercel.app/api/caption")
                        .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
                    client.newCall(request).execute().use { response ->
                        check(response.isSuccessful) { "فشلت معالجة الصوت (${response.code})؛ أعد المحاولة" }
                        val body = JSONObject(response.body?.string() ?: "{}")
                        check(body.optString("translationError").isBlank()) { "تعذرت ترجمة المقطع؛ لم يتم تجهيز الفيديو" }
                        val original = body.optString("original").trim()
                        val translated = body.optString("translated").trim()
                        if (original.isNotBlank()) {
                            // Anchor to decoded audio presentation times, not HTTP response arrival.
                            val segments = body.optJSONObject("debug")?.optJSONArray("segments")
                            val kept = (0 until (segments?.length() ?: 0)).mapNotNull {
                                segments?.optJSONObject(it)?.takeIf { s -> s.optBoolean("kept", false) }
                            }
                            val from = kept.minOfOrNull { it.optDouble("start", 0.0) } ?: 0.0
                            val to = kept.maxOfOrNull { it.optDouble("end", (endMs - startMs) / 1000.0) }
                                ?: (endMs - startMs) / 1000.0
                            val begin = (startMs + (from * 1000).toLong()).coerceIn(startMs, endMs)
                            val finish = (startMs + (to * 1000).toLong()).coerceIn(begin, endMs)
                            if (finish > begin) cues.add(VideoCue(begin, finish, original, translated))
                            history.add(original)
                        }
                    }
                }
                progress(endMs)
                pcm.reset()
                startMs = -1
            }
            while (!outputEnd && !cancelled) {
                if (!inputEnd) {
                    val index = codec.dequeueInputBuffer(10000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnd = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(pcm.size() == 0) { "تغير تنسيق الصوت أثناء الفيديو؛ هذا الملف غير مدعوم حاليًا" }
                    rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (codec.outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
                        codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    check(encoding == AudioFormat.ENCODING_PCM_16BIT) { "صيغة الصوت غير مدعومة حاليًا" }
                } else if (index >= 0) {
                    if (info.size > 0) {
                        if (startMs < 0) startMs = info.presentationTimeUs / 1000
                        val buffer = codec.getOutputBuffer(index)!!.duplicate().order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        var peak = 0
                        val frames = info.size / (2 * channels)
                        repeat(frames) {
                            var sum = 0
                            repeat(channels) { sum += buffer.short.toInt() }
                            val sample = sum / channels
                            peak = maxOf(peak, kotlin.math.abs(sample))
                            pcm.write(sample and 255)
                            pcm.write((sample shr 8) and 255)
                        }
                        endMs = info.presentationTimeUs / 1000 + frames * 1000L / rate
                        val duration = pcm.size() / (2.0 * rate)
                        // Prefer a quiet boundary after three seconds; cap requests at eight seconds.
                        if ((duration >= 3 && peak < 180) || duration >= 8) flush()
                    }
                    outputEnd = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            check(!cancelled) { "أُلغيت المعالجة" }
            flush()
            check(cues.isNotEmpty()) { "لم يتم التعرف على كلام؛ اختر لغة الكلام الصحيحة وأعد المحاولة" }
            return cues
        } finally {
            try { decoder?.stop() } catch (_: Exception) {}
            decoder?.release()
            extractor.release()
        }
    }
}
