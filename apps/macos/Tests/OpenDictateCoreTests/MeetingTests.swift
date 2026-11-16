import XCTest
@testable import OpenDictateCore

final class MeetingTests: XCTestCase {
    private let utc = TimeZone(identifier: "UTC")!

    private func meeting(source: String? = "Zoom") -> Meeting {
        var meeting = Meeting(startedAt: Date(timeIntervalSince1970: 1_794_830_400), // 2026-11-16 12:00 UTC
                              sourceName: source, sourceBundleID: source == nil ? nil : "us.zoom.xos")
        meeting.durationMillis = 47 * 60_000 + 10_000
        return meeting
    }

    func testChannelsMergeByStartTimeAndKeepArrivalOrderForEqualStarts() {
        var meeting = meeting()
        let late = MeetingUtterance(speaker: Meeting.meID, startMillis: 9_000, durationMillis: 1_000)
        let early = MeetingUtterance(speaker: Meeting.otherID, startMillis: 2_000, durationMillis: 3_000)
        let tieFirst = MeetingUtterance(speaker: Meeting.meID, startMillis: 5_000, durationMillis: 500)
        let tieSecond = MeetingUtterance(speaker: Meeting.otherID, startMillis: 5_000, durationMillis: 500)
        for utterance in [late, early, tieFirst, tieSecond] { meeting.insert(utterance) }
        XCTAssertEqual(meeting.utterances.map(\.id), [early.id, tieFirst.id, tieSecond.id, late.id])
    }

    func testMarkdownMatchesTheApprovedShape() {
        var meeting = meeting()
        meeting.insert(MeetingUtterance(speaker: Meeting.meID, startMillis: 4_200, durationMillis: 2_000,
                                        text: "Let's start with the release plan.", status: .recognized))
        meeting.insert(MeetingUtterance(speaker: Meeting.otherID, startMillis: 11_900, durationMillis: 2_000,
                                        text: "Agreed, channels first.", status: .recognized))
        XCTAssertEqual(MeetingFormat.markdown(meeting, russian: false, timeZone: utc), """
            # Meeting 2026-11-16, Zoom, 47 min

            **[00:00:04] Me:** Let's start with the release plan.

            **[00:00:11] Other speaker:** Agreed, channels first.

            """)
        XCTAssertEqual(MeetingFormat.markdown(meeting, russian: true, timeZone: utc), """
            # Встреча 2026-11-16, Zoom, 47 мин

            **[00:00:04] Я:** Let's start with the release plan.

            **[00:00:11] Собеседник:** Agreed, channels first.

            """)
    }

    func testRenamingAppliesToTheWholeTranscriptAndBlankRestoresTheRole() {
        var meeting = meeting(source: nil)
        for start in [1_000, 3_000] {
            meeting.insert(MeetingUtterance(speaker: Meeting.otherID, startMillis: start, durationMillis: 500,
                                            text: "Hi", status: .recognized))
        }
        meeting.rename(speaker: Meeting.otherID, to: "  Anna *Lead*\nPM ")
        let markdown = MeetingFormat.markdown(meeting, russian: false, timeZone: utc)
        XCTAssertTrue(markdown.hasPrefix("# Meeting 2026-11-16, 47 min\n"), "All system audio has no source name")
        XCTAssertEqual(markdown.components(separatedBy: "**[00:00:01] Anna \\*Lead\\* PM:** Hi").count, 2)
        XCTAssertTrue(markdown.contains("**[00:00:03] Anna \\*Lead\\* PM:** Hi"))
        meeting.rename(speaker: Meeting.otherID, to: "   ")
        XCTAssertNil(meeting.speaker(Meeting.otherID)?.name)
        XCTAssertEqual(meeting.speaker(Meeting.otherID)?.displayName(russian: true), "Собеседник")
        meeting.rename(speaker: Meeting.meID, to: String(repeating: "x", count: 200))
        XCTAssertEqual(meeting.speaker(Meeting.meID)?.name?.count, MeetingSpeaker.maximumNameLength)
    }

