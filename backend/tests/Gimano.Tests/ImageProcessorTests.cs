using System.Text;
using Gimano.Core.Community;
using SkiaSharp;

namespace Gimano.Tests;

public class ImageProcessorTests
{
    /// <summary>A 300×200 JPEG: red top-left quadrant, blue elsewhere; optional EXIF APP1 with Orientation and GPS.</summary>
    internal static byte[] Jpeg(int orientation = 1, bool withGps = true, int w = 300, int h = 200)
    {
        using var bmp = new SKBitmap(w, h);
        using (var c = new SKCanvas(bmp))
        {
            c.Clear(SKColors.Blue);
            using var red = new SKPaint { Color = SKColors.Red };
            c.DrawRect(0, 0, w / 2f, h / 2f, red);
        }
        using var img = SKImage.FromBitmap(bmp);
        var jpeg = img.Encode(SKEncodedImageFormat.Jpeg, 95).ToArray();
        return InsertExif(jpeg, orientation, withGps);
    }

    /// <summary>Builds a minimal big-endian TIFF/EXIF: IFD0 {Orientation, GPSInfo→GPS IFD {GPSLatitudeRef 'N', GPSVersionID}} + "GimanoTestGPS" marker.</summary>
    private static byte[] InsertExif(byte[] jpeg, int orientation, bool withGps)
    {
        var tiff = new List<byte>();
        void U16(int v) { tiff.Add((byte)(v >> 8)); tiff.Add((byte)v); }
        void U32(int v) { U16(v >> 16); U16(v & 0xffff); }
        tiff.AddRange("MM"u8.ToArray()); U16(42); U32(8);
        var entries = withGps ? 2 : 1;
        U16(entries);
        U16(0x0112); U16(3); U32(1); U16(orientation); U16(0);          // Orientation SHORT
        var gpsIfdOffset = 8 + 2 + entries * 12 + 4;
        if (withGps) { U16(0x8825); U16(4); U32(1); U32(gpsIfdOffset); } // GPSInfo LONG
        U32(0);                                                           // next IFD
        if (withGps)
        {
            U16(2);
            U16(0x0001); U16(2); U32(2); tiff.Add((byte)'N'); tiff.Add(0); U16(0);   // GPSLatitudeRef "N"
            U16(0x0000); U16(1); U32(4); tiff.AddRange(new byte[] { 2, 3, 0, 0 });   // GPSVersionID
            U32(0);
            tiff.AddRange(Encoding.ASCII.GetBytes("GimanoTestGPS"));
        }
        var app1 = new List<byte> { 0xFF, 0xE1 };
        var payload = Encoding.ASCII.GetBytes("Exif\0\0").Concat(tiff).ToArray();
        var len = payload.Length + 2;
        app1.Add((byte)(len >> 8)); app1.Add((byte)len);
        app1.AddRange(payload);
        return jpeg.Take(2).Concat(app1).Concat(jpeg.Skip(2)).ToArray();
    }

    private static SKColor Pixel(byte[] webp, float fx, float fy)
    {
        using var bmp = SKBitmap.Decode(webp);
        return bmp.GetPixel((int)(fx * (bmp.Width - 1)), (int)(fy * (bmp.Height - 1)));
    }

    private static bool IsRed(SKColor c) => c.Red > 200 && c.Blue < 60;

    [Fact]
    public void Fixture_carries_exif_orientation_and_gps()
    {
        var jpeg = Jpeg(6);
        using var codec = SKCodec.Create(new SKMemoryStream(jpeg));
        Assert.Equal(SKEncodedOrigin.RightTop, codec.EncodedOrigin);
        Assert.Contains("GimanoTestGPS", Encoding.ASCII.GetString(jpeg));
    }

    [Fact]
    public void Output_is_webp_without_any_metadata()
    {
        var p = ImageProcessor.Process(Jpeg(1));
        Assert.Equal("RIFF", Encoding.ASCII.GetString(p.Webp, 0, 4));
        Assert.Equal("WEBP", Encoding.ASCII.GetString(p.Webp, 8, 4));
        Assert.Equal("WEBP", Encoding.ASCII.GetString(p.Thumb, 8, 4));
        foreach (var bytes in new[] { p.Webp, p.Thumb })
        {
            var ascii = Encoding.ASCII.GetString(bytes);
            Assert.DoesNotContain("Exif", ascii);
            Assert.DoesNotContain("EXIF", ascii);
            Assert.DoesNotContain("XMP", ascii);
            Assert.DoesNotContain("GimanoTestGPS", ascii);
        }
    }

    [Theory]
    [InlineData(1, 300, 200, 0.1f, 0.1f)]   // as stored: red top-left
    [InlineData(3, 300, 200, 0.9f, 0.9f)]   // 180°: red bottom-right
    [InlineData(6, 200, 300, 0.9f, 0.1f)]   // rotate 90° CW: red top-right
    [InlineData(8, 200, 300, 0.1f, 0.9f)]   // rotate 90° CCW: red bottom-left
    [InlineData(2, 300, 200, 0.9f, 0.1f)]   // mirrored: red top-right
    [InlineData(4, 300, 200, 0.1f, 0.9f)]   // flipped: red bottom-left
    [InlineData(5, 200, 300, 0.1f, 0.1f)]   // transpose: red top-left
    [InlineData(7, 200, 300, 0.9f, 0.9f)]   // transverse: red bottom-right
    public void Exif_orientation_is_applied(int orientation, int w, int h, float rx, float ry)
    {
        var p = ImageProcessor.Process(Jpeg(orientation));
        Assert.Equal((w, h), (p.Width, p.Height));
        Assert.True(IsRed(Pixel(p.Webp, rx, ry)), $"expected red at ({rx},{ry}) for orientation {orientation}");
        Assert.False(IsRed(Pixel(p.Webp, 1 - rx, 1 - ry)));
    }

    [Fact]
    public void Large_images_are_resized_to_1600_and_thumb_400()
    {
        var p = ImageProcessor.Process(Jpeg(1, w: 3200, h: 2400));
        Assert.Equal((1600, 1200), (p.Width, p.Height));
        Assert.Equal((400, 300), (p.ThumbWidth, p.ThumbHeight));
        Assert.Equal(32, p.Key.Length);
        Assert.NotEqual(p.Key, p.ThumbKey);
    }

    [Fact]
    public void Small_images_are_not_upscaled_but_tiny_ones_are_rejected()
    {
        var p = ImageProcessor.Process(Jpeg(1, w: 640, h: 480));
        Assert.Equal((640, 480), (p.Width, p.Height));
        var ex = Assert.Throws<ImageRejectedException>(() => ImageProcessor.Process(Jpeg(1, w: 300, h: 150)));
        Assert.False(ex.UnsupportedFormat);
    }

    [Fact]
    public void Undecodable_and_heic_are_unsupported()
    {
        var heic = new byte[64];
        heic[3] = 0x18;
        "ftypheic"u8.ToArray().CopyTo(heic, 4);
        var ex = Assert.Throws<ImageRejectedException>(() => ImageProcessor.Probe(heic));
        Assert.True(ex.UnsupportedFormat);
        Assert.Contains("HEIC", ex.Message);
        Assert.True(Assert.Throws<ImageRejectedException>(() => ImageProcessor.Probe("not an image"u8.ToArray())).UnsupportedFormat);
    }

    [Fact]
    public void Content_keys_are_stable_hashes()
    {
        var a = ImageProcessor.Process(Jpeg(1));
        var b = ImageProcessor.Process(Jpeg(1));
        Assert.Equal(a.Key, b.Key);
    }
}
