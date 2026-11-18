import XCTest
@testable import OpenDictateCore

final class TextTests: XCTestCase {
    func testCumulativePartialsAlwaysReplaceOriginalSelection() {
        let snapshot = EditableTextSnapshot(original: "before old after", selection: NSRange(location: 7, length: 3))
        XCTAssertEqual(snapshot.compose("new"), "before new after")
        XCTAssertEqual(snapshot.compose("new words"), "before new words after")
        XCTAssertEqual(snapshot.cursor(after: "new words"), NSRange(location: 16, length: 0))
    }
    func testUTF16SelectionPreservesEmojiAndCyrillic() {
        let snapshot = EditableTextSnapshot(original: "🙂 старое!", selection: NSRange(location: 3, length: 6))
        XCTAssertEqual(snapshot.selectedText, "старое")
        XCTAssertEqual(snapshot.compose("🎤 новое"), "🙂 🎤 новое!")
        XCTAssertEqual(snapshot.cursor(after: "🎤 новое").location, 11)
    }
    func testBoundsAreClampedWithoutOverflow() {
        XCTAssertEqual(EditableTextSnapshot(original: "abc", selection: NSRange(location: Int.max, length: Int.max)).compose("x"), "abcx")
        XCTAssertEqual(EditableTextSnapshot(original: "abc", selection: NSRange(location: -1, length: 20)).compose("x"), "x")
    }
    func testNoSelectionTransformsWholeField() {
        let snapshot = EditableTextSnapshot(original: "A whole sentence.", selection: NSRange(location: 5, length: 0))
        XCTAssertEqual(snapshot.transformationTarget.selectedText, "A whole sentence.")
        XCTAssertEqual(snapshot.transformationTarget.compose("Changed"), "Changed")
    }
    func testDeliveryRejectsTypingAndCursorMovement() {
        var guardState = TextDeliveryGuard(snapshot: EditableTextSnapshot(original: "old", selection: NSRange(location: 0, length: 3)))
        XCTAssertTrue(guardState.accepts(text: "old", selection: NSRange(location: 0, length: 3)))
        guardState.didApply("new")
        XCTAssertTrue(guardState.accepts(text: "new", selection: NSRange(location: 3, length: 0)))
        XCTAssertFalse(guardState.accepts(text: "new!", selection: NSRange(location: 4, length: 0)))
        XCTAssertFalse(guardState.accepts(text: "new", selection: NSRange(location: 1, length: 0)))
    }
    func testDictionaryRejectsInvalidKeywordsAndDuplicates() {
        XCTAssertEqual(DictionaryTerms.normalize(" OpenDictate \n\nopendictate\r\n<API>\nSwift"), ["OpenDictate", "API", "Swift"])
    }

    func testDictionarySortsAlphabeticallyIgnoringCase() {
        let english = Locale(identifier: "en")
        XCTAssertEqual(DictionaryTerms.sorted("Swift\napi\nOpenDictate\n\nswift", locale: english), "api\nOpenDictate\nSwift")
        XCTAssertEqual(DictionaryTerms.sorted("Никита\nЖук\nЛебедев\nёлка", locale: Locale(identifier: "ru")), "ёлка\nЖук\nЛебедев\nНикита")
        XCTAssertEqual(DictionaryTerms.sorted(" \n\n", locale: english), "")
    }
    func testSearchIsLocalCaseInsensitiveAndTypoTolerant() {
        XCTAssertTrue(HistorySearch.matches("OpenDictate release résumé", query: "opendictat resume"))
        XCTAssertTrue(HistorySearch.matches("Привет, мир", query: "МИР"))
        XCTAssertFalse(HistorySearch.matches("another topic", query: "meeting"))
        XCTAssertTrue(HistorySearch.matches("anything", query: ""))
    }
    func testFormattingTrimsWhitespaceAndPreservesProviderPunctuation() {
        XCTAssertEqual(TranscriptFormatter.format(" Hello. "), "Hello.")
        XCTAssertEqual(TranscriptFormatter.format("Hello"), "Hello")
        XCTAssertEqual(TranscriptFormatter.format("Wait..."), "Wait...")
        XCTAssertEqual(TranscriptFormatter.format("Really?"), "Really?")
    }
}
