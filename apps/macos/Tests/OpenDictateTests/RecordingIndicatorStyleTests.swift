import Combine
import XCTest
@testable import OpenDictate

final class RecordingIndicatorStyleTests: XCTestCase {
    func testExistingOrUnknownPreferencesKeepCompactIndicator() async {
        await MainActor.run {
            let domain = "com.opendictate.tests.indicator.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: domain)!
            defer { defaults.removePersistentDomain(forName: domain) }
            XCTAssertEqual(Preferences(defaults: defaults).indicatorStyle, .compact)
            defaults.set("unknown", forKey: "indicatorStyle")
            XCTAssertEqual(Preferences(defaults: defaults).indicatorStyle, .compact)
        }
    }

    func testIndicatorChoicePersistsWithoutTurningStatusBackOn() async {
        await MainActor.run {
            let domain = "com.opendictate.tests.indicator.\(UUID().uuidString)"
            let defaults = UserDefaults(suiteName: domain)!
            defer { defaults.removePersistentDomain(forName: domain) }
            let preferences = Preferences(defaults: defaults)
            preferences.showStatus = false
            for style in RecordingIndicatorStyle.allCases {
                preferences.indicatorStyle = style
                let reloaded = Preferences(defaults: defaults)
                XCTAssertEqual(reloaded.indicatorStyle, style)
                XCTAssertFalse(reloaded.showStatus)
            }
        }
    }

    func testMicrophoneLevelsPublishToHUDAndClearOnReset() async {
        await MainActor.run {
            let levels = RecordingAudioLevels()
            var frames = [[Float]]()
            let subscription = levels.$history.sink { frames.append($0.samples) }
            levels.append(0.1, at: .zero)
            levels.append(0.4, at: .milliseconds(40))
            XCTAssertEqual(frames.count, 2, "Suppressed audio updates must not rebuild the HUD")
            levels.append(0.8, at: .milliseconds(120))
            levels.reset()
            XCTAssertEqual(Array(frames[2].suffix(2)), [0.1, 0.8])
            XCTAssertEqual(frames.last, Array(repeating: 0, count: 10))
            levels.append(0.6, at: .milliseconds(140))
            XCTAssertEqual(Array(frames.last!.suffix(2)), [0, 0.6])
            withExtendedLifetime(subscription) {}
        }
    }
}
