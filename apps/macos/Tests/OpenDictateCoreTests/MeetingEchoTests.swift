import XCTest
@testable import OpenDictateCore

/// Cases come from simulated speaker echo (far end delayed 60 ms, reverberant, −6/−12/−24 dB)
/// recognized by gpt-transcribe; the strings are the actual recognized texts.
final class MeetingEchoTests: XCTestCase {
    private let call = [
        MeetingEcho.Span(startMillis: 220, endMillis: 9_380, text: "Let's start with the release plan. This week we need to finish meeting recording. Channels separate the roles, so we don't need a diarization model."),
        MeetingEcho.Span(startMillis: 15_020, endMillis: 20_460, text: "We cut each phrase at pauses and send it separately. Silence is not sent and not billed."),
        MeetingEcho.Span(startMillis: 29_420, endMillis: 33_080, text: "At the end of the meeting, you can copy the transcript as Markdown."),
        MeetingEcho.Span(startMillis: 33_980, endMillis: 40_360, text: "The question is which pause counts as the end of a phrase. A pause that is two short cut sentences in half."),
    ]

    private func clean(_ text: String, _ start: Int, _ end: Int) -> String? {
        MeetingEcho.removingEcho(from: .init(startMillis: start, endMillis: end, text: text), call: call)
    }

    func testPureEchoIsRemoved() {
        XCTAssertNil(clean("We cut each phrase at pauses and send it separately. Silence is not sent and not billed.", 15_100, 20_520))
        XCTAssertNil(clean("At the end of the meeting, you can copy the transcript as Markdown.", 29_480, 33_120))
    }

    func testOwnInterjectionsInsideEchoAreKept() {
        XCTAssertEqual(clean("We cut each phrase at pauses and send it separately. Okay. Silence is not sent and not billed.", 15_120, 20_500), "Okay.")
        XCTAssertEqual(clean("Right. At the end of the meeting, you can copy the transcript as Markdown.", 29_500, 33_100), "Right.")
    }

    func testMisrecognizedEchoSentencesGoWithTheirEcho() {
        XCTAssertEqual(clean("Let's start with the release plan. Sounds good to me. This is the finish meeting recording. Channels separate the roles, so we need a dialogue. Yes, I agree.", 60, 9_260),
                       "Sounds good to me. Yes, I agree.")
        XCTAssertEqual(clean("Let's start with the release plan. This meeting needs to finish meeting recording. Channels separate the roles, so we don't need a dialogization model. Yes, I agree.", 60, 9_260),
                       "Yes, I agree.")
        XCTAssertNil(clean("A pause that is too short cuts sentences in half.", 36_000, 40_400))
    }

    func testOwnSpeechWithoutOverlappingCallTextIsUnchanged() {
        XCTAssertEqual(clean("What about the backlog during an outage?", 10_440, 14_160), "What about the backlog during an outage?")
        // Same words, but far from any call utterance in time.
        XCTAssertEqual(clean("Silence is not sent and not billed.", 60_000, 62_000), "Silence is not sent and not billed.")
    }

    func testShortRepliesDuringTheOtherSideAreKept() {
        let call = [MeetingEcho.Span(startMillis: 0, endMillis: 6_000, text: "Yes, so we ship on Friday, yes.")]
        XCTAssertEqual(MeetingEcho.removingEcho(from: .init(startMillis: 2_000, endMillis: 2_600, text: "Yes."), call: call), "Yes.")
        // Echo of a two-word utterance that coincides in time is still removed.
        let short = [MeetingEcho.Span(startMillis: 1_000, endMillis: 1_800, text: "Thanks everyone.")]
        XCTAssertNil(MeetingEcho.removingEcho(from: .init(startMillis: 1_050, endMillis: 1_850, text: "Thanks, everyone!"), call: short))
    }

    func testRussianNormalizesYoAndCase() {
        let call = [MeetingEcho.Span(startMillis: 0, endMillis: 4_000, text: "Давайте ещё раз посмотрим на план релиза.")]
        XCTAssertNil(MeetingEcho.removingEcho(from: .init(startMillis: 100, endMillis: 4_100,
                                                         text: "давайте еще раз посмотрим на план релиза"), call: call))
        XCTAssertEqual(MeetingEcho.removingEcho(from: .init(startMillis: 100, endMillis: 4_100,
                                                           text: "Давайте ещё раз посмотрим на план релиза. Согласен."), call: call), "Согласен.")
    }
}
