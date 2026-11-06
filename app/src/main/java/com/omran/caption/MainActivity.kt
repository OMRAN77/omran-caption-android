package com.omran.caption

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import com.omran.caption.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val languages = listOf(
        "العربية" to "ar", "English" to "en", "Español" to "es", "Français" to "fr",
        "Deutsch" to "de", "Türkçe" to "tr", "Русский" to "ru", "中文" to "zh",
        "日本語" to "ja", "한국어" to "ko", "Português" to "pt", "Italiano" to "it",
        "हिन्दी" to "hi", "اردو" to "ur", "Bahasa Indonesia" to "id"
    )

    // لغة التحدث: "تلقائي" يكتشف لغة الكلام (فيديو صيني، تركي، ...). بدونها كان
    // اختيار العربية يجبر Whisper على العربية فيخرج كلامًا خاطئًا من الصوت الأجنبي.
    private val speakingLanguages = listOf("تلقائي (أي لغة)" to "auto") + languages

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appVersion.text = "الإصدار " + (packageManager.getPackageInfo(packageName, 0).versionName ?: "")

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val speakNames = speakingLanguages.map { it.first }
        val names = languages.map { it.first }
        val speakAdapter = ArrayAdapter(this, R.layout.spinner_item, speakNames)
        speakAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        val adapter = ArrayAdapter(this, R.layout.spinner_item, names)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinnerSpeaking.adapter = speakAdapter
        binding.spinnerTranslate.adapter = adapter
        // Defaults: detect the spoken language, captions in Arabic; then remember the choice.
        val savedSpeak = prefs.getString(KEY_SPEAK, "auto")
        val savedTranslate = prefs.getString(KEY_TRANSLATE, "ar")
        binding.spinnerSpeaking.setSelection(speakingLanguages.indexOfFirst { it.second == savedSpeak }.coerceAtLeast(0))
        binding.spinnerTranslate.setSelection(languages.indexOfFirst { it.second == savedTranslate }.coerceAtLeast(0))

        binding.spinnerSpeaking.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString(KEY_SPEAK, speakingLanguages[position].second).apply()
                Toast.makeText(this@MainActivity, "لغة التحدث: ${speakNames[position]}", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        binding.spinnerTranslate.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                prefs.edit().putString(KEY_TRANSLATE, languages[position].second).apply()
                Toast.makeText(this@MainActivity, "لغة الترجمة: ${names[position]}", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        updateButtonState()

        binding.btnStart.setOnClickListener {
            if (CaptionService.isRunning) {
                stopCaptioning()
            } else {
                requestPermissionsAndStart()
            }
        }
    }

    private fun updateButtonState() {
        binding.btnStart.text = if (CaptionService.isRunning)
            getString(R.string.stop_caption) else getString(R.string.start_caption)
    }

    private fun requestPermissionsAndStart() {
        // 1) Mic permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        // 2) Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        // 3) Overlay permission
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, REQ_OVERLAY)
            return
        }
        // 4) Start capturing via microphone (works with YouTube & all apps)
        startCaptioningNow()
    }

    private fun startCaptioningNow() {
        val speakingCode = speakingLanguages[binding.spinnerSpeaking.selectedItemPosition].second
        val translateCode = languages[binding.spinnerTranslate.selectedItemPosition].second
        CaptionService.start(this, speakingCode, translateCode)
        OverlayService.show(this)
        CaptionService.isRunning = true
        updateButtonState()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            requestPermissionsAndStart()
        } else {
            Toast.makeText(this, "الإذن مطلوب لتشغيل الترجمة / Permission required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_OVERLAY -> requestPermissionsAndStart()
        }
    }

    private fun stopCaptioning() {
        CaptionService.stop(this)
        OverlayService.hide(this)
        CaptionService.isRunning = false
        updateButtonState()
    }

    companion object {
        private const val REQ_MIC = 1
        private const val REQ_NOTIF = 2
        private const val REQ_OVERLAY = 3
        private const val PREFS = "omran_caption_prefs"
        private const val KEY_SPEAK = "speak_lang"
        private const val KEY_TRANSLATE = "translate_lang"
    }
}
