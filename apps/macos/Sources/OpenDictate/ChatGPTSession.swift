import Foundation
import Security
import OpenDictateCore

struct ChatGPTRecord: Codable, Sendable {
    var client: String
    var subject = ""
    var email = ""
    var access = ""
    var refresh = ""
    var idToken = ""
    var scope = ""
    var expires: TimeInterval = 0
}
struct ChatGPTJournal: Codable { var records = [String: ChatGPTRecord](); var active: String?; var last: String? }
enum ChatGPTStore {
    static func load() throws -> ChatGPTJournal {
        do {
            guard let data = try CredentialVault.shared.read(.chatGPT) else { return ChatGPTJournal() }
            return try JSONDecoder().decode(ChatGPTJournal.self, from: data)
        } catch { throw ChatGPTError.unavailable }
    }
    static func save(_ journal: ChatGPTJournal) throws {
        do { try CredentialVault.shared.write(JSONEncoder().encode(journal), to: .chatGPT) }
        catch { throw ChatGPTError.unavailable }
    }
}
private final class ChatGPTNoRedirects: NSObject, URLSessionTaskDelegate {
    func urlSession(_ session: URLSession, task: URLSessionTask, willPerformHTTPRedirection response: HTTPURLResponse,
                    newRequest request: URLRequest, completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
}
actor ChatGPTSession {
    static let shared = ChatGPTSession()
    private var journal: ChatGPTJournal?
    private let inMemory: Bool
    private var refreshes = [String: Task<ChatGPTRecord, Error>]()
    private let session: URLSession
    init(session: URLSession? = nil, initial: ChatGPTJournal? = nil) {
        self.journal = initial; self.inMemory = initial != nil
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil; config.httpCookieStorage = nil; config.timeoutIntervalForRequest = 45; config.timeoutIntervalForResource = 60
        self.session = session ?? URLSession(configuration: config, delegate: ChatGPTNoRedirects(), delegateQueue: nil)
    }
    private func load() throws -> ChatGPTJournal { if let journal { return journal }; let saved = try ChatGPTStore.load(); journal = saved; return saved }
    private func save(_ journal: ChatGPTJournal) throws { if !inMemory { try ChatGPTStore.save(journal) }; self.journal = journal }
    func active() throws -> ChatGPTRecord? { let saved = try load(); return saved.active.flatMap { saved.records[$0] }.flatMap { $0.access.isEmpty ? nil : $0 } }
    func registration(id: String? = nil) throws -> ChatGPTRecord? { let saved = try load(); return (id ?? saved.last).flatMap { saved.records[$0] } }
    func exchange(_ attempt: ChatGPTAuthorization.Result, expected: ChatGPTRecord?) async throws -> ChatGPTRecord {
        var saved = try load()
        // A fresh code can be requested using this registration if exchange fails.
        if saved.records[attempt.client] == nil { saved.records[attempt.client] = ChatGPTRecord(client: attempt.client) }
        saved.last = attempt.client; try save(saved)
        let tokens = try await request(ChatGPTProtocol.tokenURL, fields: ["grant_type": "authorization_code", "client_id": attempt.client,
            "code": attempt.code, "redirect_uri": attempt.redirect, "code_verifier": attempt.verifier, "resource": ChatGPTProtocol.resource])
        let jwks = try await request(URL(string: "https://auth.openai.com/.well-known/jwks.json")!)
        guard let idToken = tokens["id_token"] as? String else { throw ChatGPTError.unavailable }
        let identity = try ChatGPTProtocol.identity(idToken, jwks: jwks, client: attempt.client, nonce: attempt.nonce, now: Date().timeIntervalSince1970)
        if let expected, !expected.subject.isEmpty, identity["sub"] as? String != expected.subject { throw ChatGPTError.unavailable }
        if (tokens["scope"] as? String)?.split(separator: " ").contains("chatgpt.tokens.use.direct") != true {
            let pending = ChatGPTRecord(client: attempt.client, subject: identity["sub"] as! String,
                email: identity["email"] as? String ?? "", idToken: idToken, scope: tokens["scope"] as? String ?? "")
            var saved = try load(); saved.records[attempt.client] = pending; try save(saved)
            throw ChatGPTError.unavailable
        }
        var record = try credentials(tokens, client: attempt.client)
        record.idToken = idToken; record.subject = identity["sub"] as! String; record.email = identity["email"] as? String ?? ""
        let catalog = try await request(URL(string: ChatGPTProtocol.resource + "/models")!, bearer: record.access)
        guard let models = catalog["models"] as? [[String: Any]],
              models.contains(where: { $0["slug"] as? String == "gpt-6-luna" && $0["visibility"] as? String == "list" }) else { throw ChatGPTError.unavailable }
        try Task.checkCancellation()
        return record
    }
    func activate(_ record: ChatGPTRecord) throws { var saved = try load(); saved.records[record.client] = record; saved.last = record.client; saved.active = record.client; try save(saved) }
    func disconnect() async throws {
        var saved = try load()
        guard let id = saved.active, var record = saved.records[id] else { return }
        refreshes[id]?.cancel(); refreshes[id] = nil
        saved.active = nil; record.access = ""; record.refresh = ""; record.idToken = ""
        saved.records[id] = record
        let old = try load().records[id]!
        try save(saved) // Stop local access even if revocation is temporarily unavailable.
        _ = try await request(URL(string: "https://auth.openai.com/api/accounts/oauth/revoke")!,
            fields: ["client_id": id, "token": old.refresh, "token_type_hint": "refresh_token"], empty: true)
    }
    private func credentials(_ value: [String: Any], client: String) throws -> ChatGPTRecord {
        guard let access = value["access_token"] as? String, !access.isEmpty,
              let refresh = value["refresh_token"] as? String, !refresh.isEmpty,
              let scope = value["scope"] as? String, scope.split(separator: " ").contains("chatgpt.tokens.use.direct"),
              let expires = value["expires_in"] as? Double, expires > 0,
              (value["token_type"] as? String)?.lowercased() == "bearer" else { throw ChatGPTError.unavailable }
        return ChatGPTRecord(client: client, access: access, refresh: refresh, scope: scope, expires: Date().timeIntervalSince1970 + expires)
    }
    private func token(_ id: String) async throws -> String {
        guard let current = try load().records[id], !current.access.isEmpty, current.scope.split(separator: " ").contains("chatgpt.tokens.use.direct") else { throw ChatGPTError.unavailable }
        if current.expires > Date().timeIntervalSince1970 + 60 { return current.access }
        let task: Task<ChatGPTRecord, Error>
        if let running = refreshes[id] { task = running }
        else {
            task = Task {
                let tokens = try await self.request(ChatGPTProtocol.tokenURL, fields: ["grant_type": "refresh_token", "client_id": id,
                    "refresh_token": current.refresh, "resource": ChatGPTProtocol.resource])
                var next = try self.credentials(tokens, client: id)
                next.subject = current.subject; next.email = current.email; next.idToken = current.idToken
                return next
            }
            refreshes[id] = task
        }
        do {
            let next = try await task.value
            var saved = try load()
            guard saved.records[id]?.refresh == current.refresh || saved.records[id]?.refresh == next.refresh,
                  saved.records[id]?.access.isEmpty == false else { throw ChatGPTError.unavailable }
            saved.records[id] = next; try save(saved); refreshes[id] = nil
            return next.access
        } catch {
            refreshes[id] = nil
            if error is ChatGPTReauthentication {
                var saved = try load()
                saved.records[id]?.access = ""; saved.records[id]?.refresh = ""; saved.records[id]?.idToken = ""
                try save(saved)
            }
            throw error
        }
    }
    func response(account: String, body: [String: Any]) async throws -> [String: Any] {
        do {
            guard body["model"] as? String == "gpt-6-luna" else { throw ChatGPTError.unavailable }
            return try await request(URL(string: ChatGPTProtocol.resource + "/responses")!, bearer: token(account), body: ChatGPTProtocol.planRequest(body))
        }
        catch is CancellationError { throw CancellationError() }
        catch { throw ChatGPTError.unavailable }
    }
    private func request(_ url: URL, bearer: String? = nil, fields: [String: String]? = nil,
                         body: [String: Any]? = nil, empty: Bool = false) async throws -> [String: Any] {
        var request = URLRequest(url: url); request.setValue("OpenDictate-macOS", forHTTPHeaderField: "User-Agent")
        if let bearer { request.setValue("Bearer \(bearer)", forHTTPHeaderField: "Authorization") }
        if let fields { request.httpMethod = "POST"; request.httpBody = ChatGPTProtocol.form(fields); request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type") }
        if let body { request.httpMethod = "POST"; request.httpBody = try JSONSerialization.data(withJSONObject: body); request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        let (bytes, response) = try await session.bytes(for: request)
        guard let response = response as? HTTPURLResponse else { throw ChatGPTError.unavailable }
        if !(200..<300).contains(response.statusCode) {
            if url == ChatGPTProtocol.tokenURL {
                var data = Data()
                for try await byte in bytes { if data.count >= 65536 { break }; data.append(byte) }
                let object = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
                if let error = object?["error"] as? String,
                   ["invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused"].contains(error) { throw ChatGPTReauthentication() }
            }
            throw ChatGPTError.unavailable
        }
        if body != nil {
            var data = "", line = Data(), count = 0; var stream = ChatGPTResponseAccumulator()
            // AsyncBytes.lines omits empty lines on some Foundation versions; SSE uses them as event boundaries.
            for try await byte in bytes {
                try Task.checkCancellation(); count += 1
                guard count <= 2_097_152 else { throw ChatGPTError.unavailable }
                if byte != 10 { line.append(byte); continue }
                if line.last == 13 { line.removeLast() }
                let text = String(decoding: line, as: UTF8.self); line.removeAll(keepingCapacity: true)
                if text.isEmpty {
                    if !data.isEmpty { try stream.consume(data); data = "" }
                } else if text.hasPrefix("data:") {
                    if !data.isEmpty { data += "\n" }; data += text.dropFirst(5).trimmingCharacters(in: .whitespaces)
                }
            }
            return try stream.result()
        }
        var data = Data()
        for try await byte in bytes { guard data.count < 2_097_152 else { throw ChatGPTError.unavailable }; data.append(byte) }
        if empty { return [:] }
        guard let value = try JSONSerialization.jsonObject(with: data) as? [String: Any] else { throw ChatGPTError.unavailable }; return value
    }
}

private struct ChatGPTReauthentication: Error {}
