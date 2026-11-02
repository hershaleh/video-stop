package com.hershaleh.videostop

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom

/** שמירת קוד ההגנה (מגובב עם מלח, לא בטקסט גלוי). */
object Prefs {
    private fun sp(c: Context) =
        c.applicationContext.getSharedPreferences("videostop", Context.MODE_PRIVATE)

    fun hasPin(c: Context) = sp(c).contains("pin_hash")

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
        sp(c).edit().remove("pin_salt").remove("pin_hash").remove("pin_len").apply()
    }

    fun verify(c: Context, pin: String): Boolean {
        val salt = sp(c).getString("pin_salt", null) ?: return false
        val saved = sp(c).getString("pin_hash", null) ?: return false
        return MessageDigest.isEqual(saved.toByteArray(), hash(salt, pin).toByteArray())
    }

    private fun hash(salt: String, pin: String): String =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
