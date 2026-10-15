import XCTest
@testable import OpenDictate

final class ErrorPresentationTests: XCTestCase {
    @MainActor func testErrorsNotifyInTheSelectedLanguage() {
        let domain = "com.opendictate.tests.errors.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: domain)!
        defer { defaults.removePersistentDomain(forName: domain) }
        var notifications: [String] = []
        let model = AppModel(localOnly: true, defaults: defaults, notifyError: { notifications.append($0) })
        defer { model.shutdown() }

        for language in ["en", "ru"] {
            model.preferences.interfaceLanguage = language
            for error in [DictationError.missingKey, .microphone, .accessibility, .secureField,
                          .excluded, .connection, .api(401), .api(429), .keychain] {
                model.present(error)
                XCTAssertEqual(notifications.last, error.message(russian: language == "ru"))
                XCTAssertEqual(model.message, notifications.last)
                XCTAssertEqual(model.phase, .idle)
            }
        }
        XCTAssertEqual(notifications.count, 18)
    }

    @MainActor func testShortRecordingUpdatesStatusWithoutNotifying() {
        let domain = "com.opendictate.tests.errors.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: domain)!
        defer { defaults.removePersistentDomain(forName: domain) }
        var notifications: [String] = []
        var stateChanges = 0
        let model = AppModel(localOnly: true, defaults: defaults, notifyError: { notifications.append($0) })
        defer { model.shutdown() }
        model.onStateChange = { stateChanges += 1 }

        for language in ["en", "ru"] {
            model.preferences.interfaceLanguage = language
            let previousStateChanges = stateChanges

            model.present(DictationError.tooShort)

            XCTAssertTrue(notifications.isEmpty)
            XCTAssertEqual(model.message, DictationError.tooShort.message(russian: language == "ru"))
            XCTAssertEqual(stateChanges, previousStateChanges + 1)
            XCTAssertEqual(model.phase, .idle)
        }
    }

    @MainActor func testMissingFieldAndCancellationAreCompletelySilent() {
        var notifications: [String] = []
        var stateChanges = 0
        let model = AppModel(localOnly: true, notifyError: { notifications.append($0) })
        defer { model.shutdown() }
        model.onStateChange = { stateChanges += 1 }

        model.present(DictationError.noField)
        model.present(CancellationError())

        XCTAssertTrue(notifications.isEmpty)
        XCTAssertTrue(model.message.isEmpty)
        XCTAssertEqual(stateChanges, 0)
    }

    @MainActor func testMissingFieldDoesNotReplaceAnExistingStatus() {
        var notifications: [String] = []
        let model = AppModel(localOnly: true, notifyError: { notifications.append($0) })
        defer { model.shutdown() }
        model.present(DictationError.connection)
        let previous = model.message

        model.present(DictationError.noField)

        XCTAssertEqual(model.message, previous)
        XCTAssertEqual(notifications.count, 1)
    }

    @MainActor func testUnknownErrorDetailsNeverEnterNotificationCenter() {
        let domain = "com.opendictate.tests.errors.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: domain)!
        defer { defaults.removePersistentDomain(forName: domain) }
        var notifications: [String] = []
        let model = AppModel(localOnly: true, defaults: defaults, notifyError: { notifications.append($0) })
        defer { model.shutdown() }
        let error = NSError(domain: "test", code: 1,
                            userInfo: [NSLocalizedDescriptionKey: "private request contents"])

        model.preferences.interfaceLanguage = "en"
        model.present(error)
        model.preferences.interfaceLanguage = "ru"
        model.present(error)

        XCTAssertEqual(notifications, ["Something went wrong. Try again.", "Произошла ошибка. Попробуйте ещё раз."])
    }

    @MainActor func testCaptureFailureNotifiesButMissingFieldDoesNot() {
        for error in [DictationError.noField, .secureField, .excluded, .accessibility] {
            var notifications: [String] = []
            let model = AppModel(localOnly: true, captureTarget: { _, _ in throw error },
                                 notifyError: { notifications.append($0) })
            defer { model.shutdown() }

            for transform in [false, true] {
                model.toggleLocalRecording(transform: transform)
                XCTAssertEqual(model.phase, .idle)
            }

            if case .noField = error {
                XCTAssertTrue(notifications.isEmpty)
                XCTAssertTrue(model.message.isEmpty)
            } else {
                XCTAssertEqual(notifications.count, 2)
            }
        }
    }
}
