import ImageIO
import UIKit
import UniformTypeIdentifiers
import libwebp

/// Photo preparation done on the device before anything leaves it.
enum ImageProcessing {
    /// Upload size cap from the contract (the server re-encodes to WebP ≤ 1600 px and strips metadata).
    static let maxUploadPixels: CGFloat = 2048
    /// Private-place photos are stored as WebP (shared backup format), ≤ 1600 px like the server.
    static let maxLocalPixels: CGFloat = 1600

    /// Decodes any supported input (JPEG/PNG/HEIC/WebP), applies EXIF orientation and downsizes with ImageIO
    /// (memory-friendly thumbnailing). Metadata, GPS included, is not carried over.
    static func downscaled(_ data: Data, maxPixels: CGFloat) -> UIImage? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixels,
            kCGImageSourceShouldCacheImmediately: true,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }

    /// JPEG for upload (HEIC → JPEG), long edge ≤ 2048 px, orientation applied, and NO metadata:
    /// the pixels are re-encoded on their own, so EXIF, GPS, TIFF (make/model), XMP and IPTC never leave the phone.
    static func uploadJPEG(_ data: Data, quality: CGFloat = 0.82) -> Data? {
        guard let cg = downscaled(data, maxPixels: maxUploadPixels)?.cgImage else { return nil }
        return strippedJPEG(cg, quality: quality)
    }

    /// Encodes pixels only (the destination gets no source properties).
    static func strippedJPEG(_ cg: CGImage, quality: CGFloat) -> Data? {
        let out = NSMutableData()
        guard let dest = CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(dest, cg, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        guard CGImageDestinationFinalize(dest) else { return nil }
        return out as Data
    }

    static func uploadJPEG(_ image: UIImage, quality: CGFloat = 0.82) -> Data? {
        guard let d = image.jpegData(compressionQuality: 0.95) else { return nil }
        return uploadJPEG(d, quality: quality)
    }

    /// Lossy WebP (libwebp) of an image, long edge ≤ `maxPixels`.
    static func webp(_ data: Data, maxPixels: CGFloat = maxLocalPixels, quality: Float = 80) -> Data? {
        guard let image = downscaled(data, maxPixels: maxPixels), let cg = image.cgImage else { return nil }
        return webp(cg, quality: quality)
    }

    static func webp(_ cg: CGImage, quality: Float = 80) -> Data? {
        let w = cg.width, h = cg.height
        let stride = w * 4
        var rgba = [UInt8](repeating: 0, count: stride * h)
        guard let ctx = CGContext(data: &rgba, width: w, height: h, bitsPerComponent: 8, bytesPerRow: stride,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: w, height: h))
        var out: UnsafeMutablePointer<UInt8>?
        let size = WebPEncodeRGBA(&rgba, Int32(w), Int32(h), Int32(stride), quality, &out)
        guard size > 0, let out else { return nil }
        defer { WebPFree(out) }
        return Data(bytes: out, count: size)
    }

    /// True when the bytes are a RIFF/WEBP container.
    static func isWebP(_ data: Data) -> Bool {
        data.count > 12 && data.prefix(4) == Data("RIFF".utf8) && data.subdata(in: 8..<12) == Data("WEBP".utf8)
    }

    /// Pixel size of encoded image data (any ImageIO-readable format; WebP decoding is native on iOS 14+).
    static func pixelSize(_ data: Data) -> CGSize? {
        guard let src = CGImageSourceCreateWithData(data as CFData, nil),
              let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
              let w = p[kCGImagePropertyPixelWidth] as? Int, let h = p[kCGImagePropertyPixelHeight] as? Int else { return nil }
        return CGSize(width: w, height: h)
    }
}
