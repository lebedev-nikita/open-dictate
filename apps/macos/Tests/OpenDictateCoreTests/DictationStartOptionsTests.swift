import XCTest
@testable import OpenDictateCore

final class DictationStartOptionsTests: XCTestCase {
    func testOrdinarySessionsPreserveTheSelectedModeAndSavedRules() {
        for mode in DictationMode.allCases {
            for enabled in [false, true] {
                let saved = PostProcessingSettings(enabled: enabled)
                let options = DictationStartOptions(mode: mode, postProcessing: saved)
                XCTAssertEqual(options.mode, mode)
                XCTAssertEqual(options.postProcessing, saved.forMode(mode))
            }
        }
    }

    func testOneSessionOverrideUsesAccurateAndLeavesSavedSettingsUntouched() {
        for mode in DictationMode.allCases {
            for enabled in [false, true] {
                let saved = PostProcessingSettings(enabled: enabled, punctuation: false)
                let options = DictationStartOptions(mode: mode, postProcessing: saved, postProcessingOverride: !enabled)
                XCTAssertEqual(options.mode, .accurate)
                XCTAssertEqual(options.postProcessing.shouldProcess, !enabled)
                XCTAssertFalse(saved.punctuation)
                XCTAssertEqual(saved.enabled, enabled)
                XCTAssertEqual(DictationStartOptions(mode: mode, postProcessing: saved).postProcessing, saved.forMode(mode))
            }
        }
    }
}
