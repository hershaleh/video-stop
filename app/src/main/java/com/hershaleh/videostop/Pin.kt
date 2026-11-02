package com.hershaleh.videostop

import android.content.Context
import java.security.MessageDigest
import java.util.UUID

/** קוד הגנה אופציונלי: נדרש כדי להיכנס להגדרות הנגישות / להסרת האפליקציה. נשמר כ-hash בלבד. */
object Pin {
    private const val PREFS = "pin"
    private const val KEY_SALT = "salt"
    private const val KEY_HASH = "hash"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSet(c: Context): Boolean = prefs(c).contains(KEY_HASH)

    fun set(c: Context, pin: String) {
        val salt = UUID.randomUUID().toString()
        prefs(c).edit().putString(KEY_SALT, salt).putString(KEY_HASH, hash(salt, pin)).apply()
    }

    fun verify(c: Context, pin: String): Boolean {
        val p = prefs(c)
        val salt = p.getString(KEY_SALT, null) ?: return false
        val saved = p.getString(KEY_HASH, null) ?: return false
        return MessageDigest.isEqual(hash(salt, pin).toByteArray(), saved.toByteArray())
    }

    fun clear(c: Context) {
        prefs(c).edit().clear().apply()
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((salt + pin).toByteArray())
            .joinToString("") { "%02x".format(it) }
}
