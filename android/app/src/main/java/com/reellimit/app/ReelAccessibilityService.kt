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
import android.os.VibratorManager
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import java.util.ArrayDeque
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

    // Floating Bubble Overlay
    private var windowManager: WindowManager? = null
    private var hudContainer: FrameLayout? = null
    private var bubbleCard: LinearLayout? = null
    private var tvBubbleIcon: TextView? = null
    private var tvBubbleCount: TextView? = null
    private var tvBubbleLimit: TextView? = null
    private var tvBubbleStatus: TextView? = null
    private var tvBubblePlusOne: TextView? = null
    private var hudLayoutParams: WindowManager.LayoutParams? = null
    private var isHudAttached = false
    private var isDetailsExpanded = false

    private val collapseDetailsRunnable = Runnable {
        isDetailsExpanded = false
        tvBubbleStatus?.visibility = View.GONE
        tvBubbleIcon?.text = "🎬"
    }

    private val hideHudRunnable = Runnable {
        hudContainer?.visibility = View.GONE
    }

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
        const val PACKAGE_REEL_LIMIT = "com.reellimit.app"

        const val ACTION_TEST_BUBBLE = "com.reellimit.app.ACTION_TEST_BUBBLE"
        const val ACTION_SHOW_BUBBLE = "com.reellimit.app.ACTION_SHOW_BUBBLE"

        var isServiceRunning = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = PreferencesManager(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        isServiceRunning = true
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TEST_BUBBLE || intent?.action == ACTION_SHOW_BUBBLE) {
            mainHandler.post {
                ensureFloatingHudCreated()
                updateFloatingHud(HudStatus.LOCKED)
                hudContainer?.visibility = View.VISIBLE
                hudContainer?.alpha = 1.0f
            }
        }
        return super.onStartCommand(intent, flags, startId)
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
            // Immediately display the bubble when user opens YouTube, Instagram, Snapchat, or ReelLimit!
            updateFloatingHud(HudStatus.LOCKED)
        }

        // 4. SCROLL EVENT DETECTION:
        // A physical scroll gesture occurred (user swiping up/down to next reel)
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            // Unlock state machine so next video can be evaluated
            if (isLockedOnCountedVideo) {
                isLockedOnCountedVideo = false
                currentVideoSignature = null
                videoWatchStartTime = 0L
                updateFloatingHud(HudStatus.TRANSITION)
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
            PACKAGE_REEL_LIMIT -> "reellimit"
            else -> ""
        }
    }

    private fun isMonitoredPackage(packageName: String): Boolean {
        return when (packageName) {
            PACKAGE_YOUTUBE -> prefs.isYoutubeEnabled
            PACKAGE_INSTAGRAM -> prefs.isInstagramEnabled
            PACKAGE_SNAPCHAT -> prefs.isSnapchatEnabled
            PACKAGE_REEL_LIMIT -> true
            else -> false
        }
    }

    // ==========================================
    // CORE TICKER & VIDEO EVALUATION
    // ==========================================
    private fun tickActiveApp() {
        val rootNode = rootInActiveWindow ?: return
        val platform = activePlatform ?: return

        // In ReelLimit app: keep bubble visible in preview mode
        if (platform == "reellimit") {
            updateFloatingHud(HudStatus.LOCKED)
            return
        }

        // Check if user is currently watching short-form video or elsewhere in the app
        val signature = when (platform) {
            "youtube" -> evaluateYouTubeShorts(rootNode)
            "instagram" -> evaluateInstagramReel(rootNode)
            "snapchat" -> evaluateSnapchatSpotlight(rootNode)
            else -> null
        }

        // --- CASE A: User is in app, but not on active short video (e.g. Home feed, Profile, Search) ---
        if (signature == null) {
            videoWatchStartTime = 0L
            currentVideoSignature = null
            // DO NOT HIDE! Keep bubble visible in calm idle state so user always knows their current count!
            updateFloatingHud(HudStatus.PAUSED)
            return
        }

        // --- CASE B: Video is ALREADY COUNTED & LOCKED ---
        // Prevents counting a single 30-second reel 20 times!
        if (isLockedOnCountedVideo && currentVideoSignature == signature) {
            updateFloatingHud(HudStatus.LOCKED)
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

        if (elapsedSec < thresholdSec) {
            // Still watching, accumulating time towards verification
            updateFloatingHud(HudStatus.WATCHING, elapsedSec, thresholdSec)
        } else {
            // Watch threshold fulfilled! Increment count exactly ONCE!
            isLockedOnCountedVideo = true
            val newTotal = prefs.incrementCount(platform)

            // Trigger subtle haptic tick to give tactile confirmation
            triggerHapticTick()

            // Update HUD
            updateFloatingHud(HudStatus.COUNTED)

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
            "shorts_player_pager", "reel_recycler", "reel_player_page_view", "shorts_container", "shorts_player"
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
            "clips_viewer_fragment", "clips_video_container", "clips_media_item", "clips_viewer",
            "reel_viewer", "clips_video", "feed_video"
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
            "spotlight_action_menu", "spotlight_description", "spotlight_audio", "neon_view_pager"
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
    // FLOATING REEL COUNTING BUBBLE
    // ==========================================
    enum class HudStatus {
        WATCHING,
        COUNTED,
        LOCKED,
        TRANSITION,
        PAUSED
    }

    private fun updateFloatingHud(
        status: HudStatus,
        elapsedSec: Float = 0f,
        thresholdSec: Float = 0f
    ) {
        if (!prefs.showFloatingOverlay) {
            hideFloatingHud()
            return
        }

        mainHandler.post {
            ensureFloatingHudCreated()
            val container = hudContainer ?: return@post

            val total = prefs.getTotalWatched()
            val limit = prefs.dailyLimit

            mainHandler.removeCallbacks(hideHudRunnable)
            container.visibility = View.VISIBLE

            when (status) {
                HudStatus.WATCHING -> {
                    container.alpha = 1.0f
                    tvBubbleIcon?.text = "⏱️"
                    tvBubbleCount?.text = "$total"
                    tvBubbleLimit?.text = "/$limit"

                    if (!isDetailsExpanded) {
                        tvBubbleStatus?.visibility = View.VISIBLE
                        tvBubbleStatus?.setTextColor(Color.parseColor("#38BDF8"))
                        tvBubbleStatus?.text = String.format(Locale.US, "%.1fs", elapsedSec)
                    }
                    setBubbleCardBorder(Color.parseColor("#38BDF8")) // Sky blue pulse
                }

                HudStatus.COUNTED -> {
                    container.alpha = 1.0f
                    tvBubbleIcon?.text = "✅"
                    tvBubbleCount?.text = "$total"
                    tvBubbleLimit?.text = "/$limit"
                    if (!isDetailsExpanded) {
                        tvBubbleStatus?.visibility = View.GONE
                    }
                    setBubbleCardBorder(Color.parseColor("#34D399")) // Bright emerald

                    // Animate "+1" pop badge
                    tvBubblePlusOne?.apply {
                        visibility = View.VISIBLE
                        alpha = 1.0f
                        translationY = 0f
                        animate()
                            .translationY(-dpToPx(16).toFloat())
                            .alpha(0f)
                            .setDuration(1000)
                            .withEndAction { visibility = View.GONE }
                            .start()
                    }

                    // Gentle pulse animation on bubble
                    bubbleCard?.animate()
                        ?.scaleX(1.18f)?.scaleY(1.18f)
                        ?.setDuration(130)
                        ?.withEndAction {
                            bubbleCard?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(130)?.start()
                        }?.start()
                }

                HudStatus.LOCKED -> {
                    // Counted or active video:
                    container.alpha = 0.90f
                    tvBubbleIcon?.text = "🎬"
                    tvBubbleCount?.text = "$total"
                    tvBubbleLimit?.text = "/$limit"
                    if (!isDetailsExpanded) {
                        tvBubbleStatus?.visibility = View.GONE
                    }
                    setBubbleCardBorder(Color.parseColor("#10B981")) // Emerald border
                }

                HudStatus.TRANSITION -> {
                    container.alpha = 0.95f
                    tvBubbleIcon?.text = "🔄"
                    if (!isDetailsExpanded) {
                        tvBubbleStatus?.visibility = View.GONE
                    }
                    setBubbleCardBorder(Color.parseColor("#F59E0B")) // Amber
                }

                HudStatus.PAUSED -> {
                    // In-app browsing (Home feed, Explore, Profile):
                    // Keep bubble visible in corner with calm, muted border so user always knows their progress
                    container.alpha = 0.75f
                    tvBubbleIcon?.text = "🎬"
                    tvBubbleCount?.text = "$total"
                    tvBubbleLimit?.text = "/$limit"
                    if (!isDetailsExpanded) {
                        tvBubbleStatus?.visibility = View.GONE
                    }
                    setBubbleCardBorder(Color.parseColor("#475569"))
                }
            }
        }
    }

    private fun setBubbleCardBorder(strokeColor: Int) {
        val bg = bubbleCard?.background as? GradientDrawable
        bg?.setStroke(dpToPx(1) + 1, strokeColor)
    }

    private fun ensureFloatingHudCreated() {
        if (hudContainer != null && isHudAttached) {
            syncBubbleCornerGravity()
            return
        }

        try {
            val inflater = LayoutInflater.from(this)
            val root = inflater.inflate(R.layout.view_reel_bubble, null) as FrameLayout

            hudContainer = root
            bubbleCard = root.findViewById(R.id.bubbleCard)
            tvBubbleIcon = root.findViewById(R.id.tvBubbleIcon)
            tvBubbleCount = root.findViewById(R.id.tvBubbleCount)
            tvBubbleLimit = root.findViewById(R.id.tvBubbleLimit)
            tvBubbleStatus = root.findViewById(R.id.tvBubbleStatus)
            tvBubblePlusOne = root.findViewById(R.id.tvBubblePlusOne)

            setupBubbleTouchListener(root)

            val isLeft = prefs.bubbleCorner == PreferencesManager.CORNER_TOP_LEFT
            val gravity = if (isLeft) (Gravity.TOP or Gravity.START) else (Gravity.TOP or Gravity.END)

            // 1. Try TYPE_ACCESSIBILITY_OVERLAY first: works automatically without needing SYSTEM_ALERT_WINDOW!
            var layoutType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

            val wmParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                this.gravity = gravity
                x = dpToPx(12)
                y = dpToPx(prefs.bubbleYOffset.coerceIn(36, 250))
            }

            try {
                windowManager?.addView(root, wmParams)
                hudLayoutParams = wmParams
                isHudAttached = true
            } catch (e: Exception) {
                // 2. If OEM restricts accessibility overlay, fallback to TYPE_APPLICATION_OVERLAY
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    wmParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    wmParams.type = WindowManager.LayoutParams.TYPE_PHONE
                }
                windowManager?.addView(root, wmParams)
                hudLayoutParams = wmParams
                isHudAttached = true
            }
        } catch (e: Exception) {
            isHudAttached = false
        }
    }

    private fun syncBubbleCornerGravity() {
        val params = hudLayoutParams ?: return
        val isLeft = prefs.bubbleCorner == PreferencesManager.CORNER_TOP_LEFT
        val targetGravity = if (isLeft) (Gravity.TOP or Gravity.START) else (Gravity.TOP or Gravity.END)

        if (params.gravity != targetGravity) {
            params.gravity = targetGravity
            params.x = dpToPx(12)
            params.y = dpToPx(prefs.bubbleYOffset.coerceIn(36, 250))
            try {
                windowManager?.updateViewLayout(hudContainer, params)
            } catch (e: Exception) {}
        }
    }

    private fun setupBubbleTouchListener(root: FrameLayout) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        var touchDownTime = 0L

        root.setOnTouchListener { v, event ->
            val wm = windowManager ?: return@setOnTouchListener false
            val params = hudLayoutParams ?: return@setOnTouchListener false

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    touchDownTime = System.currentTimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    if (!isDragging && (Math.abs(deltaX) > dpToPx(6) || Math.abs(deltaY) > dpToPx(6))) {
                        isDragging = true
                        bubbleCard?.animate()?.scaleX(1.08f)?.scaleY(1.08f)?.setDuration(80)?.start()
                    }

                    if (isDragging) {
                        val screenWidth = resources.displayMetrics.widthPixels
                        val screenHeight = resources.displayMetrics.heightPixels

                        if (params.gravity and Gravity.END == Gravity.END) {
                            params.x = (initialX - deltaX).toInt().coerceIn(dpToPx(4), screenWidth - dpToPx(60))
                        } else {
                            params.x = (initialX + deltaX).toInt().coerceIn(dpToPx(4), screenWidth - dpToPx(60))
                        }
                        params.y = (initialY + deltaY).toInt().coerceIn(dpToPx(24), screenHeight - dpToPx(80))

                        try {
                            wm.updateViewLayout(hudContainer, params)
                        } catch (e: Exception) {}
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    bubbleCard?.animate()?.scaleX(1.0f)?.scaleY(1.0f)?.setDuration(100)?.start()
                    val clickDuration = System.currentTimeMillis() - touchDownTime

                    if (!isDragging && clickDuration < 350) {
                        // Quick tap -> toggle expanded breakdown stats!
                        toggleBubbleStats()
                    } else if (isDragging) {
                        // Dock/snap smoothly to nearest corner (Top Left or Top Right)
                        val screenWidth = resources.displayMetrics.widthPixels
                        val snapToLeft = event.rawX < screenWidth / 2
                        snapBubbleToCorner(snapToLeft, params.y)
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun snapBubbleToCorner(snapToLeft: Boolean, targetY: Int) {
        val wm = windowManager ?: return
        val params = hudLayoutParams ?: return

        val newCorner = if (snapToLeft) PreferencesManager.CORNER_TOP_LEFT else PreferencesManager.CORNER_TOP_RIGHT
        prefs.bubbleCorner = newCorner
        prefs.bubbleYOffset = pxToDp(targetY)

        params.gravity = if (snapToLeft) (Gravity.TOP or Gravity.START) else (Gravity.TOP or Gravity.END)
        params.x = dpToPx(12)
        params.y = targetY.coerceIn(dpToPx(36), resources.displayMetrics.heightPixels - dpToPx(100))

        try {
            wm.updateViewLayout(hudContainer, params)
        } catch (e: Exception) {}
    }

    private fun toggleBubbleStats() {
        isDetailsExpanded = !isDetailsExpanded
        mainHandler.removeCallbacks(collapseDetailsRunnable)

        if (isDetailsExpanded) {
            val total = prefs.getTotalWatched()
            val limit = prefs.dailyLimit
            val y = prefs.getYoutubeWatched()
            val i = prefs.getInstagramWatched()
            val s = prefs.getSnapchatWatched()

            tvBubbleIcon?.text = "📊"
            tvBubbleCount?.text = "$total"
            tvBubbleLimit?.text = "/$limit"
            tvBubbleStatus?.visibility = View.VISIBLE
            tvBubbleStatus?.setTextColor(Color.parseColor("#38BDF8"))
            tvBubbleStatus?.text = "▶$y 📷$i 👻$s"

            // Auto-collapse after 3.5 seconds
            mainHandler.postDelayed(collapseDetailsRunnable, 3500L)
        } else {
            tvBubbleStatus?.visibility = View.GONE
            tvBubbleIcon?.text = "🎬"
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
                bubbleCard = null
                tvBubbleIcon = null
                tvBubbleCount = null
                tvBubbleLimit = null
                tvBubbleStatus = null
                tvBubblePlusOne = null
                hudLayoutParams = null
                isHudAttached = false
            }
        }
    }

    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun pxToDp(px: Int): Int {
        val density = resources.displayMetrics.density
        return if (density > 0) (px / density).toInt() else px
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createOneShot(350, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
