import XCTest
@testable import OpenDictateCore

final class PunctuationCorrectionTests: XCTestCase {
    func testSharedValidationPreservesWordsNumbersAddressesAndSymbols() throws {
        struct Example: Decodable { let source: String; let candidate: String; let expected: String }
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let examples = try JSONDecoder().decode([Example].self,
            from: Data(contentsOf: root.appendingPathComponent("shared/punctuation-correction.json")))
        for example in examples {
            XCTAssertEqual(PunctuationCorrection.validated(example.candidate, source: example.source), example.expected)
        }
    }

}
