package com.hershaleh.videostop

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/** מסך הזנת קוד מלא, בעיצוב זהה למסך החסימה. נבנה בקוד כדי להישאר קטן. */
class PinPad(
    private val ctx: Context,
    private val onSuccess: () -> Unit,
    private val onCancel: () -> Unit
) {
    private val typed = StringBuilder()
    private var wrong = 0

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()

    private val dots = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        textSize = 28f
        gravity = Gravity.CENTER
    }

    private val msg = TextView(ctx).apply {
        setTextColor(0xCCFFFFFF.toInt())
        textSize = 15f
        gravity = Gravity.CENTER
        setText(R.string.pin_subtitle)
    }

    val view: View = FrameLayout(ctx).apply {
        setBackgroundResource(R.drawable.bg_gradient)
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        setOnKeyListener { _, keyCode, e ->
            if (keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP) {
                onCancel(); true
            } else false
        }

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        col.addView(TextView(ctx).apply {
            setText(R.string.pin_title)
            setTextColor(Color.WHITE)
            textSize = 24f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        col.addView(msg, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        col.addView(dots, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(28); bottomMargin = dp(20) })

        val grid = GridLayout(ctx).apply { columnCount = 3 }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "CANCEL", "0", "DEL")
        for (k in keys) {
            val b = Button(ctx).apply {
                text = when (k) {
                    "CANCEL" -> ctx.getString(R.string.pin_cancel)
                    "DEL" -> "⌫"
                    else -> k
                }
                textSize = if (k == "CANCEL") 13f else 24f
                setTextColor(Color.WHITE)
                isAllCaps = false
                stateListAnimator = null
                setBackgroundResource(R.drawable.bg_circle)
                setOnClickListener {
                    when (k) {
                        "CANCEL" -> onCancel()
                        "DEL" -> { if (typed.isNotEmpty()) typed.deleteCharAt(typed.length - 1); refresh() }
                        else -> press(k)
                    }
                }
            }
            grid.addView(b, GridLayout.LayoutParams().apply {
                width = dp(76); height = dp(76)
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
        }
        col.addView(grid)

        addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER
        ))
        refresh()
    }

    private fun press(d: String) {
        if (typed.length >= 8) return
        typed.append(d)
        refresh()
        if (typed.length == Prefs.pinLength(ctx)) {
            if (Prefs.verify(ctx, typed.toString())) {
                onSuccess()
            } else {
                wrong++
                typed.clear()
                refresh()
                msg.setText(R.string.pin_wrong)
                if (wrong >= 5) onCancel()
            }
        }
    }

    private fun refresh() {
        val total = max(Prefs.pinLength(ctx), typed.length)
        dots.text = (0 until total).joinToString(" ") { if (it < typed.length) "●" else "○" }
    }
}
