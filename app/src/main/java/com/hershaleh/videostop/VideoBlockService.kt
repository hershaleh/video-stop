package com.hershaleh.videostop

import android.accessibilityservice.AccessibilityService
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.PixelFormat

/**
 * שירות נגישות שמזהה ניסיון לצפות בוידאו ומציג מסך חסימה מעל כל המסך.
 */
class VideoBlockService : AccessibilityService() {

    private var overlay: View? = null
    private var blockedPackage: String? = null
    private var lastScan = 0L
    private var cooldownUntil = 0L
    private var nodeBudget = 0
    private var focusRequest: AudioFocusRequest? = null

    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in IGNORED_PACKAGES) return

        // אם עברנו לאפליקציה אחרת – מורידים את החסימה
        if (overlay != null) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != blockedPackage) {
                hideOverlay()
            }
            return
        }

        if (SystemClock.uptimeMillis() < cooldownUntil) return

        // אפליקציות שכל מטרתן וידאו – חוסמים מיד
        if (pkg in VIDEO_APPS) {
            showOverlay(pkg)
            return
        }

        // סריקה (מוגבלת בתדירות) של המסך אחר נגן וידאו
        val now = SystemClock.uptimeMillis()
        if (now - lastScan < SCAN_INTERVAL_MS) return
        lastScan = now

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        nodeBudget = MAX_NODES
        if (looksLikeVideo(root, 0)) showOverlay(pkg)
    }

    private fun looksLikeVideo(node: AccessibilityNodeInfo?, depth: Int): Boolean {
        if (node == null || depth > MAX_DEPTH || nodeBudget-- <= 0) return false

        val cls = node.className?.toString().orEmpty()
        if (CLASS_HINTS.any { cls.contains(it, ignoreCase = true) }) return true

        val id = node.viewIdResourceName.orEmpty()
        if (id.isNotEmpty() && ID_HINTS.any { id.contains(it, ignoreCase = true) }) return true

        val label = buildString {
            node.contentDescription?.let { append(it).append(' ') }
            node.text?.takeIf { it.length <= 40 }?.let { append(it) }
        }
        if (label.isNotBlank() && LABEL_HINTS.any { label.contains(it, ignoreCase = true) }) return true

        for (i in 0 until node.childCount) {
            if (looksLikeVideo(node.getChild(i), depth + 1)) return true
        }
        return false
    }

    private fun showOverlay(pkg: String) {
        if (overlay != null) return
        val themed = ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_NoActionBar)
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_block, null)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { dismissToHome() }
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, e ->
            if (keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) {
                dismissToHome(); true
            } else false
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager.addView(view, params)
            view.requestFocus()
            overlay = view
            blockedPackage = pkg
            pauseMedia()
        } catch (_: Exception) {
            overlay = null
        }
    }

    private fun dismissToHome() {
        cooldownUntil = SystemClock.uptimeMillis() + COOLDOWN_MS
        hideOverlay()
        performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun hideOverlay() {
        overlay?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        overlay = null
        blockedPackage = null
        releaseMedia()
    }

    /** מבקש פוקוס שמע כדי שרוב הנגנים יושהו מאחורי מסך החסימה. */
    private fun pauseMedia() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { }
            .build()
        focusRequest = req
        audioManager.requestAudioFocus(req)
    }

    private fun releaseMedia() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    override fun onInterrupt() = hideOverlay()

    override fun onDestroy() {
        hideOverlay()
        super.onDestroy()
    }

    private companion object {
        const val SCAN_INTERVAL_MS = 400L
        const val COOLDOWN_MS = 1500L
        const val MAX_DEPTH = 40
        const val MAX_NODES = 1500

        val IGNORED_PACKAGES = setOf("com.android.systemui")

        /** אפליקציות שמיועדות בעיקר לצפייה בוידאו. */
        val VIDEO_APPS = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "app.revanced.android.youtube",
            "com.netflix.mediaclient",
            "com.zhiliaoapp.musically",
            "com.ss.android.ugc.trill",
            "com.twitch.android.app",
            "com.disney.disneyplus",
            "com.amazon.avod.thirdpartyclient",
            "com.hbo.hbonow",
            "com.apple.atve.androidtv.appletv",
            "org.videolan.vlc",
            "com.mxtech.videoplayer.ad",
            "com.mxtech.videoplayer.pro",
            "com.google.android.apps.youtube.kids",
            "tv.twitch.android.app",
            "com.vimeo.android.videoapp",
            "com.dailymotion.dailymotion",
            "com.google.android.videos"
        )

        val CLASS_HINTS = listOf("VideoView", "PlayerView", "ExoPlayer", "YouTubePlayer", "JWPlayer")

        val ID_HINTS = listOf(
            "exo_play", "exo_pause", "exo_progress", "video_view", "videoview",
            "player_view", "playerview", "video_player", "videoplayer"
        )

        val LABEL_HINTS = listOf(
            "play video", "pause video", "video player", "fullscreen video",
            "הפעל וידאו", "השהה וידאו", "נגן וידאו", "הפעל סרטון", "השהה סרטון", "נגן סרטון"
        )
    }
}
