import XCTest
@testable import OpenDictateCore

final class TextProcessingServiceTierTests: XCTestCase {
    func testChoicesKeepTheModelIDSeparateFromTheTier() {
        XCTAssertEqual(TextProcessingModel.defaults.map(TextProcessingModel.title), ["GPT-6 Luna", "GPT-6 Luna Fast"])
        for choice in TextProcessingModel.defaults {
            let model = TextProcessingModel.model(for: choice), tier = TextProcessingModel.tier(for: choice)
            XCTAssertEqual(model, "gpt-6-luna")
            XCTAssertEqual(TextProcessingModel.selection(model: model, tier: tier), choice)
            for request in [OpenAIRequest.transform(source: "Draft", instruction: "Shorten", model: model, serviceTier: tier),
                            OpenAIRequest.postProcessing(source: "Draft", settings: .init(enabled: true), model: model, serviceTier: tier)] {
                XCTAssertEqual(request["model"] as? String, "gpt-6-luna")
                XCTAssertEqual(request["service_tier"] as? String, tier.rawValue)
                XCTAssertEqual(ChatGPTProtocol.planRequest(request)["service_tier"] as? String, tier.rawValue)
                XCTAssertEqual(request["store"] as? Bool, false)
            }
        }
    }

    func testBothTextOperationsDefaultToFastWithoutChangingTranscriptionOrSearch() {
        XCTAssertEqual(OpenAIRequest.transform(source: "Draft", instruction: "Shorten")["service_tier"] as? String, "fast")
        let postProcessing = OpenAIRequest.postProcessing(source: "Draft", settings: .init(enabled: true))
        XCTAssertEqual(postProcessing["service_tier"] as? String, "fast")
        XCTAssertEqual((postProcessing["reasoning"] as? [String: String])?["effort"], "none")
        XCTAssertNil(OpenAIRequest.search(query: "Draft", entries: [])["service_tier"])
        XCTAssertFalse(String(decoding: OpenAIRequest.multipart(wav: Data(), context: .init(), boundary: "test"), as: UTF8.self).contains("service_tier"))
    }

    func testTierSyncSurvivesAnOlderReplicaAndDoesNotChangeTheModel() throws {
        var old = SettingsSyncDocument()
        old.record(["textProcessingModel": "gpt-6-luna"], deviceId: "old", now: 100)
        var local = old
        local.record(["textProcessingServiceTier": "default"], deviceId: "mac", now: 200)
        var remote = old
        remote.record(["dictionary": "Updated"], deviceId: "android", now: 300)
        var left = local; left.merge(remote)
        var right = remote; right.merge(local)
        XCTAssertEqual(left, right)
        let decoded = try SettingsSyncDocument.decode(JSONEncoder().encode(left))
        XCTAssertEqual(decoded.entries["textProcessingModel"]?.value, "gpt-6-luna")
        XCTAssertEqual(decoded.entries["textProcessingServiceTier"]?.value, "default")
        XCTAssertEqual(decoded.entries["dictionary"]?.value, "Updated")
        XCTAssertTrue(old.needsConnectionChoice(with: local))
    }
}
