import AVFoundation
import XCTest
@testable import SCAMERA

final class LensMathTests: XCTestCase {
    func testLabels() {
        XCTAssertEqual(LensMath.label(0.48), "0.5×")
        XCTAssertEqual(LensMath.label(1.0), "1×")
        XCTAssertEqual(LensMath.label(2.0), "2×")
        XCTAssertEqual(LensMath.label(4.93), "5×")
        XCTAssertEqual(LensMath.label(2.5), "2.5×")
    }

    func testEquivalentZoom() {
        XCTAssertEqual(LensMath.equivalentZoom(referenceFOV: 70, fov: 70), 1, accuracy: 1e-9)
        // Half the tangent of the half-angle is a 2× lens.
        let half = 2 * atan(tan(35 * Double.pi / 180) / 2) * 180 / Double.pi
        XCTAssertEqual(LensMath.equivalentZoom(referenceFOV: 70, fov: half), 2, accuracy: 1e-9)
        XCTAssertEqual(LensMath.equivalentZoom(referenceFOV: 0, fov: 50), 1)
    }

    func testStandardSizeIsBinnedQuad() {
        let dims = [CMVideoDimensions(width: 4032, height: 3024), CMVideoDimensions(width: 8064, height: 6048)]
        XCTAssertEqual(PhotoCapture.standard(in: dims)?.width, 4032)
    }
}

final class SupportTests: XCTestCase {
    func testFourCC() {
        XCTAssertEqual(FourCharCode(0x6267_3130).fourCC, "bg10")
        XCTAssertEqual(FourCharCode(1).fourCC, "0x00000001")
    }

    func testSessionLogWritesHeaderAndLines() throws {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let log = SessionLog(folder: folder)
        log.write("I", "Test", "hello")
        let files = log.flushedFiles()
        XCTAssertEqual(files.count, 1)
        let text = try String(contentsOf: files[0], encoding: .utf8)
        XCTAssertTrue(text.contains("I/ScameraBuild: SCAMERA iOS"))
        XCTAssertTrue(text.contains("I/Test: hello"))
        try? FileManager.default.removeItem(at: folder)
    }

    func testLocalizationsHaveTheSameKeys() throws {
        func keys(_ language: String) throws -> Set<String> {
            let path = try XCTUnwrap(Bundle(for: CameraModel.self).path(forResource: "Localizable", ofType: "strings",
                                                                        inDirectory: nil, forLocalization: language))
            let dict = try XCTUnwrap(NSDictionary(contentsOfFile: path) as? [String: String])
            return Set(dict.keys)
        }
        XCTAssertEqual(try keys("en"), try keys("ru"))
    }
}
