import XCTest
@testable import OpenDictateCore

final class ValueFormatRulesTests: XCTestCase {
    private func fixture() throws -> [String: Any] {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        return try JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("shared/value-format-rules.json"))) as! [String: Any]
    }
    func testSharedRuleEncodingScopesPrecedenceAndOrdering() throws {
        let root = try fixture()
        for row in root["cases"] as! [[String: Any]] {
            let encoded = row["encoded"] as! String, languages = row["languages"] as! [String]
            let settings = try PostProcessingSettings.decode(encoded)
            XCTAssertEqual(try settings.encoded(), encoded)
            let formats = try XCTUnwrap(settings.formats)
            let prompt = settings.prompt(languages: languages)
            if let expected = row["prompt"] as? String { XCTAssertEqual(prompt, expected, row["name"] as! String) }
            if row["name"] as? String == "ru_en" {
                XCTAssertEqual(formats.sortedRules(russian: false).map(\.id), root["orderEnglish"] as! [String])
                XCTAssertTrue(prompt.contains(#""language":"ru","reference":"2026-03-05""#))
                XCTAssertFalse(prompt.contains(#""language":"de""#))
                XCTAssertTrue(prompt.contains("03/05/2026")); XCTAssertTrue(prompt.contains("05.03.2026"))
            }
            if row["name"] as? String == "global_beats_automatic" {
                XCTAssertFalse(prompt.contains(#""language":"en","reference":"2026-03-05""#))
                XCTAssertTrue(prompt.contains(#""example":"2026-03-05","language":"*","reference":"2026-03-05""#))
            }
        }
        for encoded in root["invalid"] as! [String] { XCTAssertThrowsError(try PostProcessingSettings.decode(encoded)) }
    }
    func testEditingMovesAKeyWithoutDuplicatingItAndDeletingRevealsDefaults() {
        var formats = ValueFormatting.systemDefaults(locale: Locale(identifier: "en_US"))
        XCTAssertTrue(formats.rules.isEmpty)
        formats.setRule(.init(kind: .date, example: "2026-03-05"))
        formats.setRule(.init(kind: .date, language: "ru", example: "05.03.2026"), replacing: "date:*")
        XCTAssertEqual(formats.rules.map(\.id), ["date:ru"])
        formats.removeRule(id: "date:ru")
        XCTAssertTrue(formats.rules.isEmpty); XCTAssertTrue(formats.isValid)
        XCTAssertTrue(PostProcessingSettings(enabled: true, formatValues: true, formats: formats).prompt(languages: ["en"]).contains(#""language":"en""#))
        XCTAssertEqual(ValueKind.date.source(russian: true), "5 марта 2026 года")
        XCTAssertEqual(ValueKind.date.source(russian: false), "March 5, 2026")
    }
    func testLegacyFixedStylesRemainGlobalUntilExplicitlyChanged() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let fixture = try JSONSerialization.jsonObject(with: Data(contentsOf: root.appendingPathComponent("shared/postprocessing-settings.json"))) as! [String: Any]
        let encoded = (fixture["cases"] as! [[String: Any]])[2]["encoded"] as! String
        let old = try PostProcessingSettings.decode(encoded)
        XCTAssertNil(old.formats?.customRules); XCTAssertEqual(old.formats?.rules.count, 10)
        XCTAssertEqual(try old.encoded(), encoded)
    }
}
