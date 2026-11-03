package com.hershaleh.videostop

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * מסך הזנת קוד מלא, בעיצוב זהה למסך החסימה. נבנה בקוד כדי להישאר קטן.
 * גודל המקשים מותאם לגובה המסך, הטקסט אינו תלוי בגודל הגופן של המערכת
 * (כדי שהספרות לא ייחתכו), והכול גולל אם עדיין אין מספיק מקום.
 */
class PinPad(
    private val ctx: Context,
    private val onSuccess: () -> Unit,
    private val onCancel: () -> Unit
) {
    private val typed = StringBuilder()
    private var wrong = 0

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    /** גודל מקש (dp): עד 76, וקטן יותר במסכים נמוכים. */
    private val keyDp: Float = run {
        val heightDp = ctx.resources.displayMetrics.heightPixels / density
        min(76f, max(46f, (heightDp - 250f) / 4f - 16f))
    }
    private val small = keyDp < 64f

    private fun TextView.dipText(size: Float) = setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)

    private val dots = TextView(ctx).apply {
        setTextColor(Color.WHITE)
        dipText(if (small) 22f else 28f)
        gravity = Gravity.CENTER
        includeFontPadding = false
    }

    private val msg = TextView(ctx).apply {
        setTextColor(0xCCFFFFFF.toInt())
        dipText(14f)
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
            gravity = Gravity.CENTER
            setPadding(dp(16f), dp(24f), dp(16f), dp(24f))
        }
        col.addView(TextView(ctx).apply {
            setText(R.string.pin_title)
            setTextColor(Color.WHITE)
            dipText(if (small) 20f else 24f)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        col.addView(msg, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6f) })
        col.addView(dots, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(if (small) 12f else 24f); bottomMargin = dp(if (small) 8f else 16f) })

        val grid = GridLayout(ctx).apply { columnCount = 3 }
        val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "CANCEL", "0", "DEL")
        val margin = if (small) 4f else 8f
        for (k in keys) {
            val b = Button(ctx).apply {
                text = when (k) {
                    "CANCEL" -> ctx.getString(R.string.pin_cancel)
                    "DEL" -> "⌫"
                    else -> k
                }
                dipText(if (k == "CANCEL") 13f else if (small) 20f else 24f)
                setTextColor(Color.WHITE)
                isAllCaps = false
                includeFontPadding = false
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, 0)
                minWidth = 0
                minHeight = 0
                minimumWidth = 0
                minimumHeight = 0
                maxLines = 1
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
                width = dp(keyDp); height = dp(keyDp)
                setMargins(dp(margin), dp(margin), dp(margin), dp(margin))
            })
        }
        col.addView(grid)
        col.addView(TextView(ctx).apply {
            setText(R.string.pin_forgot)
            setTextColor(0xCCFFFFFF.toInt())
            dipText(14f)
            gravity = Gravity.CENTER
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
            setOnClickListener { Prefs.requestReset(ctx); showPending() }
        })

        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(col, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        refresh()
        showPending()
    }

    private fun showPending() {
        val ms = Prefs.resetRemainingMs(ctx)
        if (ms > 0L) msg.text = ctx.getString(R.string.pin_reset_pending, ((ms + 59_999L) / 60_000L).toInt())
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
