package com.hershaleh.videostop

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * מזהה "תנועה אוטומטית" שמאפיינת וידאו: אזור גדול ורציף במסך שמשתנה שוב ושוב
 * בין פריימים עוקבים, ובאותו מקום.
 *
 * כל צילום מסך מוקטן לרשת קטנה של בהירות; משווים לפריים הקודם ומחפשים אזור
 * שמשתנה בצפיפות גבוהה. רק אם זה קורה ברצף של פריימים ובאותו אזור – זה וידאו.
 * בסצנות כהות הסף יורד, כדי לתפוס גם וידאו חשוך.
 */
class MotionDetector {

    private class Region(val l: Int, val t: Int, val r: Int, val b: Int) {
        val area get() = (r - l + 1) * (b - t + 1)

        fun iou(o: Region): Float {
            val il = max(l, o.l); val it = max(t, o.t)
            val ir = min(r, o.r); val ib = min(b, o.b)
            if (ir < il || ib < it) return 0f
            val inter = (ir - il + 1) * (ib - it + 1)
            return inter.toFloat() / (area + o.area - inter)
        }
    }

    private var prev: IntArray? = null
    private val history = ArrayDeque<Region?>()

    fun reset() {
        prev = null
        history.clear()
    }

    /** מקבל צילום (לא ממחזר אותו). מחזיר true כשזוהה וידאו. */
    fun analyze(src: Bitmap): Boolean {
        if (src.width < 8 || src.height < 8) return false
        val gh = (GRID_W * src.height / src.width.toFloat()).roundToInt().coerceAtLeast(8)
        val small = Bitmap.createScaledBitmap(src, GRID_W, gh, true)
        val px = IntArray(GRID_W * gh)
        small.getPixels(px, 0, GRID_W, 0, 0, GRID_W, gh)
        if (small !== src) small.recycle()

        var sum = 0L
        val luma = IntArray(px.size) {
            val c = px[it]
            val v = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
            sum += v
            v
        }
        val threshold = if (sum / luma.size < DARK_MEAN) DARK_DIFF else DIFF_THRESHOLD

        val old = prev
        prev = luma
        if (old == null || old.size != luma.size) {
            history.clear()
            return false
        }

        val changed = BooleanArray(luma.size)
        val rows = IntArray(gh)
        val cols = IntArray(GRID_W)
        for (y in 0 until gh) for (x in 0 until GRID_W) {
            val i = y * GRID_W + x
            if (abs(luma[i] - old[i]) > threshold) {
                changed[i] = true
                rows[y]++
                cols[x]++
            }
        }

        val region = boundingRegion(rows, cols)?.takeIf { r ->
            if (r.area.toFloat() / (GRID_W * gh) < MIN_AREA) return@takeIf false
            var inside = 0
            for (y in r.t..r.b) for (x in r.l..r.r) if (changed[y * GRID_W + x]) inside++
            inside.toFloat() / r.area >= MIN_DENSITY
        }

        history.addLast(region)
        if (history.size > WINDOW) history.removeFirst()
        if (history.size < WINDOW) return false

        val regions = history.filterNotNull()
        if (regions.size < MIN_HITS) return false
        val ref = regions.last()
        if (regions.any { it.iou(ref) < MIN_IOU }) return false

        history.clear()
        return true
    }

    private fun boundingRegion(rows: IntArray, cols: IntArray): Region? {
        val t = rows.indexOfFirst { it >= MIN_LINE }
        val b = rows.indexOfLast { it >= MIN_LINE }
        val l = cols.indexOfFirst { it >= MIN_LINE }
        val r = cols.indexOfLast { it >= MIN_LINE }
        if (t < 0 || l < 0 || b < t || r < l) return null
        return Region(l, t, r, b)
    }

    private companion object {
        const val GRID_W = 40          // רוחב הרשת
        const val DIFF_THRESHOLD = 12  // שינוי בהירות שנחשב תנועה
        const val DARK_DIFF = 5        // סף מופחת לסצנות כהות
        const val DARK_MEAN = 45       // בהירות ממוצעת מתחתיה הסצנה נחשבת כהה
        const val MIN_LINE = 3         // מינימום תאים משתנים בשורה/עמודה
        const val MIN_AREA = 0.08f     // האזור צריך לכסות לפחות 8% מהחלון
        const val MIN_DENSITY = 0.25f  // וחלק גדול ממנו חייב להשתנות
        const val WINDOW = 6           // בודקים את 6 הפריימים האחרונים
        const val MIN_HITS = 5         // ב-5 מהם צריכה להיות תנועה
        const val MIN_IOU = 0.4f       // ובאותו אזור פחות או יותר
    }
}
