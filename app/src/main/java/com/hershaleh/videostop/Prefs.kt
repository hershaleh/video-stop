package com.hershaleh.videostop

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import kotlin.math.max
import java.security.MessageDigest
import java.security.SecureRandom

/** שמירת קוד ההגנה (מגובב עם מלח, לא בטקסט גלוי). */
object Prefs {
    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("videostop", Context.MODE_PRIVATE)

    /** זמן ההמתנה לאיפוס קוד שנשכח. */
    const val RESET_DELAY_MS = 60L * 60 * 1000

    // ---- רשימות אפליקציות ----
    const val MODE_ALL = 0     // חסימה בכל האפליקציות
    const val MODE_EXCEPT = 1  // כל האפליקציות חוץ מהמסומנות (רשימה לבנה)
    const val MODE_ONLY = 2    // רק האפליקציות המסומנות (רשימה שחורה)

    fun listMode(c: Context) = sp(c).getInt("list_mode", MODE_ALL)

    fun setListMode(c: Context, mode: Int) {
        sp(c).edit().putInt("list_mode", mode).apply()
    }

    fun listedApps(c: Context): Set<String> = sp(c).getStringSet("list_pkgs", null) ?: emptySet()

    fun setListedApps(c: Context, pkgs: Set<String>) {
        sp(c).edit().putStringSet("list_pkgs", HashSet(pkgs)).apply()
    }

    /** האם הזיהוי והחסימה פעילים באפליקציה הזו, לפי המצב והרשימה. */
    fun blockApplies(c: Context, pkg: String): Boolean = when (listMode(c)) {
        MODE_EXCEPT -> pkg !in listedApps(c)
        MODE_ONLY -> pkg in listedApps(c)
        else -> true
    }

    fun hasPin(c: Context): Boolean {
        expireResetIfDue(c)
        return sp(c).contains("pin_hash")
    }

    private fun bootCount(c: Context) = try {
        Settings.Global.getInt(c.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }

    /** "שכחתי קוד": מתחיל ספירה לאחור. בתום ההמתנה הקוד מוסר. */
    fun requestReset(c: Context) {
        if (sp(c).getLong("reset_wall", 0L) > 0L) return
        sp(c).edit()
            .putLong("reset_wall", System.currentTimeMillis())
            .putLong("reset_elapsed", SystemClock.elapsedRealtime())
            .putInt("reset_boot", bootCount(c))
            .apply()
    }

    /** כמה זמן נשאר עד האיפוס (0 אם אין איפוס ממתין או שהזמן עבר). */
    fun resetRemainingMs(c: Context): Long {
        val wall = sp(c).getLong("reset_wall", 0L)
        if (wall <= 0L) return 0L
        // שעון מונוטוני אינו ניתן לעקיפה בשינוי התאריך; אחרי אתחול נשען על שעון המכשיר
        var elapsed = if (sp(c).getInt("reset_boot", -2) == bootCount(c))
            SystemClock.elapsedRealtime() - sp(c).getLong("reset_elapsed", 0L)
        else -1L
        if (elapsed < 0L) elapsed = System.currentTimeMillis() - wall
        return max(0L, RESET_DELAY_MS - elapsed)
    }

    private fun expireResetIfDue(c: Context) {
        if (sp(c).getLong("reset_wall", 0L) > 0L && resetRemainingMs(c) == 0L) clearPin(c)
    }

    fun pinLength(c: Context) = sp(c).getInt("pin_len", 0)

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        sp(c).edit()
            .putString("pin_salt", salt)
            .putString("pin_hash", hash(salt, pin))
            .putInt("pin_len", pin.length)
            .apply()
    }

    fun clearPin(c: Context) {
        sp(c).edit().remove("pin_salt").remove("pin_hash").remove("pin_len")
            .remove("reset_wall").remove("reset_elapsed").remove("reset_boot").apply()
    }

    fun verify(c: Context, pin: String): Boolean {
        val salt = sp(c).getString("pin_salt", null) ?: return false
        val saved = sp(c).getString("pin_hash", null) ?: return false
        val ok = MessageDigest.isEqual(saved.toByteArray(), hash(salt, pin).toByteArray())
        if (ok) sp(c).edit().remove("reset_wall").remove("reset_elapsed").remove("reset_boot").apply()
        return ok
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
