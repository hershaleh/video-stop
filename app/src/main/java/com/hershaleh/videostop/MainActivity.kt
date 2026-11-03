package com.hershaleh.videostop

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var chip: TextView
    private lateinit var desc: TextView
    private lateinit var btnPin: Button
    private lateinit var btnBattery: Button
    private lateinit var btnProjection: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        chip = findViewById(R.id.statusChip)
        desc = findViewById(R.id.statusDesc)
        btnPin = findViewById(R.id.btnPin)
        btnBattery = findViewById(R.id.btnBattery)
        btnProjection = findViewById(R.id.btnProjection)

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnPin.setOnClickListener { onPinClicked() }
        btnBattery.setOnClickListener { requestBatteryExemption() }
        btnProjection.setOnClickListener { onProjectionClicked() }
        btnProjection.visibility = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) View.VISIBLE else View.GONE
    }

    override fun onResume() {
        super.onResume()
        refreshUi()
    }

    private fun refreshUi() {
        val on = isServiceEnabled()
        chip.setText(if (on) R.string.status_on else R.string.status_off)
        chip.setBackgroundResource(if (on) R.drawable.bg_chip_on else R.drawable.bg_chip_off)
        desc.setText(if (on) R.string.status_on_desc else R.string.status_off_desc)
        btnPin.setText(if (Prefs.hasPin(this)) R.string.btn_pin_manage else R.string.btn_pin_set)
        btnBattery.setText(if (ignoringBattery()) R.string.btn_battery_ok else R.string.btn_battery)
        btnProjection.setText(
            if (ProjectionHolder.grabber != null) R.string.btn_projection_on else R.string.btn_projection_off
        )
    }

    private fun isServiceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(this, VideoBlockService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    // ---- קוד הגנה ----

    private fun askPin(title: Int, withForgot: Boolean = false, onOk: (String) -> Unit) {
        val et = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(InputFilter.LengthFilter(8))
            gravity = Gravity.CENTER
            textSize = 24f
        }
        val b = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(et)
            .setPositiveButton(R.string.dlg_ok) { _, _ -> onOk(et.text.toString()) }
            .setNegativeButton(R.string.dlg_cancel, null)
        if (withForgot) b.setNeutralButton(R.string.pin_forgot) { _, _ -> forgotPin() }
        b.show()
    }

    private fun forgotPin() {
        Prefs.requestReset(this)
        val min = ((Prefs.resetRemainingMs(this) + 59_999L) / 60_000L).toInt()
        Toast.makeText(this, getString(R.string.pin_reset_pending, min), Toast.LENGTH_LONG).show()
        refreshUi()
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun onPinClicked() {
        if (!Prefs.hasPin(this)) {
            newPin()
            return
        }
        askPin(R.string.pin_enter_current, withForgot = true) { current ->
            if (!Prefs.verify(this, current)) {
                toast(R.string.pin_wrong)
                return@askPin
            }
            AlertDialog.Builder(this)
                .setItems(arrayOf(getString(R.string.pin_change), getString(R.string.pin_remove))) { _, which ->
                    if (which == 0) {
                        newPin()
                    } else {
                        Prefs.clearPin(this)
                        toast(R.string.pin_removed)
                        refreshUi()
                    }
                }
                .show()
        }
    }

    private fun newPin() {
        askPin(R.string.pin_new) { pin ->
            if (pin.length !in 4..8) {
                toast(R.string.pin_len_error)
            } else {
                Prefs.setPin(this, pin)
                toast(R.string.pin_saved)
                refreshUi()
            }
        }
    }

    // ---- סוללה ----

    private fun ignoringBattery() =
        (getSystemService(POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun requestBatteryExemption() {
        if (ignoringBattery()) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---- צילום מסך לאנדרואיד 8–10 ----

    private fun onProjectionClicked() {
        if (ProjectionHolder.grabber != null) {
            stopService(Intent(this, ProjectionService::class.java))
            btnProjection.postDelayed({ refreshUi() }, 400)
        } else {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == RESULT_OK && data != null) {
            val i = Intent(this, ProjectionService::class.java)
                .putExtra("code", resultCode)
                .putExtra("data", data)
            startForegroundService(i)
            btnProjection.postDelayed({ refreshUi() }, 700)
        }
    }

    private companion object {
        const val REQ_PROJECTION = 42
    }
}
