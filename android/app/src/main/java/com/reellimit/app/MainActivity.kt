package com.reellimit.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: PreferencesManager

    private lateinit var tvServiceStatus: TextView
    private lateinit var cardPermissionWarning: MaterialCardView
    private lateinit var btnEnableAccessibility: Button

    private lateinit var tvTotalWatched: TextView
    private lateinit var tvLimitCaption: TextView
    private lateinit var tvCountYoutube: TextView
    private lateinit var tvCountInstagram: TextView
    private lateinit var tvCountSnapchat: TextView

    private lateinit var tvDailyLimitValue: TextView
    private lateinit var sliderDailyLimit: Slider

    private lateinit var switchYoutube: SwitchMaterial
    private lateinit var switchInstagram: SwitchMaterial
    private lateinit var switchSnapchat: SwitchMaterial

    private lateinit var btnResetToday: Button
    private lateinit var btnTestReminder: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = PreferencesManager(this)
        initViews()
        requestNotificationPermissionIfNeeded()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updateDashboard()
    }

    private fun initViews() {
        tvServiceStatus = findViewById(R.id.tvServiceStatus)
        cardPermissionWarning = findViewById(R.id.cardPermissionWarning)
        btnEnableAccessibility = findViewById(R.id.btnEnableAccessibility)

        tvTotalWatched = findViewById(R.id.tvTotalWatched)
        tvLimitCaption = findViewById(R.id.tvLimitCaption)
        tvCountYoutube = findViewById(R.id.tvCountYoutube)
        tvCountInstagram = findViewById(R.id.tvCountInstagram)
        tvCountSnapchat = findViewById(R.id.tvCountSnapchat)

        tvDailyLimitValue = findViewById(R.id.tvDailyLimitValue)
        sliderDailyLimit = findViewById(R.id.sliderDailyLimit)

        switchYoutube = findViewById(R.id.switchYoutube)
        switchInstagram = findViewById(R.id.switchInstagram)
        switchSnapchat = findViewById(R.id.switchSnapchat)

        btnResetToday = findViewById(R.id.btnResetToday)
        btnTestReminder = findViewById(R.id.btnTestReminder)

        // Set initial slider & switches values
        sliderDailyLimit.value = prefs.dailyLimit.toFloat()
        tvDailyLimitValue.text = "${prefs.dailyLimit} Reels"

        switchYoutube.isChecked = prefs.isYoutubeEnabled
        switchInstagram.isChecked = prefs.isInstagramEnabled
        switchSnapchat.isChecked = prefs.isSnapchatEnabled
    }

    private fun setupListeners() {
        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        sliderDailyLimit.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val limit = value.toInt()
                prefs.dailyLimit = limit
                tvDailyLimitValue.text = "$limit Reels"
                tvLimitCaption.text = "of $limit Reels Watched Today"
            }
        }

        switchYoutube.setOnCheckedChangeListener { _, isChecked ->
            prefs.isYoutubeEnabled = isChecked
        }

        switchInstagram.setOnCheckedChangeListener { _, isChecked ->
            prefs.isInstagramEnabled = isChecked
        }

        switchSnapchat.setOnCheckedChangeListener { _, isChecked ->
            prefs.isSnapchatEnabled = isChecked
        }

        btnResetToday.setOnClickListener {
            prefs.resetToday()
            updateDashboard()
            Toast.makeText(this, "Today's watch count reset to 0", Toast.LENGTH_SHORT).show()
        }

        btnTestReminder.setOnClickListener {
            sendTestNotification()
            Toast.makeText(this, "Test milestone reminder triggered!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateDashboard() {
        val isServiceEnabled = isAccessibilityServiceEnabled()
        if (isServiceEnabled) {
            tvServiceStatus.text = "Active & Monitoring"
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            cardPermissionWarning.visibility = View.GONE
        } else {
            tvServiceStatus.text = "Service Disabled"
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
            cardPermissionWarning.visibility = View.VISIBLE
        }

        val total = prefs.getTotalWatched()
        tvTotalWatched.text = total.toString()
        tvLimitCaption.text = "of ${prefs.dailyLimit} Reels Watched Today"

        tvCountYoutube.text = prefs.getYoutubeWatched().toString()
        tvCountInstagram.text = prefs.getInstagramWatched().toString()
        tvCountSnapchat.text = prefs.getSnapchatWatched().toString()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC)
        for (service in enabledServices) {
            if (service.resolveInfo.serviceInfo.packageName == packageName &&
                service.resolveInfo.serviceInfo.name.contains("ReelAccessibilityService")) {
                return true
            }
        }
        return false
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    private fun sendTestNotification() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(this, ReelAccessibilityService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🔔 10 Reels Milestone (Test)")
            .setContentText("You've watched 10 reels! Mindful reminder to take a break.")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(999, notification)
    }
}
