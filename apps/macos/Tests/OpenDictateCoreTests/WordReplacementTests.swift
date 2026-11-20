import XCTest
@testable import OpenDictateCore

final class WordReplacementTests: XCTestCase {
    private struct Fixture: Decodable {
        struct Rule: Decodable { let source: String; let replacement: String; let enabled: Bool?; let regex: Bool?; let matchCase: Bool?; let wholeWord: Bool? }
        let name: String; let rules: [Rule]; let text: String; let expected: String; let final: Bool?
    }
    func testSharedPlatformCases() throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let cases = try JSONDecoder().decode([Fixture].self, from: Data(contentsOf: root.appendingPathComponent("shared/word-replacement-cases.json")))
        for fixture in cases {
            let rules = fixture.rules.map { WordReplacement(source: $0.source, replacement: $0.replacement, enabled: $0.enabled ?? true, regex: $0.regex ?? false, matchCase: $0.matchCase ?? false, wholeWord: $0.wholeWord) }
            XCTAssertEqual(WordReplacementEngine(rules: rules).apply(fixture.text, final: fixture.final ?? true), fixture.expected, fixture.name)
        }
    }
    func testSessionSnapshotAndCumulativeUpdates() {
        var rule = WordReplacement(source: "cat", replacement: "dog")
        let session = WordReplacementEngine(rules: [rule])
        rule.replacement = "bird"
        XCTAssertEqual(session.apply("cat ", final: false), "dog ")
        XCTAssertEqual(session.apply("cat cat.", final: false), "dog dog.")
        XCTAssertEqual(WordReplacementEngine(rules: [rule]).apply("cat"), "bird")
    }
    func testDocumentRoundTripAndValidation() throws {
        let rule = WordReplacement(source: "тест", replacement: "Test")
        let document = ReplacementDocument(rules: [rule])
        XCTAssertEqual(try JSONDecoder().decode(ReplacementDocument.self, from: JSONEncoder().encode(document)), document)
        XCTAssertFalse(ReplacementDocument(rules: [rule, rule]).isValid)
        XCTAssertFalse(WordReplacement(source: " ", replacement: "Test").isValid)
        XCTAssertFalse(WordReplacement(source: "test", replacement: "").isValid)
        XCTAssertFalse(WordReplacement(source: String(repeating: "x", count: 257), replacement: "Test").isValid)
        for json in [#"{"schemaVersion":2,"rules":[]}"#, #"{"schemaVersion":3,"rules":[]}"#, #"{"schemaVersion":4,"rules":[]}"#, #"{"schemaVersion":5,"rules":[]}"#] {
            XCTAssertThrowsError(try JSONDecoder().decode(ReplacementDocument.self, from: Data(json.utf8)), json)
        }
    }
    func testPatternRulesUseVersionTwoAndLiteralRulesKeepVersionOneBytes() throws {
        let id = "00000000-0000-0000-0000-000000000001"
        let literal = ReplacementDocument(rules: [WordReplacement(id: id, source: "cat", replacement: "dog")])
        let literalJSON = try JSONDecoder().decode([String: AnyDecodable].self, from: JSONEncoder().encode(literal))
        XCTAssertEqual(literalJSON["schemaVersion"]?.value as? Int, 1)
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(literal), as: UTF8.self).contains("regex"))
        let pattern = ReplacementDocument(rules: [WordReplacement(id: id, source: "c.t", replacement: "", regex: true)])
        let data = try JSONEncoder().encode(pattern)
        XCTAssertEqual(try JSONDecoder().decode(ReplacementDocument.self, from: data), pattern)
        XCTAssertEqual(pattern.schemaVersion, 2)
        XCTAssertTrue(pattern.isValid)
        let downgraded = String(decoding: data, as: UTF8.self).replacingOccurrences(of: #""schemaVersion":2"#, with: #""schemaVersion":1"#)
        XCTAssertThrowsError(try JSONDecoder().decode(ReplacementDocument.self, from: Data(downgraded.utf8)))
    }
    func testCaseSensitiveRulesUseVersionThree() throws {
        let id = "00000000-0000-0000-0000-000000000001"
        for rule in [WordReplacement(id: id, source: "ИП", replacement: "предприниматель", matchCase: true),
                     WordReplacement(id: id, source: "ё", replacement: "е", regex: true, matchCase: true)] {
            let document = ReplacementDocument(rules: [rule])
            let data = try JSONEncoder().encode(document)
            XCTAssertEqual(document.schemaVersion, 3)
            XCTAssertEqual(try JSONDecoder().decode(ReplacementDocument.self, from: data), document)
            let downgraded = String(decoding: data, as: UTF8.self).replacingOccurrences(of: #""schemaVersion":3"#, with: #""schemaVersion":2"#)
            XCTAssertThrowsError(try JSONDecoder().decode(ReplacementDocument.self, from: Data(downgraded.utf8)))
        }
        // Rules without the option keep their earlier wire format.
        let pattern = ReplacementDocument(rules: [WordReplacement(id: id, source: "c.t", replacement: "", regex: true)])
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(pattern), as: UTF8.self).contains("matchCase"))
    }
    func testWholeWordRulesUseVersionFour() throws {
        let id = "00000000-0000-0000-0000-000000000001"
        let document = ReplacementDocument(rules: [WordReplacement(id: id, source: "пулл?", replacement: "pull", regex: true, wholeWord: true)])
        let data = try JSONEncoder().encode(document)
        XCTAssertEqual(document.schemaVersion, 4)
        XCTAssertEqual(try JSONDecoder().decode(ReplacementDocument.self, from: data), document)
        let downgraded = String(decoding: data, as: UTF8.self).replacingOccurrences(of: #""schemaVersion":4"#, with: #""schemaVersion":3"#)
        XCTAssertThrowsError(try JSONDecoder().decode(ReplacementDocument.self, from: Data(downgraded.utf8)))
        // Rules saved before the option decode without it and keep matching inside words.
        let legacy = #"{"schemaVersion":2,"rules":[{"id":"\#(id)","source":"пулл?","replacement":"pull","enabled":true,"regex":true}]}"#
        let decoded = try JSONDecoder().decode(ReplacementDocument.self, from: Data(legacy.utf8))
        XCTAssertFalse(decoded.rules[0].wholeWord)
        XCTAssertFalse(String(decoding: try JSONEncoder().encode(decoded), as: UTF8.self).contains("wholeWord"))
    }
    func testInWordPhrasesUseVersionFiveAndLegacyPhrasesStayWholeWord() throws {
        let id = "00000000-0000-0000-0000-000000000001"
        // Phrases saved before the option decode as whole-word and keep the version 1 wire format.
        let legacy = #"{"schemaVersion":1,"rules":[{"id":"\#(id)","source":"cat","replacement":"dog","enabled":true}]}"#
        let decoded = try JSONDecoder().decode(ReplacementDocument.self, from: Data(legacy.utf8))
        XCTAssertTrue(decoded.rules[0].wholeWord)
        XCTAssertEqual(decoded.schemaVersion, 1)
        XCTAssertEqual(String(decoding: try JSONEncoder().encode(decoded), as: UTF8.self).contains("wholeWord"), false)
        XCTAssertTrue(WordReplacement(source: "cat", replacement: "dog").wholeWord)
        XCTAssertFalse(WordReplacement(source: "c.t", replacement: "dog", regex: true).wholeWord)

        let document = ReplacementDocument(rules: [WordReplacement(id: id, source: "читшит", replacement: "cheatsheet", wholeWord: false)])
        let data = try JSONEncoder().encode(document)
        XCTAssertEqual(document.schemaVersion, 5)
        XCTAssertTrue(String(decoding: data, as: UTF8.self).contains(#""wholeWord":false"#))
        XCTAssertEqual(try JSONDecoder().decode(ReplacementDocument.self, from: data), document)
        // A client that only knows version 4 computes a lower version for this document and rejects it.
        let downgraded = String(decoding: data, as: UTF8.self).replacingOccurrences(of: #""schemaVersion":5"#, with: #""schemaVersion":4"#)
        XCTAssertThrowsError(try JSONDecoder().decode(ReplacementDocument.self, from: Data(downgraded.utf8)))
    }
    func testWholeWordPatternUsability() {
        func usable(_ source: String) -> Bool {
            WordReplacement(source: source, replacement: "x", regex: true, wholeWord: true).isUsable
        }
        XCTAssertTrue(usable("пулл?"))
        XCTAssertTrue(usable("\\Qc++"))
        // The wrapper must not complete a pattern that is invalid on its own.
        XCTAssertFalse(usable("a)(b"))
        XCTAssertFalse(usable("a*"))
    }
    func testPatternUsability() {
        func usable(_ source: String, _ replacement: String = "x") -> Bool {
            WordReplacement(source: source, replacement: replacement, regex: true).isUsable
        }
        XCTAssertTrue(usable("(\\d+) руб", "$1 ₽"))
        XCTAssertTrue(usable(" +", " "))
        XCTAssertTrue(usable("um", ""))
        XCTAssertFalse(usable("("))
        XCTAssertFalse(usable("a*"))
        XCTAssertFalse(usable("(a)", "$2"))
        XCTAssertFalse(usable("(a)", "${2}"))
        XCTAssertTrue(usable("(a)", "$$2 $x"))
        XCTAssertFalse(usable(""))
        XCTAssertFalse(WordReplacement(source: "cat", replacement: "", regex: false).isUsable)
    }
}

private struct AnyDecodable: Decodable {
    let value: Any
    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let number = try? container.decode(Int.self) { value = number } else { value = "" }
    }
}
