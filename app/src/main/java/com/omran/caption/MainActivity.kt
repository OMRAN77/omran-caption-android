package com.omran.caption

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
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
    private lateinit var projectionManager: MediaProjectionManager

    private val languages = listOf(
        "العربية" to "ar", "English" to "en", "Español" to "es", "Français" to "fr",
        "Deutsch" to "de", "Türkçe" to "tr", "Русский" to "ru", "中文" to "zh",
        "日本語" to "ja", "한국어" to "ko", "Português" to "pt", "Italiano" to "it",
        "हिन्दी" to "hi", "اردو" to "ur", "Bahasa Indonesia" to "id"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        projectionManager = getSystemService(MediaProjectionManager::class.java)

        val names = languages.map { it.first }
        val adapter = ArrayAdapter(this, R.layout.spinner_item, names)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        binding.spinnerSpeaking.adapter = adapter
        binding.spinnerTranslate.adapter = adapter
        binding.spinnerSpeaking.setSelection(0) // Arabic default
        binding.spinnerTranslate.setSelection(1) // English default

        binding.spinnerSpeaking.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                Toast.makeText(this@MainActivity, "لغة التحدث: ${names[position]}", Toast.LENGTH_SHORT).show()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        binding.spinnerTranslate.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
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

        binding.btnGoPremium.setOnClickListener {
            Toast.makeText(this, "قريبًا / Coming soon", Toast.LENGTH_SHORT).show()
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
        // 4) MediaProjection (system audio capture) permission
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_PROJECTION)
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
            REQ_PROJECTION -> {
                if (resultCode == Activity.RESULT_OK && data != null) {
                    val speakingCode = languages[binding.spinnerSpeaking.selectedItemPosition].second
                    val translateCode = languages[binding.spinnerTranslate.selectedItemPosition].second
                    CaptionService.start(this, resultCode, data, speakingCode, translateCode)
                    OverlayService.show(this)
                    CaptionService.isRunning = true
                    updateButtonState()
                } else {
                    Toast.makeText(this, "تم رفض إذن التقاط الصوت / Capture permission denied", Toast.LENGTH_LONG).show()
                }
            }
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
        private const val REQ_PROJECTION = 4
    }
}
