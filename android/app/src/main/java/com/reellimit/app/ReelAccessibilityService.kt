package com.reellimit.app

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.Locale

class ReelAccessibilityService : AccessibilityService() {

    private lateinit var prefs: PreferencesManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // ==========================================
    // SCROLL-GATED STATE MACHINE 2.0
    // ==========================================
    // Guarantees: 1 physical video scroll = max 1 count.
    // Loop-watching 1 video for minutes will NEVER count 20 times!
    private var isLockedOnCountedVideo = false
    private var currentVideoSignature: String? = null
    private var activePlatform: String? = null
    private var videoWatchStartTime: Long = 0L
    private var activePackageName: String? = null

    // Floating HUD Overlay
    private var windowManager: WindowManager? = null
    private var hudContainer: FrameLayout? = null
    private var hudTextView: TextView? = null
    private var isHudAttached = false

    // Periodic check ticker (runs every 250ms when monitored app is open)
    private val tickerRunnable = object : Runnable {
        override fun run() {
            tickActiveApp()
            if (activePackageName != null) {
                mainHandler.postDelayed(this, 250L)
            }
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
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        isServiceRunning = true
        createNotificationChannel()
    }

    override fun onDestroy() {
        super.onDestroy()
        isServiceRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        removeFloatingHud()
    }

    override fun onInterrupt() {
        isServiceRunning = false
        mainHandler.removeCallbacksAndMessages(null)
        removeFloatingHud()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val packageName = event.packageName?.toString() ?: return

        // 1. If daily limit is already reached, immediately block and close monitored apps
        if (prefs.isLimitReached() && isMonitoredPackage(packageName)) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                enforceAppClose("Daily limit of ${prefs.dailyLimit} reels reached!")
                return
            }
        }

        // 2. Monitored package boundary handling
        if (!isMonitoredPackage(packageName)) {
            if (activePackageName != null) {
                // User navigated away from monitored app
                activePackageName = null
                activePlatform = null
                currentVideoSignature = null
                isLockedOnCountedVideo = false
                videoWatchStartTime = 0L
                mainHandler.removeCallbacks(tickerRunnable)
                hideFloatingHud()
            }
            return
        }

        // 3. User entered or is active in a monitored app
        if (activePackageName != packageName) {
            activePackageName = packageName
            activePlatform = getPlatformFromPackage(packageName)
            mainHandler.removeCallbacks(tickerRunnable)
            mainHandler.post(tickerRunnable)
        }

