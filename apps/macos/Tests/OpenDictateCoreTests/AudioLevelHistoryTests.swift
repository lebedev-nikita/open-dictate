import XCTest
@testable import OpenDictateCore

final class AudioLevelHistoryTests: XCTestCase {
    func testRecentLevelsStayBoundedAndMoveFromRightToLeft() {
        var history = AudioLevelHistory()
        XCTAssertEqual(history.samples, Array(repeating: 0, count: 10))
        for value in 1...15 { history.append(Float(value) / 20, at: .milliseconds(value * 100)) }
        XCTAssertEqual(history.samples, (6...15).map { Float($0) / 20 })
        history.append(0, at: .milliseconds(1_600))
        XCTAssertEqual(history.samples, (7...15).map { Float($0) / 20 } + [0])
    }

    func testInvalidLevelsCannotProduceInvalidBarHeights() {
        var history = AudioLevelHistory()
        for (index, value) in [Float(-1), 2, .nan, .infinity, -.infinity].enumerated() {
            history.append(value, at: .milliseconds(index * 100))
        }
        XCTAssertEqual(Array(history.samples.suffix(5)), [0, 1, 0, 0, 0])
    }

    func testEndingSessionClearsPreviousInputEnvelope() {
        var history = AudioLevelHistory()
        for index in 0..<1_000 { history.append(0.8, at: .milliseconds(index * 100)) }
        XCTAssertEqual(history.samples.count, 10)
        history.reset()
        XCTAssertEqual(history.samples, Array(repeating: 0, count: 10))
        XCTAssertTrue(history.append(0.4, at: .zero))
        XCTAssertEqual(history.samples.last, 0.4)
    }

    func testFastMicrophoneCallbacksKeepTheOriginalTenUpdatesPerSecond() {
        for callbackMilliseconds in [5, 40, 43] {
            var history = AudioLevelHistory()
            var updates = 0
            for time in stride(from: 0, to: 1_000, by: callbackMilliseconds) {
                if history.append(Float(time) / 1_000, at: .milliseconds(time)) { updates += 1 }
            }
            XCTAssertEqual(updates, 10, "Callback interval: \(callbackMilliseconds) ms")
            XCTAssertLessThan(history.samples[0], 0.1)
            XCTAssertGreaterThanOrEqual(history.samples[9], 0.9)
        }
    }

    func testEachDisplayUpdateUsesTheCurrentLevelAndKeepsTheCadenceAfterAGap() {
        var history = AudioLevelHistory()
        XCTAssertTrue(history.append(0.1, at: .zero))
        XCTAssertFalse(history.append(0.2, at: .milliseconds(40)))
        XCTAssertFalse(history.append(0.3, at: .milliseconds(80)))
        XCTAssertEqual(history.samples.last, 0.1)
        XCTAssertTrue(history.append(0.4, at: .milliseconds(120)))
        XCTAssertFalse(history.append(0.5, at: .milliseconds(160)))
        XCTAssertTrue(history.append(0.6, at: .milliseconds(200)))
        XCTAssertTrue(history.append(0.7, at: .milliseconds(1_040)))
        XCTAssertFalse(history.append(0.8, at: .milliseconds(1_040)))
        XCTAssertTrue(history.append(0.9, at: .milliseconds(1_100)))
        XCTAssertEqual(Array(history.samples.suffix(5)), [0.1, 0.4, 0.6, 0.7, 0.9])
    }
}
