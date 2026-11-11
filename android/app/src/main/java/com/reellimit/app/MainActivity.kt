package com.reellimit.app

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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

    private lateinit var cardOverlayWarning: MaterialCardView
    private lateinit var btnEnableOverlay: Button

    private lateinit var tvTotalWatched: TextView
    private lateinit var tvLimitCaption: TextView
    private lateinit var tvCountYoutube: TextView
    private lateinit var tvCountInstagram: TextView
    private lateinit var tvCountSnapchat: TextView

    private lateinit var tvDailyLimitValue: TextView
    private lateinit var sliderDailyLimit: Slider

    private lateinit var tvWatchThresholdValue: TextView
    private lateinit var sliderWatchThreshold: Slider

    private lateinit var switchYoutube: SwitchMaterial
    private lateinit var switchInstagram: SwitchMaterial
    private lateinit var switchSnapchat: SwitchMaterial

    private lateinit var switchFloatingOverlay: SwitchMaterial
    private lateinit var layoutBubbleCorner: View
    private lateinit var toggleBubbleCorner: com.google.android.material.button.MaterialButtonToggleGroup
    private lateinit var btnCornerLeft: com.google.android.material.button.MaterialButton
    private lateinit var btnCornerRight: com.google.android.material.button.MaterialButton
    private lateinit var btnTestBubble: Button

    private lateinit var switchHapticTick: SwitchMaterial

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

        cardOverlayWarning = findViewById(R.id.cardOverlayWarning)
        btnEnableOverlay = findViewById(R.id.btnEnableOverlay)

        tvTotalWatched = findViewById(R.id.tvTotalWatched)
        tvLimitCaption = findViewById(R.id.tvLimitCaption)
        tvCountYoutube = findViewById(R.id.tvCountYoutube)
        tvCountInstagram = findViewById(R.id.tvCountInstagram)
        tvCountSnapchat = findViewById(R.id.tvCountSnapchat)

        tvDailyLimitValue = findViewById(R.id.tvDailyLimitValue)
        sliderDailyLimit = findViewById(R.id.sliderDailyLimit)

        tvWatchThresholdValue = findViewById(R.id.tvWatchThresholdValue)
        sliderWatchThreshold = findViewById(R.id.sliderWatchThreshold)

        switchYoutube = findViewById(R.id.switchYoutube)
        switchInstagram = findViewById(R.id.switchInstagram)
        switchSnapchat = findViewById(R.id.switchSnapchat)

        switchFloatingOverlay = findViewById(R.id.switchFloatingOverlay)
        layoutBubbleCorner = findViewById(R.id.layoutBubbleCorner)
        toggleBubbleCorner = findViewById(R.id.toggleBubbleCorner)
        btnCornerLeft = findViewById(R.id.btnCornerLeft)
        btnCornerRight = findViewById(R.id.btnCornerRight)
        btnTestBubble = findViewById(R.id.btnTestBubble)

        switchHapticTick = findViewById(R.id.switchHapticTick)

        btnResetToday = findViewById(R.id.btnResetToday)
        btnTestReminder = findViewById(R.id.btnTestReminder)

        // Set initial slider & switches values
        sliderDailyLimit.value = prefs.dailyLimit.toFloat()
        tvDailyLimitValue.text = "${prefs.dailyLimit} Reels"

        val threshold = prefs.watchThresholdSeconds.coerceIn(0.6f, 3.0f)
        sliderWatchThreshold.value = threshold
        tvWatchThresholdValue.text = "${threshold}s"

        switchYoutube.isChecked = prefs.isYoutubeEnabled
        switchInstagram.isChecked = prefs.isInstagramEnabled
        switchSnapchat.isChecked = prefs.isSnapchatEnabled

        switchFloatingOverlay.isChecked = prefs.showFloatingOverlay
        layoutBubbleCorner.visibility = if (prefs.showFloatingOverlay) View.VISIBLE else View.GONE
        if (prefs.bubbleCorner == PreferencesManager.CORNER_TOP_LEFT) {
            toggleBubbleCorner.check(R.id.btnCornerLeft)
        } else {
            toggleBubbleCorner.check(R.id.btnCornerRight)
        }

        switchHapticTick.isChecked = prefs.hapticFeedbackEnabled
    }

    private fun setupListeners() {
        btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        btnEnableOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
        }

        sliderDailyLimit.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val limit = value.toInt()
                prefs.dailyLimit = limit
                tvDailyLimitValue.text = "$limit Reels"
                tvLimitCaption.text = "of $limit Reels Watched Today"
            }
        }

        sliderWatchThreshold.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                prefs.watchThresholdSeconds = value
                tvWatchThresholdValue.text = "${value}s"
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

        switchFloatingOverlay.setOnCheckedChangeListener { _, isChecked ->
            prefs.showFloatingOverlay = isChecked
            layoutBubbleCorner.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
                Toast.makeText(this, "Enable 'Display over other apps' to show the reel counting bubble", Toast.LENGTH_LONG).show()
            }
        }

        toggleBubbleCorner.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                if (checkedId == R.id.btnCornerLeft) {
                    prefs.bubbleCorner = PreferencesManager.CORNER_TOP_LEFT
                    Toast.makeText(this, "Reel bubble set to Top Left corner", Toast.LENGTH_SHORT).show()
                } else if (checkedId == R.id.btnCornerRight) {
                    prefs.bubbleCorner = PreferencesManager.CORNER_TOP_RIGHT
                    Toast.makeText(this, "Reel bubble set to Top Right corner", Toast.LENGTH_SHORT).show()
                }
            }
        }

        btnTestBubble.setOnClickListener {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "⚠️ Please enable ReelLimit in Accessibility Settings first!", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                return@setOnClickListener
            }
            val intent = Intent(this, ReelAccessibilityService::class.java).apply {
                action = ReelAccessibilityService.ACTION_TEST_BUBBLE
            }
            startService(intent)
            val cornerName = if (prefs.bubbleCorner == PreferencesManager.CORNER_TOP_LEFT) "Top Left" else "Top Right"
            Toast.makeText(this, "🫧 Reel bubble preview activated in $cornerName! Try dragging it.", Toast.LENGTH_LONG).show()
        }

        switchHapticTick.setOnCheckedChangeListener { _, isChecked ->
            prefs.hapticFeedbackEnabled = isChecked
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
            if (ReelAccessibilityService.isServiceRunning) {
                tvServiceStatus.text = "Active & Monitoring"
                tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
                cardPermissionWarning.visibility = View.GONE
            } else {
                tvServiceStatus.text = "Re-toggle in Accessibility Settings"
                tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.warning))
                cardPermissionWarning.visibility = View.VISIBLE
            }
        } else {
            tvServiceStatus.text = "Service Disabled"
            tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.danger))
            cardPermissionWarning.visibility = View.VISIBLE
        }

        // Overlay permission check
        val hasOverlayPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)
        if (!hasOverlayPermission && prefs.showFloatingOverlay) {
            cardOverlayWarning.visibility = View.VISIBLE
        } else {
            cardOverlayWarning.visibility = View.GONE
        }

        val total = prefs.getTotalWatched()
        tvTotalWatched.text = total.toString()
        tvLimitCaption.text = "of ${prefs.dailyLimit} Reels Watched Today"

        tvCountYoutube.text = prefs.getYoutubeWatched().toString()
        tvCountInstagram.text = prefs.getInstagramWatched().toString()
        tvCountSnapchat.text = prefs.getSnapchatWatched().toString()

        if (prefs.bubbleCorner == PreferencesManager.CORNER_TOP_LEFT) {
            toggleBubbleCorner.check(R.id.btnCornerLeft)
        } else {
            toggleBubbleCorner.check(R.id.btnCornerRight)
        }
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
