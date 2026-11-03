package com.hershaleh.videostop

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager

/**
 * שירות נגישות שמזהה ניסיון לצפות בוידאו ומציג מסך חסימה.
 *
 * מנגנוני זיהוי:
 *  1. רשימת אפליקציות וידאו.
 *  2. סריקת רכיבי המסך (נגנים, מזהים, כיתובים).
 *  3. זיהוי תנועה אוטומטית בצילומי מסך (לכל חלון בנפרד במסך מפוצל).
 *  4. כתובת האתר בדפדפנים (חסימה לפי דומיין).
 *  5. חלון תמונה-בתוך-תמונה (מכוסה במקום).
 * בנוסף: זיכרון חסימות, וקוד הגנה על כיבוי/הסרה.
 */
class VideoBlockService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val windowManager by lazy { getSystemService(WINDOW_SERVICE) as WindowManager }
    private val audioManager by lazy { getSystemService(AUDIO_SERVICE) as AudioManager }
    private val power by lazy { getSystemService(POWER_SERVICE) as PowerManager }
    private val themed by lazy { ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_NoActionBar) }
    private val imePackages by lazy {
        try {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .enabledInputMethodList.map { it.packageName }.toSet()
        } catch (_: Exception) {
            emptySet<String>()
        }
    }

    // חלונות מעל המסך
    private var overlay: View? = null
    private var blockedPackage: String? = null
    private var pinOverlay: View? = null
    private var pipOverlay: View? = null
    private var pipParams: WindowManager.LayoutParams? = null
    private var focusRequest: AudioFocusRequest? = null

    // מצב
    private var currentPkg: String? = null
    private var currentDomain: String? = null
    private var homePkg: String? = null
    private var lastScan = 0L
    private var lastGuardScan = 0L
    private var cooldownUntil = 0L
    private var pinGraceUntil = 0L
    private var lastInteraction = 0L
    private var touching = false
    private var touchScrolled = false
    private var nodeBudget = 0
    private var grabbing = false
    private var uninstallScreen = false
    private var grabStart = 0L

    private val detectors = HashMap<String, MotionDetector>()
    private val blockMemory = HashMap<String, Long>()
    private val exemptCache = HashMap<String, Boolean>()

    private class Target(val key: String, val pkg: String, val bounds: Rect?)

    private val tick = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, MOTION_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        homePkg = packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    // ───────────────────────── אירועים ─────────────────────────

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val now = SystemClock.uptimeMillis()
        val type = event.eventType
        trackInteraction(type, now)

        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in IGNORED_PACKAGES || pkg in imePackages) return

        // הגנה על הגדרות הנגישות / הסרת האפליקציה
        if (pkg in PROTECTED_PACKAGES) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                currentPkg = pkg
                if (pkg in INSTALLER_PACKAGES) {
                    uninstallScreen =
                        event.className?.toString()?.contains("uninstall", ignoreCase = true) == true
                }
            }
            // התקנה ועדכון חופשיים; ממסכי ההתקנה רק מסך ההסרה מוגן
            if (pkg !in INSTALLER_PACKAGES || uninstallScreen) guardSettings(pkg, now)
            return
        }

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentPkg = pkg
            currentDomain = null
            uninstallScreen = false
            resetDetectors()
            if (pinOverlay != null) hidePin()
        }

        // מנהלי אפליקציות של היצרן, חנות האפליקציות וכד'
        if (pkg != homePkg && !BROWSER_URL_IDS.containsKey(pkg) && isSystemPackage(pkg)) {
            guardSettings(pkg, now, requireAction = true)
        }

        if (overlay != null) {
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != blockedPackage) {
                hideOverlay()
            } else {
                return
            }
        }
        if (now < cooldownUntil || pkg == homePkg) return

        // חזרה לאפליקציה שנחסמה לאחרונה
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            !BROWSER_URL_IDS.containsKey(pkg) && recentlyBlocked("pkg:$pkg", now)
        ) {
            showOverlay(pkg, remember = false)
            return
        }

        // אפליקציות שכל מטרתן וידאו
        if (pkg in VIDEO_APPS) {
            showOverlay(pkg)
            return
        }

        if (now - lastScan < SCAN_INTERVAL_MS) return
        lastScan = now

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return

        // דפדפן: חסימה לפי כתובת
        if (BROWSER_URL_IDS.containsKey(pkg)) {
            val parsed = urlBarText(root, pkg)?.let { parseUrl(it) }
            if (parsed != null) {
                val (host, path) = parsed
                currentDomain = host
                if (isBlockedUrl(host, path)) {
                    showOverlay(pkg)
                    return
                }
                if (recentlyBlocked("dom:$host", now)) {
                    showOverlay(pkg, remember = false)
                    return
                }
            }
        }

        nodeBudget = MAX_NODES
        if (looksLikeVideo(root, 0)) showOverlay(pkg)
    }

    /** נגיעה/גלילה של המשתמש אינה "תנועה אוטומטית". נגיעה בלי גלילה (למשל סרגל התקדמות) לא מדכאת. */
    private fun trackInteraction(type: Int, now: Long) {
        when (type) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START -> {
                touching = true
                touchScrolled = false
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                lastInteraction = now
                if (touching) touchScrolled = true
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> lastInteraction = now
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END -> {
                touching = false
                if (touchScrolled) lastInteraction = now
            }
        }
    }

    // ───────────────────────── זיהוי לפי רכיבים ─────────────────────────

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

    // ───────────────────────── דפדפנים ─────────────────────────

    private fun urlBarText(root: AccessibilityNodeInfo, pkg: String): String? {
        val names = BROWSER_URL_IDS[pkg] ?: return null
        for (n in names) {
            val nodes = root.findAccessibilityNodeInfosByViewId("$pkg:id/$n")
            val t = nodes?.firstOrNull()?.text?.toString()
            if (!t.isNullOrBlank()) return t
        }
        return null
    }

    /** מחזיר (דומיין, נתיב) או null אם זה לא נראה כמו כתובת. */
    private fun parseUrl(raw: String): Pair<String, String>? {
        val s = raw.trim().lowercase()
        if (s.isEmpty() || s.any { it.isWhitespace() }) return null
        val rest = s.substringAfter("://")
        val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
        var host = (if (cut < 0) rest else rest.substring(0, cut)).substringBefore(':')
        val path = if (cut < 0) "" else rest.substring(cut)
        for (p in listOf("www.", "m.", "mobile.")) host = host.removePrefix(p)
        if (!host.contains('.')) return null
        return host to path
    }

    private fun isBlockedUrl(host: String, path: String): Boolean {
        fun sameSite(d: String) = host == d || host.endsWith(".$d")
        return BLOCKED_DOMAINS.any { sameSite(it) } ||
            BLOCKED_PATHS.any { (d, p) -> sameSite(d) && path.startsWith(p) }
    }

    // ───────────────────────── זיכרון חסימות ─────────────────────────

    private fun rememberBlock(pkg: String) {
        val now = SystemClock.uptimeMillis()
        if (BROWSER_URL_IDS.containsKey(pkg)) {
            currentDomain?.let { blockMemory["dom:$it"] = now + REMEMBER_DOMAIN_MS }
        } else {
            blockMemory["pkg:$pkg"] = now + REMEMBER_APP_MS
        }
    }

    private fun recentlyBlocked(key: String, now: Long) = (blockMemory[key] ?: 0L) > now

    // ───────────────────────── מסך חסימה ─────────────────────────

    private fun fullScreenParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT
    )

    private fun showOverlay(pkg: String, remember: Boolean = true) {
        if (overlay != null) return
        val view = LayoutInflater.from(themed).inflate(R.layout.overlay_block, null)

        view.findViewById<View>(R.id.btnBack).setOnClickListener { dismissToHome() }
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, keyCode, e ->
            if (keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) {
                dismissToHome(); true
            } else false
        }

        try {
            windowManager.addView(view, fullScreenParams())
            view.requestFocus()
            overlay = view
            blockedPackage = pkg
            if (remember) rememberBlock(pkg)
            updateAudio()
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
        overlay?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        overlay = null
        blockedPackage = null
        updateAudio()
    }

    /** מחזיק פוקוס שמע כל עוד משהו מכוסה, כדי שרוב הנגנים יושהו. */
    private fun updateAudio() {
        val need = overlay != null || pipOverlay != null
        if (need && focusRequest == null) {
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
        } else if (!need && focusRequest != null) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    // ───────────────────────── קוד הגנה ─────────────────────────

    /**
     * מגן על כיבוי/הסרה. במסכי ההגדרות הידועים מספיק שמופיע שם האפליקציה;
     * במסכי ניהול אחרים של המערכת/היצרן (requireAction) נדרשת גם מילת פעולה
     * כמו "הסר" או "עצור".
     */
    private fun guardSettings(pkg: String, now: Long, requireAction: Boolean = false) {
        if (!Prefs.hasPin(this) || pinOverlay != null || now < pinGraceUntil) return
        if (now - lastGuardScan < GUARD_INTERVAL_MS) return
        lastGuardScan = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        nodeBudget = MAX_NODES
        val found = BooleanArray(2) // [0] שם האפליקציה, [1] מילת פעולה
        scanForGuard(root, 0, getString(R.string.app_name), found)
        if (found[0] && (!requireAction || found[1])) showPin()
    }

    private fun scanForGuard(node: AccessibilityNodeInfo?, depth: Int, name: String, found: BooleanArray) {
        if (node == null || depth > MAX_DEPTH || nodeBudget-- <= 0) return
        for (t in arrayOf(node.text, node.contentDescription)) {
            if (t == null) continue
            if (t.contains(name, ignoreCase = true)) found[0] = true
            if (ACTION_WORDS.any { t.contains(it, ignoreCase = true) }) found[1] = true
        }
        if (found[0] && found[1]) return
        for (i in 0 until node.childCount) {
            scanForGuard(node.getChild(i), depth + 1, name, found)
            if (found[0] && found[1]) return
        }
    }

    private val systemCache = HashMap<String, Boolean>()

    private fun isSystemPackage(pkg: String): Boolean = systemCache.getOrPut(pkg) {
        try {
            val f = packageManager.getApplicationInfo(pkg, 0).flags
            (f and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: Exception) {
            false
        }
    }

    private fun showPin() {
        if (pinOverlay != null) return
        val pad = PinPad(
            themed,
            onSuccess = {
                pinGraceUntil = SystemClock.uptimeMillis() + PIN_GRACE_MS
                hidePin()
            },
            onCancel = {
                hidePin()
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        )
        try {
            windowManager.addView(pad.view, fullScreenParams())
            pad.view.requestFocus()
            pinOverlay = pad.view
        } catch (_: Exception) {
            pinOverlay = null
        }
    }

    private fun hidePin() {
        pinOverlay?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        pinOverlay = null
    }

    // ───────────────────────── מחזור תקופתי ─────────────────────────

    private fun onTick() {
        if (!power.isInteractive) {
            resetDetectors()
            hidePip()
            return
        }
        updatePip()
        motionScan(SystemClock.uptimeMillis())
    }

    private fun resetDetectors() = detectors.values.forEach { it.reset() }

    /** תמונה-בתוך-תמונה: מכסים את החלון הצף בלבד (נוגעים דרכו, כדי שאפשר יהיה להזיז/לסגור אותו). */
    private fun updatePip() {
        val wins = try { windows } catch (_: Exception) { null }
        val pip = wins?.firstOrNull { it.isInPictureInPictureMode }
        var rect: Rect? = null
        if (pip != null) {
            val pkg = pip.root?.packageName?.toString()
            if (pkg == null || !isMotionExempt(pkg)) {
                val r = Rect()
                pip.getBoundsInScreen(r)
                if (!r.isEmpty) rect = r
            }
        }
        if (rect == null) {
            hidePip()
            return
        }

        val lp = pipParams ?: WindowManager.LayoutParams(
            rect.width(), rect.height(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        pipParams = lp
        lp.x = rect.left
        lp.y = rect.top
        lp.width = rect.width()
        lp.height = rect.height()

        try {
            val v = pipOverlay
            if (v == null) {
                val view = LayoutInflater.from(themed).inflate(R.layout.overlay_pip, null)
                windowManager.addView(view, lp)
                pipOverlay = view
                updateAudio()
            } else {
                windowManager.updateViewLayout(v, lp)
            }
        } catch (_: Exception) {
        }
    }

    private fun hidePip() {
        if (pipOverlay == null) return
        pipOverlay?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
        pipOverlay = null
        pipParams = null
        updateAudio()
    }

    // ───────────────────────── זיהוי תנועה ─────────────────────────

    private fun eligible(pkg: String) =
        pkg != packageName && pkg !in IGNORED_PACKAGES && pkg !in PROTECTED_PACKAGES &&
            pkg != homePkg && !isMotionExempt(pkg)

    /** חלונות שנבדקים: חלון לכל אפליקציה במסך מפוצל, אחרת האפליקציה בחזית. */
    private fun collectTargets(): List<Target> {
        val wins = try { windows } catch (_: Exception) { null }
        val apps = wins?.filter {
            it.type == AccessibilityWindowInfo.TYPE_APPLICATION && !it.isInPictureInPictureMode
        } ?: emptyList()

        if (apps.size >= 2) {
            return apps.mapNotNull { w ->
                val pkg = w.root?.packageName?.toString() ?: return@mapNotNull null
                if (!eligible(pkg)) return@mapNotNull null
                val r = Rect()
                w.getBoundsInScreen(r)
                if (r.isEmpty) null else Target("w${w.id}", pkg, r)
            }
        }
        val pkg = currentPkg ?: return emptyList()
        return if (eligible(pkg)) listOf(Target("main", pkg, null)) else emptyList()
    }

    private fun motionScan(now: Long) {
        if (overlay != null || now < cooldownUntil || pinOverlay != null) {
            resetDetectors()
            return
        }
        if (now - lastInteraction < QUIET_MS) {
            resetDetectors()
            return
        }
        if (grabbing && now - grabStart < 3000) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R && ProjectionHolder.grabber == null) return

        val targets = collectTargets()
        if (targets.isEmpty()) {
            resetDetectors()
            return
        }

        grabbing = true
        grabStart = now
        grabFrame { bmp ->
            grabbing = false
            if (bmp != null) {
                try {
                    analyzeFrame(bmp, targets)
                } finally {
                    bmp.recycle()
                }
            }
        }
    }

    private fun analyzeFrame(bmp: Bitmap, targets: List<Target>) {
        detectors.keys.retainAll(targets.map { it.key }.toSet())
        val (sw, sh) = screenSize()
        val sx = bmp.width.toFloat() / sw
        val sy = bmp.height.toFloat() / sh

        var hit: String? = null
        for (t in targets) {
            val det = detectors.getOrPut(t.key) { MotionDetector() }
            val b = t.bounds
            val sub = if (b == null) bmp else crop(bmp, b, sx, sy)
            val video = sub != null && det.analyze(sub)
            if (sub != null && sub !== bmp) sub.recycle()
            if (video && hit == null) hit = t.pkg
        }
        if (hit != null && overlay == null) showOverlay(hit)
    }

    private fun crop(src: Bitmap, r: Rect, sx: Float, sy: Float): Bitmap? {
        val l = (r.left * sx).toInt().coerceIn(0, src.width - 1)
        val t = (r.top * sy).toInt().coerceIn(0, src.height - 1)
        val rr = (r.right * sx).toInt().coerceIn(l + 1, src.width)
        val bb = (r.bottom * sy).toInt().coerceIn(t + 1, src.height)
        return try { Bitmap.createBitmap(src, l, t, rr - l, bb - t) } catch (_: Exception) { null }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val m = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(m)
        return m.widthPixels.coerceAtLeast(1) to m.heightPixels.coerceAtLeast(1)
    }

    /** אנדרואיד 11+: צילום דרך שירות הנגישות. אנדרואיד 8–10: דרך MediaProjection (אם הופעל באפליקציה). */
    private fun grabFrame(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(
                Display.DEFAULT_DISPLAY, mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        var soft: Bitmap? = null
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                            if (hw != null) {
                                soft = hw.copy(Bitmap.Config.ARGB_8888, false)
                                hw.recycle()
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { result.hardwareBuffer.close() } catch (_: Exception) {}
                        }
                        cb(soft)
                    }

                    override fun onFailure(errorCode: Int) = cb(null) // למשל חלון מוגן
                }
            )
        } else {
            cb(ProjectionHolder.grabber?.grab())
        }
    }

    /** אפליקציות שבהן תנועה במסך היא לגיטימית: מצלמה, שיחות וידאו, ניווט ומשחקים. */
    private fun isMotionExempt(pkg: String): Boolean = exemptCache.getOrPut(pkg) {
        if (pkg in MOTION_EXEMPT_APPS) return@getOrPut true
        if (EXEMPT_NAME_HINTS.any { pkg.contains(it, ignoreCase = true) }) return@getOrPut true
        try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            @Suppress("DEPRECATION")
            info.category == ApplicationInfo.CATEGORY_GAME ||
                (info.flags and ApplicationInfo.FLAG_IS_GAME) != 0
        } catch (_: Exception) {
            false
        }
    }

    // ───────────────────────── מחזור חיים ─────────────────────────

    override fun onInterrupt() {
        hideOverlay()
        hidePip()
        hidePin()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        hideOverlay()
        hidePip()
        hidePin()
        super.onDestroy()
    }

    private companion object {
        const val MOTION_INTERVAL_MS = 350L // המערכת לא מאפשרת צילום בתדירות גבוהה מ~333ms
        const val QUIET_MS = 1200L
        const val SCAN_INTERVAL_MS = 400L
        const val GUARD_INTERVAL_MS = 250L
        const val COOLDOWN_MS = 1500L
        const val MAX_DEPTH = 40
        const val MAX_NODES = 1500
        const val REMEMBER_APP_MS = 60_000L
        const val REMEMBER_DOMAIN_MS = 600_000L
        const val PIN_GRACE_MS = 90_000L

        val IGNORED_PACKAGES = setOf("com.android.systemui")

        /** מילים שמצביעות על פעולת הסרה/כיבוי (עברית ואנגלית). */
        val ACTION_WORDS = listOf(
            "uninstall", "force stop", "disable", "deactivate", "clear data", "clear storage", "turn off",
            "הסר", "עצור", "השבת", "בטל", "נקה", "כבה", "מחק"
        )

        /** מסכים שמהם אפשר לכבות או להסיר את האפליקציה – מוגנים בקוד (אם הוגדר). */
        val PROTECTED_PACKAGES = setOf(
            "com.android.settings",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.google.android.permissioncontroller"
        )

        /** מסכי התקנה/הסרה של המערכת: מוגנים רק כשזה מסך הסרה. */
        val INSTALLER_PACKAGES = setOf(
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.samsung.android.packageinstaller",
            "com.google.android.permissioncontroller"
        )

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

        /** דפדפנים, ומזהי שורת הכתובת שלהם. */
        val BROWSER_URL_IDS: Map<String, List<String>> = mapOf(
            "com.android.chrome" to listOf("url_bar"),
            "com.chrome.beta" to listOf("url_bar"),
            "com.chrome.dev" to listOf("url_bar"),
            "com.chrome.canary" to listOf("url_bar"),
            "org.chromium.chrome" to listOf("url_bar"),
            "com.brave.browser" to listOf("url_bar"),
            "com.microsoft.emmx" to listOf("url_bar"),
            "com.vivaldi.browser" to listOf("url_bar"),
            "com.kiwibrowser.browser" to listOf("url_bar"),
            "org.mozilla.firefox" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            "org.mozilla.firefox_beta" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            "org.mozilla.fenix" to listOf("mozac_browser_toolbar_url_view", "url_bar_title"),
            "com.sec.android.app.sbrowser" to listOf("location_bar_edit_text"),
            "com.opera.browser" to listOf("url_field"),
            "com.opera.browser.beta" to listOf("url_field"),
            "com.duckduckgo.mobile.android" to listOf("omnibarTextInput")
        )

        /** אתרי וידאו חסומים (כולל תת-דומיינים). */
        val BLOCKED_DOMAINS = listOf(
            "youtube.com", "youtu.be", "youtube-nocookie.com", "vimeo.com", "tiktok.com",
            "twitch.tv", "dailymotion.com", "netflix.com", "rumble.com", "bitchute.com",
            "odysee.com", "disneyplus.com", "primevideo.com", "hulu.com", "max.com", "hbomax.com"
        )

        /** נתיבים חסומים בתוך אתרים אחרים (עובד כשהדפדפן מציג את הכתובת המלאה). */
        val BLOCKED_PATHS = listOf(
            "facebook.com" to "/watch",
            "facebook.com" to "/reel",
            "instagram.com" to "/reel",
            "instagram.com" to "/tv"
        )

        /** פטורות מזיהוי תנועה בלבד (שאר המנגנונים ממשיכים לפעול). */
        val MOTION_EXEMPT_APPS = setOf(
            // מצלמה
            "com.android.camera", "com.android.camera2", "com.google.android.GoogleCamera",
            "com.sec.android.app.camera", "com.motorola.camera3", "com.oneplus.camera",
            "com.miui.camera", "com.huawei.camera", "com.oppo.camera",
            // שיחות וידאו
            "us.zoom.videomeetings", "com.google.android.apps.tachyon",
            "com.microsoft.teams", "com.skype.raider", "com.cisco.webex.meetings",
            "org.jitsi.meet",
            // ניווט
            "com.google.android.apps.maps", "com.waze"
        )

        val EXEMPT_NAME_HINTS = listOf("camera")

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
