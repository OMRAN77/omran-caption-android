package com.omran.caption

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import java.util.concurrent.Executors

class VideoPlayerActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var processor: VideoCaptions
    private lateinit var video: VideoView
    private lateinit var caption: TextView
    private lateinit var status: TextView
    private var cues = emptyList<VideoCue>()
    private var ready = false
    private val tick = object : Runnable {
        override fun run() {
            if (ready) {
                val time = video.currentPosition.toLong()
                val cue = CueTimeline.at(cues, time)
                caption.text = cue?.let { CaptionText.format(it.original, it.translated, "") } ?: ""
            }
            handler.postDelayed(this, 80)
        }
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        processor = VideoCaptions(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(12, 12, 12, 12)
        }
        status = TextView(this).apply { setTextColor(Color.WHITE); text = "اختر فيديو لتجهيز الكلام والترجمة قبل التشغيل" }
        root.addView(status)
        val choose = Button(this).apply {
            text = "اختيار فيديو"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "video/*"; addCategory(Intent.CATEGORY_OPENABLE)
                }, 80)
            }
        }
        root.addView(choose)
        video = VideoView(this)
        root.addView(video, LinearLayout.LayoutParams(-1, 0, 1f))
        video.setMediaController(MediaController(this).apply { setAnchorView(video) })
        video.setOnErrorListener { _, _, _ -> status.text = "تعذر تشغيل صيغة هذا الفيديو"; true }
        caption = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 21f; gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
        }
        root.addView(caption, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        handler.post(tick)
        val shared = if (intent.action == Intent.ACTION_SEND)
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) else intent.data
        if (shared != null) prepare(shared, choose)
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 80 && resultCode == RESULT_OK) data?.data?.let { prepare(it, null) }
    }
    private fun prepare(uri: Uri, button: Button?) {
        processor.cancel()
        val job = VideoCaptions(this)
        processor = job
        ready = false
        video.stopPlayback()
        caption.text = ""
        status.text = "جارٍ تجهيز الكلام والترجمة… يُرسل صوت الفيديو إلى خدمة الترجمة"
        button?.isEnabled = false
        val prefs = getSharedPreferences("omran_caption_prefs", MODE_PRIVATE)
        val source = prefs.getString("speak_lang", "auto") ?: "auto"
        val target = prefs.getString("translate_lang", "ar") ?: "ar"
        worker.execute {
            try {
                val result = job.prepare(uri, source, target) { ms ->
                    handler.post { if (processor === job) status.text = "تمت معالجة ${ms / 1000} ثانية من الصوت…" }
                }
                handler.post {
                    if (!isFinishing && processor === job) {
                        cues = result
                        status.text = "جاهز — النص يتبع وقت الفيديو عند الإيقاف والتقديم"
                        video.setOnPreparedListener { ready = true; video.start() }
                        video.setVideoURI(uri)
                        button?.isEnabled = true
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    if (!isFinishing && processor === job) {
                        status.text = e.message ?: "فشلت المعالجة"
                        button?.isEnabled = true
                    }
                }
            }
        }
    }
    override fun onPause() { super.onPause(); video.pause() }
    override fun onDestroy() {
        processor.cancel(); worker.shutdownNow()
        handler.removeCallbacksAndMessages(null); video.stopPlayback()
        super.onDestroy()
    }
}
