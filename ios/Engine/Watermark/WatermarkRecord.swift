import Foundation

/// P633 / P642 (Android WatermarkPatch + WatermarkStore): the original pixels under a photo's watermark and the watermark's spec,
/// kept at the very end of the photo's own file, so the gallery's editor can take the watermark off exactly - the pixels the camera
/// had before it drew it - or draw another one. The record is Android's, byte for byte in layout (Editor/WatermarkPatch.hpp,
/// SCAMWatermarkRecord): «SCWM» v1, the photo's size, 8 / 10 bits, sRGB / Display P3, the spec's JSON, the pieces of WatermarkArea's
/// rectangles (at most 4 MP each) in the predictive code with a CRC-32 each; appended after a JPEG's last EOI, as a WebP «SCWM»
/// chunk or a HEIC / AVIF top-level «uuid» box, ending with its length and «SCWMEND1». A photo from either app opens in the
/// other's editor with «Убрать». An app that re-encodes the photo drops it; whoever has the file can take the watermark off.
enum WatermarkStore {
    /// WatermarkStore.read: the record at the end of `file`, nil when there is none or it is not one (not yet verified).
    static func read(_ file: Data) -> SCAMWatermarkRecord? {
        SCAMWatermarkRecord(file: file)
    }

    /// WatermarkStore.append: `file` with `record` at its end in its container's form; nil for a container it does not know.
    static func append(_ record: SCAMWatermarkRecord, to file: Data) -> Data? {
        SCAMWatermarkRecord.file(file, appending: record.bytes)
    }
}

enum WatermarkPatch {
    /// WatermarkPatch.capture: the pixels of WatermarkArea.of(plan) of the rows (before the watermark is drawn) with `spec`'s JSON;
    /// the rows' fourth byte is read as opaque (the photo is).
    static func capture(_ rows: WatermarkRows, plan: WatermarkPainter.Plan, spec: WatermarkSpec, displayP3: Bool = false) throws
        -> SCAMWatermarkRecord {
        let rects = WatermarkArea.of(plan).flatMap { [$0.left, $0.top, $0.right, $0.bottom] }.map { NSNumber(value: $0) }
        return try SCAMWatermarkRecord(rows: UnsafePointer(rows.base), width: rows.width, height: rows.height, bytesPerRow: rows.bytesPerRow,
                                       rects: rects, spec: spec.toJson(), displayP3: displayP3)
    }
}
