package com.reellimit.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

class ReelAccessibilityService : AccessibilityService() {

    private lateinit var prefs: PreferencesManager
    private val handler = Looper.myLooper()?.let { Handler(it) } ?: Handler(Looper.getMainLooper())

    private var lastScrollTime: Long = 0L
    private val DEBOUNCE_DELAY_MS = 1800L // 1.8 seconds between counts to ensure active viewing

    companion object {
        const val CHANNEL_ID = "reellimit_alerts"
        const val NOTIFICATION_ID_REMINDER = 101
        const val NOTIFICATION_ID_LIMIT = 102

        const val PACKAGE_YOUTUBE = "com.google.android.youtube"
        const val PACKAGE_INSTAGRAM = "com.instagram.android"
        const val PACKAGE_SNAPCHAT = "com.snapchat.android"

        var isServiceRunning = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = PreferencesManager(this)
        isServiceRunning = true
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        // 1. Check if user is attempting to open an app while limit is active
        if (prefs.isLimitReached()) {
            if (isMonitoredPackage(packageName)) {
                enforceAppClose("Daily limit already reached for today!")
                return
            }
        }

        // 2. Filter events
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (isMonitoredPackage(packageName)) {
                    inspectForReelScroll(packageName, rootInActiveWindow)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (isMonitoredPackage(packageName) && prefs.isLimitReached()) {
                    enforceAppClose("Daily limit reached!")
                }
            }
        }
    }

    private fun isMonitoredPackage(packageName: String): Boolean {
        return when (packageName) {
            PACKAGE_YOUTUBE -> prefs.isYoutubeEnabled
            PACKAGE_INSTAGRAM -> prefs.isInstagramEnabled
            PACKAGE_SNAPCHAT -> prefs.isSnapchatEnabled
            else -> false
        }
    }

    private fun inspectForReelScroll(packageName: String, rootNode: AccessibilityNodeInfo?) {
        val now = System.currentTimeMillis()
        if (now - lastScrollTime < DEBOUNCE_DELAY_MS) return

        val isReelActive = when (packageName) {
            PACKAGE_YOUTUBE -> isYouTubeShortsActive(rootNode)
            PACKAGE_INSTAGRAM -> isInstagramReelActive(rootNode)
            PACKAGE_SNAPCHAT -> isSnapchatSpotlightActive(rootNode)
            else -> false
        }

        if (isReelActive) {
            lastScrollTime = now
            val platform = when (packageName) {
                PACKAGE_YOUTUBE -> "youtube"
                PACKAGE_INSTAGRAM -> "instagram"
                else -> "snapchat"
            }
            onReelWatched(platform)
        }
    }

    private fun isYouTubeShortsActive(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        // Search node tree for YouTube Shorts indicators
        val shortsNodes = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/shorts_player_pager")
        if (shortsNodes.isNotEmpty()) return true

        val reelRecycler = rootNode.findAccessibilityNodeInfosByViewId("com.google.android.youtube:id/reel_recycler")
        if (reelRecycler.isNotEmpty()) return true

        val textMatches = rootNode.findAccessibilityNodeInfosByText("Shorts")
        return textMatches.isNotEmpty()
    }

    private fun isInstagramReelActive(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        val clipsNodes = rootNode.findAccessibilityNodeInfosByViewId("com.instagram.android:id/clips_viewer")
        if (clipsNodes.isNotEmpty()) return true

        val reelNodes = rootNode.findAccessibilityNodeInfosByViewId("com.instagram.android:id/reel_viewer")
        if (reelNodes.isNotEmpty()) return true

        val textMatches = rootNode.findAccessibilityNodeInfosByText("Reels")
        return textMatches.isNotEmpty()
    }

    private fun isSnapchatSpotlightActive(rootNode: AccessibilityNodeInfo?): Boolean {
        if (rootNode == null) return false
        val spotlightNodes = rootNode.findAccessibilityNodeInfosByViewId("com.snapchat.android:id/spotlight_carousel")
        if (spotlightNodes.isNotEmpty()) return true

        val textMatches = rootNode.findAccessibilityNodeInfosByText("Spotlight")
        return textMatches.isNotEmpty()
    }

    private fun onReelWatched(platform: String) {
        val newTotal = prefs.incrementCount(platform)

        // Check if milestone hit (every 10 reels)
        if (newTotal > 0 && newTotal % prefs.reminderInterval == 0) {
            sendReminderNotification(newTotal)
            triggerVibration()
        }

        // Check if daily limit reached
        if (newTotal >= prefs.dailyLimit) {
            enforceAppClose("Daily limit of ${prefs.dailyLimit} reels reached!")
        }
    }

    /**
     * Closes the foreground app by sending HOME action and launches the Lock Overlay Screen
     */
    private fun enforceAppClose(reason: String) {
        // 1. Kick user immediately to the device Home Screen
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Trigger vibration alert
        triggerVibration()

        // 3. Show notification
        sendLimitNotification(prefs.dailyLimit)

        // 4. Launch Lock Screen overlay after slight delay so HOME action completes
        handler.postDelayed({
            val intent = Intent(this, LockOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("reason", reason)
                putExtra("limit", prefs.dailyLimit)
            }
            startActivity(intent)
        }, 300)
    }

    private fun sendReminderNotification(count: Int) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🔔 $count Reels Watched Today!")
            .setContentText("Mindful check: You've scrolled $count short videos. Stretch or take a breather! 🌿")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_REMINDER, notification)
    }

    private fun sendLimitNotification(limit: Int) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("🛑 Daily Reel Limit Reached ($limit)!")
            .setContentText("Feed has been closed to protect your productivity and focus.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID_LIMIT, notification)
    }

    private fun triggerVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(350)
            }
        } catch (e: Exception) {
            // Some devices might restrict vibration
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ReelLimit Alerts & Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies you every 10 reels watched and alerts when your limit is reached"
                enableVibration(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onInterrupt() {
        isServiceRunning = false
    }
}
