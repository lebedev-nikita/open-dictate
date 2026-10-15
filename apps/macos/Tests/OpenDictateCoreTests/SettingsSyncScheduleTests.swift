import XCTest
@testable import OpenDictateCore

final class SettingsSyncScheduleTests: XCTestCase {
    func testTypingWaitsFifteenSecondsAfterLastEditEvenWithAutomaticPull() {
        var queue = SettingsSyncSchedule()
        queue.changed(now: 0); queue.changed(now: 2_000)
        queue.refreshIfStale(now: 3_000)
        XCTAssertFalse(queue.begin(now: 16_999))
        XCTAssertEqual(queue.delayUntilReady(now: 3_000), 14_000)
        XCTAssertTrue(queue.begin(now: 17_000))
        queue.finish(now: 17_100, completion: .success)
        XCTAssertFalse(queue.begin(now: 18_000))
    }
    func testEditsDuringRequestRemainQueuedAndDoNotRunConcurrently() {
        var queue = SettingsSyncSchedule()
        queue.refresh(); XCTAssertTrue(queue.begin(now: 0))
        queue.changed(now: 100)
        XCTAssertTrue(queue.hasPendingChange)
        XCTAssertFalse(queue.begin(now: 20_000))
        queue.finish(now: 1_000, completion: .deferred)
        XCTAssertFalse(queue.begin(now: 15_099))
        XCTAssertTrue(queue.begin(now: 15_100))
        queue.changed(now: 16_000)
        queue.finish(now: 17_000, completion: .success)
        XCTAssertFalse(queue.begin(now: 30_999))
        XCTAssertTrue(queue.begin(now: 31_000))
    }
    func testBackgroundChecksWaitFifteenMinutesAfterSuccess() {
        var queue = SettingsSyncSchedule()
        queue.refreshIfStale(now: 0, maxAge: SettingsSyncSchedule.backgroundInterval)
        XCTAssertTrue(queue.begin(now: 0))
        queue.finish(now: 100, completion: .success)
        XCTAssertEqual(queue.delayUntilReady(now: 100, includePeriodic: true), 900_000)
        queue.refreshIfStale(now: 900_099, maxAge: SettingsSyncSchedule.backgroundInterval)
        XCTAssertFalse(queue.begin(now: 900_099))
        queue.refreshIfStale(now: 900_100, maxAge: SettingsSyncSchedule.backgroundInterval)
        XCTAssertTrue(queue.begin(now: 900_100))
    }
    func testReopenAndWakeOnlyCheckStaleDataAndCoalesceWhileBusy() {
        var queue = SettingsSyncSchedule()
        queue.refresh(); XCTAssertTrue(queue.begin(now: 0))
        queue.refreshIfStale(now: 10)
        queue.finish(now: 100, completion: .success)
        queue.refreshIfStale(now: 300_099)
        XCTAssertFalse(queue.begin(now: 300_099))
        queue.refreshIfStale(now: 300_100)
        XCTAssertTrue(queue.begin(now: 300_100))
        queue.refreshIfStale(now: 400_000)
        queue.finish(now: 400_100, completion: .success)
        XCTAssertFalse(queue.begin(now: 400_100))
    }
    func testFailuresBackOffAndAutomaticChecksOrEditsCannotBypassRetry() {
        var queue = SettingsSyncSchedule()
        queue.refresh(); var time: Int64 = 0
        for delay: Int64 in [60_000, 300_000, 900_000, 1_800_000, 1_800_000] {
            XCTAssertTrue(queue.begin(now: time))
            queue.finish(now: time, completion: .retry)
            queue.changed(now: time)
            queue.refreshIfStale(now: time + 30_000)
            XCTAssertEqual(queue.delayUntilReady(now: time), delay)
            XCTAssertFalse(queue.begin(now: time + delay - 1))
            time += delay
        }
        XCTAssertTrue(queue.begin(now: time))
        queue.finish(now: time, completion: .success)
        queue.refresh(); XCTAssertTrue(queue.begin(now: time))
        queue.finish(now: time, completion: .retry)
        XCTAssertEqual(queue.delayUntilReady(now: time), 60_000)
    }
    func testAuthorizationPausesAllAutomaticWorkUntilExplicitRequest() {
        var queue = SettingsSyncSchedule()
        queue.refresh(); XCTAssertTrue(queue.begin(now: 0))
        queue.finish(now: 0, completion: .authorization)
        queue.changed(now: 100); queue.refreshIfStale(now: 900_000)
        queue.networkRestored(now: 900_000)
        XCTAssertFalse(queue.begin(now: 900_000))
        XCTAssertNil(queue.delayUntilReady(now: 900_000, includePeriodic: true))
        queue.refresh(); XCTAssertTrue(queue.begin(now: 900_000))
    }
    func testNetworkRecoverySkipsFreshDataButResumesEditsAndFailedWork() {
        var queue = SettingsSyncSchedule()
        queue.refresh(); XCTAssertTrue(queue.begin(now: 0))
        queue.finish(now: 0, completion: .success)
        queue.networkRestored(now: 100)
        XCTAssertFalse(queue.begin(now: 100))
        queue.changed(now: 200); queue.networkRestored(now: 300)
        XCTAssertFalse(queue.begin(now: 15_199))
        XCTAssertTrue(queue.begin(now: 15_200))
        queue.finish(now: 15_200, completion: .retry)
        queue.networkRestored(now: 16_000)
        XCTAssertTrue(queue.begin(now: 16_000))
        queue.finish(now: 16_000, completion: .success)
        queue.networkRestored(now: 316_000)
        XCTAssertTrue(queue.begin(now: 316_000))
    }
    func testRefreshWithoutFlushingBypassesBackoffButPreservesUnfinishedEdits() {
        var queue = SettingsSyncSchedule()
        queue.refresh(); XCTAssertTrue(queue.begin(now: 0))
        queue.finish(now: 0, completion: .retry)
        queue.changed(now: 100); queue.refresh()
        XCTAssertFalse(queue.begin(now: 15_099))
        XCTAssertTrue(queue.begin(now: 15_100))
    }
    func testImmediateRefreshBypassesEditDelayAndRetryBackoff() {
        var queue = SettingsSyncSchedule()
        queue.refresh(); XCTAssertTrue(queue.begin(now: 0))
        queue.finish(now: 0, completion: .retry)
        queue.changed(now: 100); queue.refresh(immediately: true)
        XCTAssertEqual(queue.delayUntilReady(now: 100), 0)
        XCTAssertTrue(queue.begin(now: 100))
        queue.finish(now: 200, completion: .success)
        XCTAssertFalse(queue.begin(now: 15_100))
    }
    func testEditsDuringImmediateRefreshKeepTheirAutomaticDelayAfterSuccess() {
        var queue = SettingsSyncSchedule()
        queue.changed(now: 0); queue.refresh(immediately: true)
        XCTAssertTrue(queue.begin(now: 100))
        queue.changed(now: 200)
        XCTAssertFalse(queue.begin(now: 300))
        queue.finish(now: 400, completion: .success)
        XCTAssertTrue(queue.hasPendingChange)
        XCTAssertEqual(queue.delayUntilReady(now: 400), 14_800)
        XCTAssertFalse(queue.begin(now: 15_199))
        XCTAssertTrue(queue.begin(now: 15_200))
    }
}
