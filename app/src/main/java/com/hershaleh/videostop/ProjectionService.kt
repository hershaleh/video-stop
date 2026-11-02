package com.hershaleh.videostop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager

/** נקודת מפגש בין שירות הצילום לשירות הנגישות (שניהם באותו תהליך). */
object ProjectionHolder {
    @Volatile
    var grabber: ScreenGrabber? = null
}

/** לוכד פריימים מוקטנים של המסך דרך MediaProjection. */
class ScreenGrabber(projection: MediaProjection, screenW: Int, screenH: Int, dpi: Int) {
    private val w = (screenW / SCALE).coerceAtLeast(64)
    private val h = (screenH / SCALE).coerceAtLeast(64)
    private val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
    private val display: VirtualDisplay? = projection.createVirtualDisplay(
        "videostop", w, h, dpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null
    )
    private var last: Bitmap? = null

    /** מחזיר עותק של הפריים האחרון. אם המסך לא השתנה, חוזר אותו פריים (= "אין תנועה"). */
    @Synchronized
    fun grab(): Bitmap? {
        val img = try { reader.acquireLatestImage() } catch (_: Exception) { null }
        if (img != null) {
            try {
                val plane = img.planes[0]
                val pad = plane.rowStride - plane.pixelStride * img.width
                val wide = Bitmap.createBitmap(
                    img.width + pad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888
                )
                wide.copyPixelsFromBuffer(plane.buffer)
                val bmp = if (pad == 0) wide else
                    Bitmap.createBitmap(wide, 0, 0, img.width, img.height).also { wide.recycle() }
                last?.recycle()
                last = bmp
            } catch (_: Exception) {
            } finally {
                img.close()
            }
        }
        return last?.copy(Bitmap.Config.ARGB_8888, false)
    }

    @Synchronized
    fun release() {
        display?.release()
        reader.close()
        last?.recycle()
        last = null
    }

    private companion object {
        const val SCALE = 4
    }
}

/**
 * שירות קדמי שמחזיק הרשאת צילום מסך (MediaProjection) באנדרואיד 8–10,
 * שבהן שירות הנגישות לא יכול לצלם בעצמו. הצילום נשאר במכשיר ואינו נשמר.
 */
class ProjectionService : Service() {

    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val data = intent?.getParcelableExtra<Intent>("data")
        if (intent == null || data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(intent.getIntExtra("code", 0), data)
        if (p == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }, Handler(Looper.getMainLooper()))
        projection = p

        val m = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(m)
        ProjectionHolder.grabber?.release()
        ProjectionHolder.grabber = ScreenGrabber(p, m.widthPixels, m.heightPixels, m.densityDpi)
        return START_NOT_STICKY
    }

    private fun startInForeground() {
        val channel = "videostop_capture"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channel, getString(R.string.projection_title), NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_block)
            .setContentTitle(getString(R.string.projection_title))
            .setContentText(getString(R.string.projection_text))
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, n)
        }
    }

    override fun onDestroy() {
        ProjectionHolder.grabber?.release()
        ProjectionHolder.grabber = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
