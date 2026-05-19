package com.wisprflow

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.wisprflow.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PrefsManager
    private lateinit var historyAdapter: ArrayAdapter<String>
    private val historyList = mutableListOf<String>()

    private val languages = listOf(
        "cs-CZ" to "Čeština",
        "en-US" to "Angličtina (US)",
        "en-GB" to "Angličtina (UK)",
        "de-DE" to "Němčina",
        "sk-SK" to "Slovenština",
        "pl-PL" to "Polština",
        "fr-FR" to "Francouzština",
        "es-ES" to "Španělština"
    )

    private val historyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshHistory()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = PrefsManager(this)

        setupLanguageSpinner()
        setupHistoryList()
        setupClickListeners()
        loadSettings()
        requestMicPermission()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        refreshHistory()
        registerReceiver(historyReceiver, IntentFilter("com.wisprflow.HISTORY_UPDATED"),
            RECEIVER_NOT_EXPORTED)
    }

    override fun onPause() {
        super.onPause()
        unregisterReceiver(historyReceiver)
    }

    private fun setupLanguageSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages.map { it.second })
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguage.adapter = adapter
    }

    private fun setupHistoryList() {
        historyAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, historyList)
        binding.listHistory.adapter = historyAdapter
    }

    private fun setupClickListeners() {
        binding.btnOverlayPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        binding.btnAccessibilityPermission.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnStartService.setOnClickListener {
            when {
                !canDrawOverlays() -> toast(getString(R.string.grant_overlay_first))
                !hasMicPermission() -> requestMicPermission()
                else -> {
                    val intent = Intent(this, FloatingButtonService::class.java)
                    startForegroundService(intent)
                    toast(getString(R.string.service_started))
                }
            }
        }
        binding.btnStopService.setOnClickListener {
            stopService(Intent(this, FloatingButtonService::class.java))
            toast(getString(R.string.service_stopped))
        }
        binding.btnSaveSettings.setOnClickListener {
            saveSettings()
            toast(getString(R.string.settings_saved))
        }
        binding.btnClearHistory.setOnClickListener {
            prefs.clearHistory()
            refreshHistory()
        }
    }

    private fun loadSettings() {
        binding.etApiKey.setText(prefs.claudeApiKey)
        binding.switchAiCleanup.isChecked = prefs.aiCleanupEnabled
        val langIndex = languages.indexOfFirst { it.first == prefs.languageCode }
        if (langIndex >= 0) binding.spinnerLanguage.setSelection(langIndex)
    }

    private fun saveSettings() {
        prefs.claudeApiKey = binding.etApiKey.text.toString().trim()
        prefs.aiCleanupEnabled = binding.switchAiCleanup.isChecked
        val selectedLang = languages[binding.spinnerLanguage.selectedItemPosition].first
        prefs.languageCode = selectedLang
    }

    private fun updatePermissionStatus() {
        binding.tvOverlayStatus.text = if (canDrawOverlays()) "✓ Povoleno" else "✗ Zakázáno"
        binding.tvAccessibilityStatus.text = if (isAccessibilityEnabled()) "✓ Povoleno" else "✗ Zakázáno"
        binding.tvMicStatus.text = if (hasMicPermission()) "✓ Povoleno" else "✗ Zakázáno"
    }

    private fun refreshHistory() {
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())
        historyList.clear()
        prefs.getHistory().forEach { entry ->
            val parts = entry.split("|", limit = 2)
            if (parts.size == 2) {
                val time = fmt.format(Date(parts[0].toLongOrNull() ?: 0))
                historyList.add("[$time] ${parts[1]}")
            }
        }
        historyAdapter.notifyDataSetChanged()
        if (historyList.isEmpty()) {
            historyList.add(getString(R.string.no_history))
            historyAdapter.notifyDataSetChanged()
        }
    }

    private fun canDrawOverlays() = Settings.canDrawOverlays(this)

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun requestMicPermission() {
        if (!hasMicPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 100)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val services = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        return services.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
