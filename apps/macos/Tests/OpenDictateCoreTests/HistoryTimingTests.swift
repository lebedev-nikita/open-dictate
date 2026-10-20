import XCTest
@testable import OpenDictateCore

final class HistoryTimingTests: XCTestCase {
    func testModelOnlyRecordsAndCapturedFastMetadataDoNotInventTimes() throws {
        let entry = HistoryEntry(text: "Synthetic", mode: "accurate", transcriptionModelID: "test-transcribe",
            postProcessingModelID: TextProcessingModel.id, postProcessingServiceTier: TextProcessingServiceTier.fast.rawValue)
        XCTAssertEqual(HistoryTiming.labels(for: entry, russian: false),
            ["Transcr.: test-transcribe", "Post-proc.: GPT-6 Luna Fast"])
        XCTAssertEqual(try JSONDecoder().decode(HistoryEntry.self, from: JSONEncoder().encode(entry)), entry)
        XCTAssertTrue(HistoryTiming.details(for: entry, russian: true).allSatisfy { $0.duration == nil })
        let live = HistoryEntry(text: "Synthetic", mode: "live", transcriptionModelID: "test-live",
            postProcessingModelID: TextProcessingModel.id)
        XCTAssertEqual(HistoryTiming.labels(for: live, russian: false), ["Live: test-live"])
        XCTAssertNil(live.postProcessingModelID)
    }

    func testLegacyEntriesDecodeWithoutInventedTimingsAndNewFieldsRoundTrip() throws {
        let legacy = Data(#"{"id":"3E485AF8-BE63-4C72-8CDA-EAA66C3AE195","date":0,"text":"Legacy synthetic text","mode":"accurate"}"#.utf8)
        let old = try JSONDecoder().decode(HistoryEntry.self, from: legacy)
        XCTAssertNil(old.transcriptionMillis); XCTAssertNil(old.postProcessingMillis)
        XCTAssertEqual(HistoryTiming.labels(for: old, russian: false), [])
        let measured = HistoryEntry(text: "Measured synthetic text", mode: "accurate", transcriptionMillis: 1250, postProcessingMillis: 450)
        XCTAssertEqual(try JSONDecoder().decode(HistoryEntry.self, from: JSONEncoder().encode(measured)), measured)
        XCTAssertEqual(HistoryTiming.labels(for: measured, russian: true), ["Транскр. 1,3 с", "Постобр. 0,5 с"])
        XCTAssertEqual(HistoryTiming.labels(for: measured, russian: false, abbreviated: false), ["Transcription 1.3 s", "Post-processing 0.5 s"])
    }

    func testLiveOnlyShowsItsLabelAndSkippedStagesRemainAbsent() {
        let live = HistoryEntry(text: "Synthetic live", mode: "live", transcriptionMillis: 9000, postProcessingMillis: 1000)
        XCTAssertNil(live.transcriptionMillis); XCTAssertNil(live.postProcessingMillis)
        XCTAssertEqual(HistoryTiming.labels(for: live, russian: true), ["Live"])
        let accurate = HistoryEntry(text: "Synthetic accurate", mode: "accurate", transcriptionMillis: 800)
        XCTAssertEqual(HistoryTiming.labels(for: accurate, russian: false), ["Transcr. 0.8 s"])
    }

    func testLocalizedTenthsIncludeFastRequestsAndAvoidOverflow() {
        for (milliseconds, expected) in [(Int64(0), "<0.1"), (99, "<0.1"), (100, "0.1"), (1249, "1.2"),
                                         (1250, "1.3"), (62_350, "62.4"), (Int64.max, "9223372036854775.8")] {
            XCTAssertEqual(HistoryTiming.seconds(milliseconds, locale: Locale(identifier: "en_US")), expected)
            XCTAssertEqual(HistoryTiming.seconds(milliseconds, locale: Locale(identifier: "ru_RU")), expected.replacingOccurrences(of: ".", with: ","))
        }
    }
}
