import XCTest
import OpenDictateCore
@testable import OpenDictate

private final class MockHTTP: URLProtocol, @unchecked Sendable {
    static var handler: ((URLRequest) throws -> (Int, Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, body) = try Self.handler!(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

final class HTTPTests: XCTestCase {
    private func client() -> OpenAIClient {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [MockHTTP.self]
        return OpenAIClient(session: URLSession(configuration: configuration))
    }

    func testFileResponseAndRequestUseDirectAuthorizedUpload() async throws {
        MockHTTP.handler = { request in
            XCTAssertEqual(request.url, OpenAIRequest.transcriptionURL)
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-test-key")
            XCTAssertEqual(request.timeoutInterval, 120)
            XCTAssertTrue(request.value(forHTTPHeaderField: "Content-Type")?.hasPrefix("multipart/form-data; boundary=") == true)
            return (200, Data("{\"text\":\"Synthetic transcript.\"}".utf8))
        }
        let text = try await client().transcribe(pcm: Data([1, 2]), key: "synthetic-test-key", context: .init(), timeout: 120)
        XCTAssertEqual(text, "Synthetic transcript.")
    }

    func testRawErrorBodyNeverBecomesUserMessage() async throws {
        MockHTTP.handler = { _ in (401, Data("{\"error\":{\"message\":\"private transcript and credential\"}}".utf8)) }
        do { _ = try await client().transcribe(pcm: Data([1, 2]), key: "synthetic-test-key", context: .init(), timeout: 60); XCTFail("Should fail") }
        catch let error as DictationError {
            if case .api(401) = error {} else { XCTFail("Wrong status") }
            XCTAssertFalse(error.localizedDescription.contains("private transcript"))
            XCTAssertTrue(error.message(russian: true).contains("ключ"))
        }
    }

    func testPunctuationUsesDirectLunaRequestAndRejectsRewriteOrFailedResponse() async throws {
        let client = client()
        for (status, body, expected) in [
            (200, #"{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"transformed_text\":\"Привет, мир!\",\"message\":null}"}]}]}"#, "Привет, мир!"),
            (200, #"{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{\"transformed_text\":\"Другой текст.\",\"message\":null}"}]}]}"#, "привет мир"),
            (200, #"{"status":"incomplete","output":[]}"#, "привет мир"),
            (429, #"{"error":{"message":"private provider payload"}}"#, "привет мир")
        ] {
            MockHTTP.handler = { request in
                XCTAssertEqual(request.url, OpenAIRequest.responsesURL)
                XCTAssertEqual(request.timeoutInterval, 10)
                let bodyData = request.httpBody ?? request.httpBodyStream.map { stream -> Data in
                    stream.open(); defer { stream.close() }
                    var data = Data(), buffer = [UInt8](repeating: 0, count: 4096)
                    while stream.hasBytesAvailable {
                        let count = stream.read(&buffer, maxLength: buffer.count)
                        if count <= 0 { break }
                        data.append(contentsOf: buffer.prefix(count))
                    }
                    return data
                }
                let payload = try JSONSerialization.jsonObject(with: XCTUnwrap(bodyData)) as! [String: Any]
                let input = try JSONSerialization.jsonObject(with: Data((payload["input"] as! String).utf8)) as! [String: String]
                XCTAssertEqual(input["instruction"], "Use exclamation marks.")
                XCTAssertEqual(input["source_text"], "привет мир")
                return (status, Data(body.utf8))
            }
            let result = try await client.correctPunctuation(source: "привет мир", key: "synthetic-test-key", enabled: true, mode: .accurate,
                formattingRules: "Use exclamation marks.")
            XCTAssertEqual(result, expected)
        }
        MockHTTP.handler = { _ in XCTFail("Disabled/Live must not request punctuation"); return (500, Data()) }
        for (enabled, mode) in [(false, DictationMode.accurate), (true, .live)] {
            let result = try await client.correctPunctuation(source: "привет мир", key: "synthetic-test-key", enabled: enabled, mode: mode,
                formattingRules: "Use exclamation marks.")
            XCTAssertEqual(result, "привет мир")
        }
    }

    func testStructuredTransformationParsesOnlyCompletedOutput() async throws {
        MockHTTP.handler = { request in
            XCTAssertEqual(request.url, OpenAIRequest.responsesURL)
            return (200, try JSONSerialization.data(withJSONObject: ["status": "completed", "output": [
                ["type": "message", "content": [["type": "output_text", "text": "{\"transformed_text\":\"Short text\",\"message\":null}"]]]
            ]]))
        }
        let (text, message) = try await client().transform(source: "Long synthetic text", instruction: "Shorten", key: "synthetic-test-key", model: "gpt-6-luna")
        XCTAssertEqual(text, "Short text"); XCTAssertNil(message)
    }
}
