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
    private val mainHandler = Handler(Looper.getMainLooper())

    // Accuracy Tracking State
    private var lastCountedFingerprint: String? = null
    private var currentPendingFingerprint: String? = null
    private var pendingPlatform: String? = null

    // Require 2.5 seconds of continuous viewing on a new video before counting it
    // This eliminates false counts from fast scrolling or skimming
    private val ACTIVE_WATCH_THRESHOLD_MS = 2500L

    private val watchRunnable = Runnable {
        val pendingFp = currentPendingFingerprint
        val platform = pendingPlatform
        if (pendingFp != null && platform != null && pendingFp != lastCountedFingerprint) {
            lastCountedFingerprint = pendingFp
            currentPendingFingerprint = null
            onReelWatched(platform)
        }
    }

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
        mainHandler.removeCallbacksAndMessages(null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        // 1. If limit is active, block access to monitored apps
        if (prefs.isLimitReached()) {
            if (isMonitoredPackage(packageName)) {
                if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                    enforceAppClose("Daily limit already reached for today!")
                    return
                }
            }
        }

        // 2. Only process monitored packages
        if (!isMonitoredPackage(packageName)) {
            // User switched to another app: cancel pending watch
            mainHandler.removeCallbacks(watchRunnable)
            currentPendingFingerprint = null
            return
        }

        // 3. Inspect the active window hierarchy to identify if a short-form video is being watched
        val rootNode = rootInActiveWindow ?: return
        inspectForReel(packageName, rootNode)
    }

    private fun isMonitoredPackage(packageName: String): Boolean {
        return when (packageName) {
            PACKAGE_YOUTUBE -> prefs.isYoutubeEnabled
            PACKAGE_INSTAGRAM -> prefs.isInstagramEnabled
            PACKAGE_SNAPCHAT -> prefs.isSnapchatEnabled
            else -> false
        }
    }

    private fun inspectForReel(packageName: String, rootNode: AccessibilityNodeInfo) {
        val platform = when (packageName) {
            PACKAGE_YOUTUBE -> "youtube"
            PACKAGE_INSTAGRAM -> "instagram"
            PACKAGE_SNAPCHAT -> "snapchat"
            else -> return
        }

        val fingerprint = when (platform) {
            "youtube" -> getYouTubeShortsFingerprint(rootNode)
            "instagram" -> getInstagramReelFingerprint(rootNode)
            "snapchat" -> getSnapchatSpotlightFingerprint(rootNode)
            else -> null
        }

        // User is NOT on a short-form video right now (e.g. in Chats, DMs, Home Feed, Camera, etc.)
        if (fingerprint == null) {
            mainHandler.removeCallbacks(watchRunnable)
            currentPendingFingerprint = null
            return
        }

        // Video was ALREADY counted once! Do not count again, even if watched for minutes.
        if (fingerprint == lastCountedFingerprint) {
            return
        }

        // Already waiting on this exact pending video's threshold timer
        if (fingerprint == currentPendingFingerprint) {
            return
        }

        // User has arrived at a NEW video!
        // Start the 2.5s active-viewing verification timer.
        mainHandler.removeCallbacks(watchRunnable)
        currentPendingFingerprint = fingerprint
        pendingPlatform = platform
        mainHandler.postDelayed(watchRunnable, ACTIVE_WATCH_THRESHOLD_MS)
    }

    // ==========================================
    // YOUTUBE SHORTS DETECTION
    // ==========================================
    private fun getYouTubeShortsFingerprint(rootNode: AccessibilityNodeInfo): String? {
        // Exclude standard watch pages and main search
        if (hasAnyViewId(rootNode, "watch_panel", "watch_while_layout", "player_control_play_pause_replay_button")) {
            return null
        }

        // Must be in Shorts container
        val isShorts = hasAnyViewId(rootNode, "shorts_player_pager", "reel_recycler", "reel_player_page_view", "shorts_container")
        if (!isShorts) return null

        // Extract title or channel name to identify this specific short
        val textSnippets = collectMeaningfulText(rootNode, maxItems = 4)
        if (textSnippets.isNotEmpty()) {
            return "yt_" + textSnippets.joinToString("_").take(60)
        }
        return "yt_short_" + rootNode.hashCode()
    }

    // ==========================================
    // INSTAGRAM REELS DETECTION
    // ==========================================
    private fun getInstagramReelFingerprint(rootNode: AccessibilityNodeInfo): String? {
        // 1. HARD EXCLUSION: Direct Messages (Chats)
        if (hasAnyViewId(rootNode, "direct_text_message", "row_thread_composer", "message_composer", "direct_thread", "inbox")) {
            return null
        }

        // 2. HARD EXCLUSION: Stories viewer
        if (hasAnyViewId(rootNode, "story_progress_bar", "viewer_reel_header", "reel_viewer_title")) {
            return null
        }

        // 3. Must be in Reels / Clips viewer
        val isClips = hasAnyViewId(rootNode, "clips_viewer_fragment", "clips_video_container", "clips_media_item", "clips_viewer")
        if (!isClips) return null

        // Extract author handle or audio title to fingerprint this specific reel
        val textSnippets = collectMeaningfulText(rootNode, maxItems = 4)
        if (textSnippets.isNotEmpty()) {
            return "ig_" + textSnippets.joinToString("_").take(60)
        }
        return "ig_reel_" + rootNode.hashCode()
    }

    // ==========================================
    // SNAPCHAT SPOTLIGHT DETECTION
    // ==========================================
    private fun getSnapchatSpotlightFingerprint(rootNode: AccessibilityNodeInfo): String? {
        // 1. HARD EXCLUSION: Chats / Friends screen
        if (hasAnyViewId(rootNode, "chat_input_text", "chat_message", "chat_container", "chat_feed", "chat_session", "presence", "composer")) {
            return null
        }
        val chatTextMatches = rootNode.findAccessibilityNodeInfosByText("Send a chat")
        if (chatTextMatches.isNotEmpty()) return null

        // 2. HARD EXCLUSION: Camera screen & Map
        if (hasAnyViewId(rootNode, "camera_capture_button", "shutter_button", "map_view")) {
            return null
        }

        // 3. MUST BE in the actual full-screen Spotlight player container
        val isSpotlightPlayer = hasAnyViewId(rootNode, "spotlight_carousel", "spotlight_video", "spotlight_fullscreen", "spotlight_player", "neon_view_pager")
        if (!isSpotlightPlayer) return null

        // Verify spotlight controls are present (sound title, like button, creator handle)
        val textSnippets = collectMeaningfulText(rootNode, maxItems = 3)
        if (textSnippets.isNotEmpty()) {
            return "sc_" + textSnippets.joinToString("_").take(60)
        }
        return "sc_spotlight_" + rootNode.hashCode()
    }

    // ==========================================
    // HIERARCHY HELPER UTILITIES
    // ==========================================
    private fun hasAnyViewId(root: AccessibilityNodeInfo?, vararg idFragments: String): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 150) {
            val node = queue.removeFirst()
            visited++
            val resId = node.viewIdResourceName?.lowercase()
            if (resId != null) {
                for (frag in idFragments) {
                    if (resId.contains(frag.lowercase())) {
                        return true
                    }
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun collectMeaningfulText(root: AccessibilityNodeInfo?, maxItems: Int = 4): List<String> {
        val result = mutableListOf<String>()
        if (root == null) return result

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        // Filter out generic UI buttons and tabs that appear across screens
        val ignoredWords = setOf(
            "like", "comment", "share", "send", "more", "search", "home",
            "reels", "spotlight", "profile", "subscribe", "following",
            "camera", "chat", "reply", "remix", "audio"
        )

        while (queue.isNotEmpty() && visited < 120 && result.size < maxItems) {
            val node = queue.removeFirst()
            visited++

            val text = node.text?.toString()?.trim()
            if (!text.isNullOrEmpty() && text.length >= 3) {
                val lower = text.lowercase()
                if (!ignoredWords.contains(lower)) {
                    result.add(text.replace("\\s+".toRegex(), "_"))
                }
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return result
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

    private fun enforceAppClose(reason: String) {
        // 1. Kick user immediately to the device Home Screen
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Trigger vibration alert
        triggerVibration()

        // 3. Show notification
        sendLimitNotification(prefs.dailyLimit)

        // 4. Launch Lock Screen overlay after slight delay so HOME action completes
        mainHandler.postDelayed({
            val intent = Intent(this, LockOverlayActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("reason", reason)
                putExtra("limit", prefs.dailyLimit)
            }
            startActivity(intent)
        }, 350)
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
        mainHandler.removeCallbacksAndMessages(null)
    }
}
