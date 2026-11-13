import XCTest
@testable import SCAMERA

/// The Android merge through ScamGL on this device's GPU, on a real burst crop (CI copies replay/p335's n124513b.nch and
/// tools/replay_tuning.txt into the test bundle; the Mac runner's Metal run of the same burst matches Mesa at 97 dB).
final class MergeTests: XCTestCase {
    func testRealBurstCropMerges() throws {
        let bundle = Bundle(for: MergeTests.self)
        guard let burst = bundle.path(forResource: "n124513b", ofType: "nch"),
              let tuningPath = bundle.path(forResource: "replay_tuning", ofType: "txt") else {
            throw XCTSkip("burst crop not bundled (CI adds it)")
        }
        let tuning = try String(contentsOfFile: tuningPath, encoding: .utf8)
        let result = try SCAMMerge.mergeBurst(atPath: burst, tuning: tuning)
        XCTAssertEqual(result.width, 512)
        XCTAssertEqual(result.height, 512)
        XCTAssertEqual(result.rgb.count, 512 * 512 * 3 * 4)
        let values = result.rgb.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
        XCTAssertTrue(values.allSatisfy { $0.isFinite })
        let mean = values.reduce(0, +) / Float(values.count)
        print("MERGE TEST n124513b \(result.width)x\(result.height) mean=\(mean) max=\(values.max() ?? 0) ms=\(Int(result.milliseconds))")
        XCTAssertGreaterThan(mean, 0)
        // The Mesa reference of the same burst (CI job merge-reference): the same image within float rounding.
        if let refPath = bundle.path(forResource: "n124513b.reference", ofType: "f32") {
            let raw = try Data(contentsOf: URL(fileURLWithPath: refPath))
            let reference = raw.dropFirst(16).withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
            XCTAssertEqual(reference.count, values.count)
            let sorted = reference.sorted()
            let scale = Double(sorted[Int(Double(sorted.count - 1) * 0.999)])
            var mse = 0.0
            for i in 0..<min(reference.count, values.count) {
                let d = (Double(values[i]) - Double(reference[i])) / scale
                mse += d * d
            }
            mse /= Double(values.count)
            let psnr = mse == 0 ? 99 : 10 * log10(1 / mse)
            print("MERGE TEST n124513b vs Mesa: PSNR \(String(format: "%.2f", psnr)) dB")
            XCTAssertGreaterThan(psnr, 80)
        }
        XCTAssertFalse(result.log.contains { $0.contains("GPU error") }, result.log.filter { $0.contains("GPU error") }.joined(separator: "\n"))
    }
}
