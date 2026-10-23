package my.gimano.app.core.photo

import java.io.ByteArrayOutputStream

/**
 * Removes every metadata block from photos before they leave the phone (privacy: no GPS, device model or
 * timestamps; the place already has a location). Re-encoding a decoded bitmap already writes none; this is
 * a second, independently tested layer on the exact bytes that are uploaded or stored.
 */
object MetadataStripper {
    /** JPEG: drops APP1–APP15 (EXIF, XMP, ICC, IPTC/Photoshop, maker data) and COM; keeps APP0 (JFIF) and image data. */
    fun jpeg(src: ByteArray): ByteArray {
        if (src.size < 4 || u(src, 0) != 0xFF || u(src, 1) != 0xD8) return src
        val out = ByteArrayOutputStream(src.size)
        out.write(0xFF); out.write(0xD8)
        var i = 2
        while (i + 4 <= src.size) {
            if (u(src, i) != 0xFF) return src   // not a segment boundary: leave the file alone rather than corrupt it
            val marker = u(src, i + 1)
            if (marker == 0xFF) { i++; continue }   // fill byte
            if (marker == 0xD9) { out.write(src, i, 2); return out.toByteArray() }
            if (marker in 0xD0..0xD7 || marker == 0x01) { out.write(src, i, 2); i += 2; continue }
            val len = (u(src, i + 2) shl 8) or u(src, i + 3)
            if (len < 2 || i + 2 + len > src.size) return src
            if (marker == 0xDA) {   // start of scan: the rest is entropy-coded image data (+ EOI)
                out.write(src, i, src.size - i)
                return out.toByteArray()
            }
            val drop = marker in 0xE1..0xEF || marker == 0xFE
            if (!drop) out.write(src, i, 2 + len)
            i += 2 + len
        }
        return out.toByteArray()
    }

    /** WebP (RIFF): drops the EXIF and XMP chunks and clears their VP8X flags. */
    fun webp(src: ByteArray): ByteArray {
        if (src.size < 12 || String(src, 0, 4, Charsets.US_ASCII) != "RIFF" || String(src, 8, 4, Charsets.US_ASCII) != "WEBP") return src
        val body = ByteArrayOutputStream(src.size)
        var i = 12
        while (i + 8 <= src.size) {
            val id = String(src, i, 4, Charsets.US_ASCII)
            val size = le32(src, i + 4)
            val total = 8 + size + (size and 1)
            if (size < 0 || i + 8 + size > src.size) return src
            when (id) {
                "EXIF", "XMP " -> Unit
                "VP8X" -> {
                    val chunk = src.copyOfRange(i, i + total.coerceAtMost(src.size - i))
                    chunk[8] = (chunk[8].toInt() and 0x04.inv() and 0x08.inv()).toByte()   // XMP=0x04, EXIF=0x08
                    body.write(chunk)
                }
                else -> body.write(src, i, total.coerceAtMost(src.size - i))
            }
            i += total
        }
        val b = body.toByteArray()
        val out = ByteArrayOutputStream(b.size + 12)
        out.write("RIFF".toByteArray(Charsets.US_ASCII)); out.write(le32bytes(b.size + 4)); out.write("WEBP".toByteArray(Charsets.US_ASCII)); out.write(b)
        return out.toByteArray()
    }

    /** True if [bytes] still carry EXIF/XMP/IPTC-style metadata (for tests and a final guard). */
    fun hasMetadata(bytes: ByteArray): Boolean {
        val s = String(bytes, Charsets.ISO_8859_1)
        return s.contains("Exif\u0000\u0000") || s.contains("http://ns.adobe.com/xap/") || s.contains("Photoshop 3.0") ||
            (s.startsWith("RIFF") && (s.contains("EXIF") || s.contains("XMP ")))
    }

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le32(b: ByteArray, i: Int) = u(b, i) or (u(b, i + 1) shl 8) or (u(b, i + 2) shl 16) or (u(b, i + 3) shl 24)
    private fun le32bytes(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}