    func testWaitingUtterancesAreOmittedAndFailuresStayVisible() {
        var meeting = meeting()
        meeting.insert(MeetingUtterance(speaker: Meeting.meID, startMillis: 1_000, durationMillis: 500))
        meeting.insert(MeetingUtterance(speaker: Meeting.otherID, startMillis: 2_000, durationMillis: 500, status: .failed))
        meeting.insert(MeetingUtterance(speaker: Meeting.meID, startMillis: 3_000, durationMillis: 500,
                                        text: "Line one\nline two", status: .recognized))
        let markdown = MeetingFormat.markdown(meeting, russian: false, timeZone: utc)
        XCTAssertFalse(markdown.contains("[00:00:01]"))
        XCTAssertTrue(markdown.contains("**[00:00:02] Other speaker:** *(not recognized)*"))
        XCTAssertTrue(markdown.contains("**[00:00:03] Me:** Line one line two"))
        XCTAssertEqual(meeting.pendingCount, 1)
        meeting.failPending()
        XCTAssertEqual(meeting.pendingCount, 0)
        XCTAssertTrue(MeetingFormat.markdown(meeting, russian: true, timeZone: utc).contains("**[00:00:01] Я:** *(не распознано)*"))
    }

    func testSearchIgnoresCaseAndDiacriticsAndMatchesSpeakerNames() {
        var meeting = meeting()
        meeting.insert(MeetingUtterance(speaker: Meeting.meID, startMillis: 0, durationMillis: 1, text: "Ещё раз о релизе", status: .recognized))
        meeting.insert(MeetingUtterance(speaker: Meeting.otherID, startMillis: 1, durationMillis: 1, text: "Café plans", status: .recognized))
        XCTAssertEqual(meeting.search("еще", russian: true).count, 1)
        XCTAssertEqual(meeting.search("CAFE", russian: false).count, 1)
        meeting.rename(speaker: Meeting.otherID, to: "Anna")
        XCTAssertEqual(meeting.search("anna", russian: false).map(\.text), ["Café plans"])
        XCTAssertEqual(meeting.search("  ", russian: false).count, 2)
    }

    func testStoredFormatRoundTripsWithSpeakerIDs() throws {
        var meeting = meeting()
        meeting.insert(MeetingUtterance(speaker: Meeting.otherID, startMillis: 5, durationMillis: 6, text: "x", status: .recognized))
        meeting.rename(speaker: Meeting.meID, to: "Nikita")
        let encoder = JSONEncoder(); encoder.dateEncodingStrategy = .iso8601
        let decoder = JSONDecoder(); decoder.dateDecodingStrategy = .iso8601
        let data = try encoder.encode(meeting)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(json["version"] as? Int, 1)
        XCTAssertEqual((json["utterances"] as? [[String: Any]])?.first?["speaker"] as? String, "other")
        let decoded = try decoder.decode(Meeting.self, from: data)
        XCTAssertEqual(decoded, meeting)
        XCTAssertTrue(decoded.decodedValid())
        var unknownSpeaker = meeting
        unknownSpeaker.insert(MeetingUtterance(speaker: "ghost", startMillis: 0, durationMillis: 0))
        XCTAssertFalse(unknownSpeaker.decodedValid())
    }

    func testFormattingHelpers() {
        XCTAssertEqual(MeetingFormat.timestamp(4_999), "00:00:04")
        XCTAssertEqual(MeetingFormat.timestamp(3 * 3_600_000 + 61_000), "03:01:01")
        XCTAssertEqual(MeetingFormat.elapsed(65_000), "01:05")
        XCTAssertEqual(MeetingFormat.elapsed(3_600_000), "01:00:00")
        XCTAssertEqual(MeetingFormat.minutes(10_000, russian: false), "1 min")
        XCTAssertEqual(MeetingFormat.minutes(47 * 60_000 + 40_000, russian: true), "48 мин")
        var unsafe = meeting(source: "Team: A/B")
        unsafe.startedAt = Date(timeIntervalSince1970: 1_794_830_700)
        XCTAssertEqual(MeetingFormat.fileName(unsafe, russian: false, timeZone: utc), "Meeting 2026-11-16 12-05 Team- A-B.md")
        XCTAssertEqual(MeetingFormat.fileName(meeting(source: nil), russian: true, timeZone: utc), "Встреча 2026-11-16 12-00.md")
    }

    func testRetriesBackOffAndOnlyRetryRecoverableFailures() {
        XCTAssertEqual((1...8).map(MeetingLimits.retryDelaySeconds), [1, 2, 4, 8, 16, 30, 30, 30])
        XCTAssertTrue(MeetingLimits.isRetryable(status: nil), "Network failures and timeouts retry")
        for status in [401, 429, 500, 503] { XCTAssertTrue(MeetingLimits.isRetryable(status: status)) }
        for status in [400, 404, 413, 415] { XCTAssertFalse(MeetingLimits.isRetryable(status: status)) }
        // Fifteen minutes of 24 kHz mono PCM16 bound untranscribed audio in memory.
        XCTAssertEqual(MeetingLimits.maximumPendingAudioBytes, 43_200_000)
    }
}
