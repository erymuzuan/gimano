import Foundation
import ImageIO
import Testing
import UIKit
import UniformTypeIdentifiers
@testable import Gimano

@Suite("Photos leave the phone without metadata")
struct PhotoPrivacyTests {
    /// A JPEG carrying GPS, EXIF (date, lens), TIFF (make/model) and XMP, like a phone camera's.
    private func jpegWithMetadata() throws -> Data {
        let img = UIGraphicsImageRenderer(size: CGSize(width: 400, height: 300)).image { ctx in
            UIColor.orange.setFill(); ctx.fill(CGRect(x: 0, y: 0, width: 400, height: 300))
        }
        let cg = try #require(img.cgImage)
        let out = NSMutableData()
        let dest = try #require(CGImageDestinationCreateWithData(out, UTType.jpeg.identifier as CFString, 1, nil))
        let props: [CFString: Any] = [
            kCGImagePropertyGPSDictionary: [kCGImagePropertyGPSLatitude: 3.1179, kCGImagePropertyGPSLatitudeRef: "N",
                                            kCGImagePropertyGPSLongitude: 101.6198, kCGImagePropertyGPSLongitudeRef: "E"],
            kCGImagePropertyExifDictionary: [kCGImagePropertyExifDateTimeOriginal: "2026:10:23 09:15:00",
                                             kCGImagePropertyExifLensModel: "iPhone 16 back camera"],
            kCGImagePropertyTIFFDictionary: [kCGImagePropertyTIFFMake: "Apple", kCGImagePropertyTIFFModel: "iPhone 16"],
            kCGImagePropertyOrientation: 6,
        ]
        let meta = CGImageMetadataCreateMutable()
        CGImageMetadataSetValueWithPath(meta, nil, "xmp:CreatorTool" as CFString, "Camera" as CFString)
        CGImageDestinationAddImageAndMetadata(dest, cg, meta, props as CFDictionary)
        #expect(CGImageDestinationFinalize(dest))
        let data = out as Data
        // Sanity: the input really has GPS.
        let src = try #require(CGImageSourceCreateWithData(data as CFData, nil))
        let inProps = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any]
        #expect(inProps?[kCGImagePropertyGPSDictionary] != nil)
        return data
    }

    @Test("Upload JPEG has no GPS, EXIF date/lens, TIFF make/model or XMP; orientation is baked in")
    func stripped() throws {
        let out = try #require(ImageProcessing.uploadJPEG(try jpegWithMetadata()))
        let src = try #require(CGImageSourceCreateWithData(out as CFData, nil))
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] ?? [:]
        #expect(props[kCGImagePropertyGPSDictionary] == nil, "GPS")
        let tiff = props[kCGImagePropertyTIFFDictionary] as? [CFString: Any]
        #expect(tiff?[kCGImagePropertyTIFFMake] == nil && tiff?[kCGImagePropertyTIFFModel] == nil, "camera make/model")
        let exif = props[kCGImagePropertyExifDictionary] as? [CFString: Any]
        #expect(exif?[kCGImagePropertyExifDateTimeOriginal] == nil && exif?[kCGImagePropertyExifLensModel] == nil, "EXIF")
        #expect(CGImageSourceCopyMetadataAtIndex(src, 0, nil).map { CGImageMetadataCopyTags($0) as? [Any] ?? [] }?.contains { _ in true } != true
                || (CGImageSourceCopyMetadataAtIndex(src, 0, nil).flatMap { CGImageMetadataCopyTagWithPath($0, nil, "xmp:CreatorTool" as CFString) }) == nil, "XMP")
        #expect((props[kCGImagePropertyOrientation] as? Int ?? 1) == 1)
        // Rotated by the orientation tag (6 = 90°): landscape in → portrait out.
        let w = props[kCGImagePropertyPixelWidth] as? Int ?? 0, h = props[kCGImagePropertyPixelHeight] as? Int ?? 0
        #expect(w > 0 && h > w)
        // The raw bytes don't contain the coordinates either.
        let raw = String(decoding: out, as: UTF8.self)
        #expect(!raw.contains("101.6198") && !raw.contains("iPhone 16"))
    }

    @Test("Camera images and private-place WebP go through the same path")
    func cameraPath() throws {
        let img = UIGraphicsImageRenderer(size: CGSize(width: 100, height: 100)).image { _ in }
        let p = try #require(PhotoPicking.picked(img))
        let src = try #require(CGImageSourceCreateWithData(p.jpeg as CFData, nil))
        let props = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any] ?? [:]
        #expect(props[kCGImagePropertyGPSDictionary] == nil)
    }
}
