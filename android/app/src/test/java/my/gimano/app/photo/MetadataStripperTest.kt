package my.gimano.app.photo

import my.gimano.app.core.photo.MetadataStripper
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class MetadataStripperTest {
    /** A real JPEG (ImageIO) with an EXIF APP1 carrying GPS + camera model + timestamp, and an XMP APP1 with location. */
    private fun jpegWithGps(): ByteArray {
        val img = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB).apply { for (x in 0 until 64) for (y in 0 until 48) setRGB(x, y, (x * 4) shl 16 or (y * 5)) }
        val plain = ByteArrayOutputStream().also { ImageIO.write(img, "jpg", it) }.toByteArray()
        // Minimal big-endian TIFF: IFD0 {Model, DateTime, GPS IFD pointer}, GPS IFD {GPSLatitudeRef 'N', GPSLatitude 3/1 7/1 43/1}.
        val tiff = ByteArrayOutputStream().apply {
            fun s16(v: Int) { write(v shr 8); write(v) }
            fun s32(v: Int) { s16(v shr 16); s16(v) }
            write("MM".toByteArray()); s16(42); s32(8)
            s16(3)                                            // IFD0: 3 entries (8 + 2 + 36 + 4 = 50)
            s16(0x0110); s16(2); s32(9); s32(50)              // Model "SM-S921B\0" at 50
            s16(0x0132); s16(2); s32(20); s32(59)             // DateTime at 59
            s16(0x8825); s16(4); s32(1); s32(79)              // GPS IFD at 79
            s32(0)
            write("SM-S921B\u0000".toByteArray()); write("2026:10:23 18:30:00\u0000".toByteArray())
            s16(2)                                            // GPS IFD: 2 entries (79 + 2 + 24 + 4 = 109)
            s16(1); s16(2); s32(2); write('N'.code); write(0); s16(0)
            s16(2); s16(5); s32(3); s32(109)
            s32(0)
            s32(3); s32(1); s32(7); s32(1); s32(43); s32(1)
        }.toByteArray()
        val exif = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta><exif:GPSLatitude>3,7.72N</exif:GPSLatitude></x:xmpmeta>".toByteArray(Charsets.ISO_8859_1)
        fun app1(p: ByteArray) = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), ((p.size + 2) shr 8).toByte(), (p.size + 2).toByte()) + p
        return plain.copyOfRange(0, 2) + app1(exif) + app1(xmp) + plain.copyOfRange(2, plain.size)
    }

    @Test fun jpegWithGpsExifComesOutClean() {
        val dirty = jpegWithGps()
        assertTrue("fixture really carries metadata", MetadataStripper.hasMetadata(dirty))
        assertNotNull("fixture is a valid JPEG", ImageIO.read(ByteArrayInputStream(dirty)))
        val clean = MetadataStripper.jpeg(dirty)
        assertFalse(MetadataStripper.hasMetadata(clean))
        val s = String(clean, Charsets.ISO_8859_1)
        listOf("SM-S921B", "2026:10:23", "GPSLatitude", "Exif").forEach { assertFalse("'$it' survived", s.contains(it)) }
        val img = ImageIO.read(ByteArrayInputStream(clean))
        assertEquals(64, img.width); assertEquals(48, img.height)
        assertEquals("idempotent", clean.toList(), MetadataStripper.jpeg(clean).toList())
    }

    @Test fun notAJpegIsLeftAlone() {
        val junk = byteArrayOf(1, 2, 3, 4, 5)
        assertArrayEquals(junk, MetadataStripper.jpeg(junk))
    }

    @Test fun webpExifAndXmpChunksAreDropped() {
        fun chunk(id: String, data: ByteArray) = id.toByteArray() + byteArrayOf(data.size.toByte(), (data.size shr 8).toByte(), 0, 0) + data + (if (data.size % 2 == 1) byteArrayOf(0) else byteArrayOf())
        val vp8x = chunk("VP8X", byteArrayOf(0x0C, 0, 0, 0, 63, 0, 0, 47, 0, 0))     // flags: EXIF|XMP
        val image = chunk("VP8L", ByteArray(21) { it.toByte() })
        val exif = chunk("EXIF", "MM\u0000*GPS 3.1290N 101.6290E".toByteArray())
        val xmp = chunk("XMP ", "<x:xmpmeta>GPSLatitude</x:xmpmeta>".toByteArray())
        val body = "WEBP".toByteArray() + vp8x + image + exif + xmp
        val dirty = "RIFF".toByteArray() + byteArrayOf(body.size.toByte(), (body.size shr 8).toByte(), 0, 0) + body
        assertTrue(MetadataStripper.hasMetadata(dirty))
        val clean = MetadataStripper.webp(dirty)
        assertFalse(MetadataStripper.hasMetadata(clean))
        val riffSize = (clean[4].toInt() and 0xFF) or ((clean[5].toInt() and 0xFF) shl 8)
        assertEquals("RIFF size updated", clean.size - 8, riffSize)
        assertEquals("VP8X flags cleared", 0, clean[20].toInt() and 0x0C)
        assertTrue("image chunk kept", String(clean, Charsets.ISO_8859_1).contains("VP8L"))
    }
}
