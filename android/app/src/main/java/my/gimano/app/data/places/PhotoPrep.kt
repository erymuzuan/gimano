package my.gimano.app.data.places

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import my.gimano.app.core.places.PhotoSize
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Photos are prepared on the device: decoded with sampling, EXIF-rotated (ImageDecoder), long edge
 * ≤ 2048 px, re-encoded (which also drops all metadata, incl. GPS) before upload or local storage.
 */
object PhotoPrep {
    suspend fun decode(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 28) {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                    val (w, h) = PhotoSize.target(info.size.width, info.size.height)
                    dec.setTargetSize(w, h)
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val opts = BitmapFactory.Options().apply { inSampleSize = PhotoSize.sampleSize(bounds.outWidth, bounds.outHeight) }
                val b = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
                val (w, h) = PhotoSize.target(b.width, b.height)
                if (w != b.width) Bitmap.createScaledBitmap(b, w, h, true) else b
            }
        }.getOrNull()
    }

    fun jpeg(b: Bitmap, quality: Int = 85): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    /** Private-place photos: WebP in files/photos/<id>.webp (shared backup layout). */
    fun saveWebp(b: Bitmap, dir: File, id: String): String {
        dir.mkdirs()
        val name = "$id.webp"
        @Suppress("DEPRECATION")
        val fmt = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        File(dir, name).outputStream().use { b.compress(fmt, 80, it) }
        return name
    }
}