        // 4. SCROLL EVENT DETECTION:
        // A physical scroll gesture occurred (user swiping up/down to next reel)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Unlock state machine so next video can be evaluated
            if (isLockedOnCountedVideo) {
                isLockedOnCountedVideo = false
                currentVideoSignature = null
                videoWatchStartTime = 0L
                updateFloatingHud("🔄 Swiped", HudStatus.TRANSITION)
            }
        }

        // 5. Run immediate inspection on state changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            tickActiveApp()
        }
    }

    private fun getPlatformFromPackage(packageName: String): String {
        return when (packageName) {
            PACKAGE_YOUTUBE -> "youtube"
            PACKAGE_INSTAGRAM -> "instagram"
            PACKAGE_SNAPCHAT -> "snapchat"
            else -> ""
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

    // ==========================================
    // CORE TICKER & VIDEO EVALUATION
    // ==========================================
    private fun tickActiveApp() {
        val rootNode = rootInActiveWindow ?: return
        val platform = activePlatform ?: return

        // Check if user is currently watching short-form video or elsewhere in the app
        val signature = when (platform) {
            "youtube" -> evaluateYouTubeShorts(rootNode)
            "instagram" -> evaluateInstagramReel(rootNode)
            "snapchat" -> evaluateSnapchatSpotlight(rootNode)
            else -> null
        }

        // --- CASE A: User is NOT on a short-form video (e.g. Chat, Home, Camera) ---
        if (signature == null) {
            videoWatchStartTime = 0L
            currentVideoSignature = null
            // HUD feedback for chat / non-video screens
            when (platform) {
                "snapchat" -> updateFloatingHud("💬 Chat / Stories (Paused)", HudStatus.PAUSED)
                "instagram" -> updateFloatingHud("🏠 Feed / DMs (Paused)", HudStatus.PAUSED)
                "youtube" -> updateFloatingHud("▶ Watching Video (Paused)", HudStatus.PAUSED)
            }
            return
        }

        // --- CASE B: Video is ALREADY COUNTED & LOCKED ---
        // Prevents counting a single 30-second reel 20 times!
        if (isLockedOnCountedVideo && currentVideoSignature == signature) {
            val total = prefs.getTotalWatched()
            val limit = prefs.dailyLimit
            updateFloatingHud("🌿 $total/$limit • Counted (Locked)", HudStatus.LOCKED)
            return
        }

        // --- CASE C: New Video Detected (or signature changed) ---
        if (currentVideoSignature != signature) {
            currentVideoSignature = signature
            videoWatchStartTime = System.currentTimeMillis()
            isLockedOnCountedVideo = false
        }

        // --- CASE D: Active Video Watch Timing ---
        val now = System.currentTimeMillis()
        val elapsedSec = (now - videoWatchStartTime) / 1000f
        val thresholdSec = prefs.watchThresholdSeconds
        val total = prefs.getTotalWatched()
        val limit = prefs.dailyLimit

        if (elapsedSec < thresholdSec) {
            // Still watching, accumulating time towards verification
            val formatted = String.format(Locale.US, "%.1fs/%.1fs", elapsedSec, thresholdSec)
            updateFloatingHud("⏱️ $total/$limit • $formatted", HudStatus.WATCHING)
        } else {
            // Watch threshold fulfilled! Increment count exactly ONCE!
            isLockedOnCountedVideo = true
            val newTotal = prefs.incrementCount(platform)

            // Trigger subtle haptic tick to give tactile confirmation
            triggerHapticTick()

            // Update HUD
            updateFloatingHud("✅ $newTotal/$limit • +1 Counted! 🌿", HudStatus.COUNTED)

            // Check milestone (every 10 reels)
            if (newTotal > 0 && newTotal % prefs.reminderInterval == 0) {
                sendReminderNotification(newTotal)
                triggerStrongVibration()
            }

            // Check daily limit
            if (newTotal >= prefs.dailyLimit) {
                enforceAppClose("Daily limit of ${prefs.dailyLimit} reels reached!")
            }
        }
    }

    // ==========================================
    // YOUTUBE SHORTS DETECTION
    // ==========================================
    private fun evaluateYouTubeShorts(rootNode: AccessibilityNodeInfo): String? {
        // Exclude standard watch player, search, and channel pages
        if (hasAnyViewId(rootNode, "watch_panel", "watch_while_layout", "player_control_play_pause_replay_button")) {
            return null
        }

        // Must be in Shorts container
        val isShorts = hasAnyViewId(
            rootNode,
            "shorts_player_pager", "reel_recycler", "reel_player_page_view", "shorts_container"
        )
        if (!isShorts) return null

        // Collect title or channel name
        val textSnippets = collectMeaningfulText(rootNode, maxItems = 4)
        if (textSnippets.isNotEmpty()) {
            return "yt_" + textSnippets.joinToString("_").take(60)
        }
        return "yt_short_active_video"
    }

    // ==========================================
    // INSTAGRAM REELS DETECTION
    // ==========================================
    private fun evaluateInstagramReel(rootNode: AccessibilityNodeInfo): String? {
        // 1. HARD EXCLUSION: Direct Messages (Chats) & Comments
        if (hasAnyViewId(
                rootNode,
                "direct_text_message", "row_thread_composer", "message_composer",
                "direct_thread", "inbox", "comment_edit_text", "layout_comment_thread"
            )) {
            return null
        }

        // 2. HARD EXCLUSION: Stories viewer
        if (hasAnyViewId(rootNode, "story_progress_bar", "viewer_reel_header", "reel_viewer_title")) {
            return null
        }

        // 3. Must be in Reels / Clips viewer
        val isClips = hasAnyViewId(
            rootNode,
            "clips_viewer_fragment", "clips_video_container", "clips_media_item", "clips_viewer"
        )
        if (!isClips) return null

        // Collect author handle or audio title
        val textSnippets = collectMeaningfulText(rootNode, maxItems = 4)
        if (textSnippets.isNotEmpty()) {
            return "ig_" + textSnippets.joinToString("_").take(60)
        }
        return "ig_reel_active_video"
    }

    // ==========================================
    // SNAPCHAT SPOTLIGHT DETECTION
    // ==========================================
    private fun evaluateSnapchatSpotlight(rootNode: AccessibilityNodeInfo): String? {
        // 1. HARD EXCLUSION: Any editable input field / keyboard composer
        // If an EditText is visible anywhere, the user is in Chat or Search, NEVER in Spotlight!
        if (hasAnyEditableNode(rootNode)) {
            return null
        }

        // 2. HARD EXCLUSION: Snapchat Chat Elements & Friends List
        if (hasAnyViewId(
                rootNode,
                "chat_input", "chat_message", "chat_container", "chat_feed", "chat_session",
                "presence", "composer", "conversation", "friend_name", "send_btn", "send_button",
                "chat_view_stub", "chat_camera_button", "gallery_button", "sticker_button"
            )) {
            return null
        }

        // Check for common chat strings
        if (hasMatchingText(rootNode, "Send a chat", "Chats", "Friends", "New Chat", "Unread")) {
            return null
        }

        // 3. HARD EXCLUSION: Camera screen & Map
        if (hasAnyViewId(rootNode, "camera_capture_button", "shutter_button", "lens_carousel", "map_view")) {
            return null
        }

        // 4. HARD EXCLUSION: Stories & Discover feed
        if (hasAnyViewId(rootNode, "stories_page", "discover_feed", "friend_story")) {
            return null
        }

        // 5. POSITIVE VERIFICATION: Must be actual Spotlight full-screen player
        val isSpotlight = hasAnyViewId(
            rootNode,
            "spotlight_video", "spotlight_fullscreen", "spotlight_player",
            "spotlight_action_menu", "spotlight_description", "spotlight_audio"
        )
        if (!isSpotlight) return null

        // Collect author handle or sound info
        val textSnippets = collectMeaningfulText(rootNode, maxItems = 3)
        if (textSnippets.isNotEmpty()) {
            return "sc_" + textSnippets.joinToString("_").take(60)
        }
        return "sc_spotlight_active_video"
    }

    // ==========================================
    // NODE INSPECTION HELPERS
    // ==========================================
    private fun hasAnyEditableNode(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < 100) {
            val node = queue.removeFirst()
            visited++

            if (node.isEditable || node.className?.toString()?.contains("EditText", ignoreCase = true) == true) {
                return true
            }

            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun hasMatchingText(root: AccessibilityNodeInfo?, vararg texts: String): Boolean {
        if (root == null) return false
        for (query in texts) {
            val matches = root.findAccessibilityNodeInfosByText(query)
            if (matches != null && matches.isNotEmpty()) {
                return true
            }
        }
        return false
    }

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

        val ignoredWords = setOf(
            "like", "comment", "share", "send", "more", "search", "home",
            "reels", "spotlight", "profile", "subscribe", "following",
            "camera", "chat", "reply", "remix", "audio", "original sound"
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

    // ==========================================
    // FLOATING HUD OVERLAY
    // ==========================================
    enum class HudStatus {
        WATCHING,
        COUNTED,
        LOCKED,
        TRANSITION,
        PAUSED
    }

    private fun updateFloatingHud(message: String, status: HudStatus) {
        if (!prefs.showFloatingOverlay) {
            hideFloatingHud()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            return
        }

        mainHandler.post {
            ensureFloatingHudCreated()
            val tv = hudTextView ?: return@post

            tv.text = message
            val bg = tv.background as? GradientDrawable
            if (bg != null) {
                when (status) {
                    HudStatus.WATCHING -> {
                        bg.setColor(Color.parseColor("#E60F172A")) // Slate dark
                        bg.setStroke(dpToPx(1), Color.parseColor("#38BDF8")) // Cyan pulse
                    }
                    HudStatus.COUNTED -> {
                        bg.setColor(Color.parseColor("#E6064E3B")) // Emerald green
                        bg.setStroke(dpToPx(1), Color.parseColor("#34D399"))
                    }
                    HudStatus.LOCKED -> {
                        bg.setColor(Color.parseColor("#E61E293B")) // Muted dark
                        bg.setStroke(dpToPx(1), Color.parseColor("#10B981"))
                    }
                    HudStatus.TRANSITION -> {
                        bg.setColor(Color.parseColor("#E61E293B"))
                        bg.setStroke(dpToPx(1), Color.parseColor("#F59E0B")) // Amber
                    }
                    HudStatus.PAUSED -> {
                        bg.setColor(Color.parseColor("#E60F172A"))
                        bg.setStroke(dpToPx(1), Color.parseColor("#475569"))
                    }
                }
            }

            hudContainer?.visibility = View.VISIBLE
        }
    }

    private fun ensureFloatingHudCreated() {
        if (hudContainer != null && isHudAttached) return

        try {
            val container = FrameLayout(this)
            val textView = TextView(this).apply {
                textSize = 11.5f
                setTextColor(Color.WHITE)
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setPadding(dpToPx(12), dpToPx(6), dpToPx(12), dpToPx(6))
                val shape = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(16).toFloat()
                    setColor(Color.parseColor("#E60F172A"))
                    setStroke(dpToPx(1), Color.parseColor("#334155"))
                }
                background = shape
            }

            val frameParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            container.addView(textView, frameParams)

            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val wmParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dpToPx(16)
                y = dpToPx(48) // Safe position below status bar
            }

            windowManager?.addView(container, wmParams)
            hudContainer = container
            hudTextView = textView
            isHudAttached = true
        } catch (e: Exception) {
            isHudAttached = false
        }
    }

    private fun hideFloatingHud() {
        mainHandler.post {
            hudContainer?.visibility = View.GONE
        }
    }

    private fun removeFloatingHud() {
        mainHandler.post {
            try {
                if (hudContainer != null && isHudAttached) {
                    windowManager?.removeView(hudContainer)
                }
            } catch (e: Exception) {
                // Ignore
            } finally {
                hudContainer = null
                hudTextView = null
                isHudAttached = false
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    // ==========================================
    // NOTIFICATIONS & ACTIONS
    // ==========================================
    private fun enforceAppClose(reason: String) {
        performGlobalAction(GLOBAL_ACTION_HOME)
        triggerStrongVibration()
        sendLimitNotification(prefs.dailyLimit)

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

    private fun triggerHapticTick() {
        if (!prefs.hapticFeedbackEnabled) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val vibrator = getSystemService(Vibrator::class.java)
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(35)
            }
        } catch (e: Exception) {
            // Silently ignore if device doesn't support tick
        }
    }

    private fun triggerStrongVibration() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val vibrator = getSystemService(Vibrator::class.java)
                vibrator?.vibrate(
                    VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                vibrator?.vibrate(350)
            }
        } catch (e: Exception) {
            // Silently ignore
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
}
