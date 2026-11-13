import CoreGraphics
import Foundation
import ImageIO
import Vision

/// «Портрет» on iOS: the faces of a burst in the form Android's Parameters.portraitFaces has them (PortraitFaces.fromResult of the
/// Camera2 face detection) - normalised left, top, right, bottom in the unrotated RAW buffer (the sensor orientation of the merged
/// RGB), at most five. Vision's face rectangles on the camera's own processed image of the burst (its HEIC, at most 1024 px a side),
/// or, without it or when its frame is not the RAW's, on a downscaled grey of the first RAW frame; the boxes are turned from the
/// upright picture into the RAW buffer with the DNG orientation.
enum PortraitFaces {
    struct Faces {
        var detected = false
        var boxes: [Float] = []
        var note = ""
    }

    static let maxFaces = 5
    private static let side = 1024

    /// «Защита лиц» (pref_portrait_enabled, off by default as on Android)
    static var enabled: Bool {
        (UserDefaults.standard.dictionary(forKey: PostSettings.prefsKey)?["pref_portrait_enabled"] as? NSNumber)?.boolValue ?? false
    }

    static func detect(_ burst: BayerBurst, dngOrientation: Int, black: Float, white: Float) -> Faces {
        let rawUpright = upright(width: burst.width, height: burst.height, orientation: dngOrientation)
        if let processed = burst.processed, let source = CGImageSourceCreateWithData(processed as CFData, nil),
           let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [String: Any],
           let w = props[kCGImagePropertyPixelWidth as String] as? Int, let h = props[kCGImagePropertyPixelHeight as String] as? Int {
            let orientation = (props[kCGImagePropertyOrientation as String] as? Int) ?? 1
            let shown = upright(width: w, height: h, orientation: orientation)
            // PortraitFaces.sameAspect: the processed picture must show the RAW's frame
            if sameAspect(shown.width, shown.height, rawUpright.width, rawUpright.height) {
                let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                                kCGImageSourceThumbnailMaxPixelSize: side,
                                                kCGImageSourceCreateThumbnailWithTransform: false]
                if let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary),
                   var faces = find(image, orientation: orientation, dngOrientation: dngOrientation) {
                    faces.note = "Vision on the processed \(w)x\(h) (orientation \(orientation)): " + faces.note
                    return faces
                }
            }
        }
        guard let image = rawGrey(burst, black: black, white: white),
              var faces = find(image, orientation: dngOrientation, dngOrientation: dngOrientation) else {
            return Faces(note: "no picture to find faces on")
        }
        faces.note = "Vision on RAW frame 0 (\(image.width)x\(image.height)): " + faces.note
        return faces
    }

    /// Vision on `image` (stored pixels shown with EXIF `orientation`); the boxes into the RAW buffer shown with `dngOrientation`.
    private static func find(_ image: CGImage, orientation: Int, dngOrientation: Int) -> Faces? {
        let request = VNDetectFaceRectanglesRequest()
        let handler = VNImageRequestHandler(cgImage: image, orientation: CGImagePropertyOrientation(rawValue: UInt32(clamping: orientation)) ?? .up,
                                            options: [:])
        do {
            try handler.perform([request])
        } catch {
            return nil
        }
        // Android keeps faces of score >= 50 (of 100); the largest first when there are more than five
        let found = (request.results ?? []).filter { $0.confidence >= 0.5 && $0.boundingBox.width > 0 && $0.boundingBox.height > 0 }
            .sorted { $0.boundingBox.width * $0.boundingBox.height > $1.boundingBox.width * $1.boundingBox.height }
        var out = Faces(detected: !found.isEmpty)
        for face in found where out.boxes.count < maxFaces * 4 {
            // Vision: normalised in the upright picture, the origin bottom-left; the corners top-left, then into the RAW buffer
            let box = face.boundingBox
            let a = toStored(Double(box.minX), 1 - Double(box.maxY), orientation: dngOrientation)
            let b = toStored(Double(box.maxX), 1 - Double(box.minY), orientation: dngOrientation)
            let l = Float(max(0, min(a.u, b.u))), t = Float(max(0, min(a.v, b.v)))
            let r = Float(min(1, max(a.u, b.u))), bottom = Float(min(1, max(a.v, b.v)))
            if r > l && bottom > t { out.boxes += [l, t, r, bottom] }
        }
        out.note = "\(found.count) face(s), boxes \(out.boxes.map { String(format: "%.4f", $0) }.joined(separator: ", "))"
        return out
    }

    /// The upright size of a picture stored `width` x `height` with EXIF `orientation` (5 ... 8 turn it a quarter).
    private static func upright(width: Int, height: Int, orientation: Int) -> (width: Int, height: Int) {
        (5...8).contains(orientation) ? (height, width) : (width, height)
    }

    private static func sameAspect(_ w: Int, _ h: Int, _ sw: Int, _ sh: Int) -> Bool {
        w > 0 && h > 0 && sw > 0 && sh > 0 && abs(Double(w) * Double(sh) / (Double(h) * Double(sw)) - 1) < 0.002
    }

    /// A normalised point (x, y; top-left origin) of the upright picture to the stored pixels shown with EXIF `orientation`.
    static func toStored(_ x: Double, _ y: Double, orientation: Int) -> (u: Double, v: Double) {
        switch orientation {
        case 2: return (1 - x, y)            // mirrored
        case 3: return (1 - x, 1 - y)        // turned 180
        case 4: return (x, 1 - y)            // mirrored vertically
        case 5: return (y, x)                // transposed
        case 6: return (y, 1 - x)            // shown turned 90 clockwise
        case 7: return (1 - y, 1 - x)        // transversed
        case 8: return (1 - y, x)            // shown turned 90 counter-clockwise
        default: return (x, y)
        }
    }

    /// The first RAW frame as grey (the 2x2 colour block's mean, every `step`-th block), its mean brought to mid grey, gamma 1/2.2.
    private static func rawGrey(_ burst: BayerBurst, black: Float, white: Float) -> CGImage? {
        guard let frame = burst.frames.first, burst.width >= 64, burst.height >= 64,
              frame.raw.count >= burst.width * burst.height * 2 else { return nil }
        let step = max(2, (max(burst.width, burst.height) + side - 1) / side + 1) & ~1
        let w = burst.width / step, h = burst.height / step
        var linear = [Float](repeating: 0, count: w * h)
        let range = max(white - black, 1)
        frame.raw.withUnsafeBytes { buffer in
            let px = buffer.bindMemory(to: UInt16.self)
            for y in 0..<h {
                let row = y * step * burst.width
                for x in 0..<w {
                    let i = row + x * step
                    let sum = Float(px[i]) + Float(px[i + 1]) + Float(px[i + burst.width]) + Float(px[i + burst.width + 1])
                    linear[y * w + x] = max(0, (sum * 0.25 - black) / range)
                }
            }
        }
        let mean = linear.reduce(0, +) / Float(linear.count)
        let gain = min(64, max(1, 0.18 / max(mean, 1e-6)))
        var bytes = [UInt8](repeating: 0, count: w * h)
        for i in bytes.indices { bytes[i] = UInt8(min(255, max(0, powf(min(linear[i] * gain, 1), 1 / 2.2) * 255 + 0.5))) }
        guard let provider = CGDataProvider(data: Data(bytes) as CFData) else { return nil }
        return CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 8, bytesPerRow: w, space: CGColorSpaceCreateDeviceGray(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.none.rawValue), provider: provider, decode: nil,
                       shouldInterpolate: false, intent: .defaultIntent)
    }
}
