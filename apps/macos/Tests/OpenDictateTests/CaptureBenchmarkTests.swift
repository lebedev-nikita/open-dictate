import XCTest
@testable import OpenDictate

final class CaptureBenchmarkTests: XCTestCase {
    func testNearestRankDistribution() throws {
        XCTAssertNil(CaptureBenchmark.Distribution([]))
        let single = try XCTUnwrap(CaptureBenchmark.Distribution([7]))
        XCTAssertEqual(single, CaptureBenchmark.Distribution([7, 7]))
        XCTAssertEqual(single.p50, 7); XCTAssertEqual(single.p90, 7); XCTAssertEqual(single.max, 7)
        let ten = try XCTUnwrap(CaptureBenchmark.Distribution((1...10).reversed().map(Double.init)))
        XCTAssertEqual(ten.p50, 5); XCTAssertEqual(ten.p90, 9); XCTAssertEqual(ten.max, 10)
    }

    func testCycleJSONContainsOnlyTimingFields() {
        var cycle = CaptureBenchmark.Cycle(scenario: "warm", round: 3)
        cycle.firstPCMMs = 12.34; cycle.failure = "noAudio"
        XCTAssertEqual(cycle.json, "{\"scenario\":\"warm\",\"round\":3,"
            + "\"startReturnedMs\":null,\"firstCallbackMs\":null,\"firstPCMMs\":12.3,\"firstChunkMs\":null,"
            + "\"stopMs\":null,\"maxChunkGapMs\":null,\"coverage\":null,\"graphs\":0,\"configurationChanges\":0,"
            + "\"failure\":\"noAudio\"}")
    }
}
