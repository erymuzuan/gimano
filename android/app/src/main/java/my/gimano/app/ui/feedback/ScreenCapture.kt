package my.gimano.app.ui.feedback

import android.app.Activity
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import kotlinx.coroutines.suspendCancellableCoroutine
import my.gimano.app.core.feedback.FeedbackRules
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

/** Captures the window (including the map's SurfaceView) for "Include screenshot". */
object ScreenCapture {
    suspend fun capture(activity: Activity): Bitmap? = suspendCancellableCoroutine { cont ->
        val view = activity.window.decorView
        if (view.width == 0 || view.height == 0) { cont.resume(null); return@suspendCancellableCoroutine }
        val bmp = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(activity.window, bmp, { r -> cont.resume(if (r == PixelCopy.SUCCESS) bmp else null) }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            cont.resume(null)
        }
    }

    /** Downscale to ≤ 1080 px wide and JPEG-compress until it fits the API's 1.5 MB limit. */
    fun toJpeg(bmp: Bitmap, maxWidth: Int = 1080): ByteArray {
        val scaled = if (bmp.width > maxWidth) Bitmap.createScaledBitmap(bmp, maxWidth, (bmp.height * maxWidth.toFloat() / bmp.width).toInt(), true) else bmp
        var q = 80
        while (true) {
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, q, out)
            if (out.size() <= FeedbackRules.MAX_SCREENSHOT_BYTES || q <= 30) return out.toByteArray()
            q -= 15
        }
    }
}
