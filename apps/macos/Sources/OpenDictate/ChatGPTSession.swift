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
struct ChatGPTJournal: Codable, Sendable { var records = [String: ChatGPTRecord](); var active: String?; var last: String? }
struct ChatGPTPersistence: Sendable {
    var load: @Sendable () throws -> ChatGPTJournal
    var save: @Sendable (ChatGPTJournal) throws -> Void
    static let keychain = Self(load: { try ChatGPTStore.load() }, save: { try ChatGPTStore.save($0) })
}
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
    private var pendingJournal: ChatGPTJournal?
    private struct PendingCredential { let previousRefresh: String; let record: ChatGPTRecord }
    private var pendingCredentials = [String: PendingCredential]()
    private let inMemory: Bool
    private let persistence: ChatGPTPersistence
    private struct Refresh { let id = UUID(); let task: Task<ChatGPTRecord, Error> }
    private var refreshes = [String: Refresh]()
    private var disconnecting = Set<String>()
    private var recoveredAccount: String?
    private var unresolvedAccountRetired = false
    private let session: URLSession
    init(session: URLSession? = nil, initial: ChatGPTJournal? = nil, persistence: ChatGPTPersistence = .keychain) {
        self.journal = initial; self.inMemory = initial != nil; self.persistence = persistence
        let config = URLSessionConfiguration.ephemeral
        config.urlCache = nil; config.httpCookieStorage = nil; config.timeoutIntervalForRequest = 45; config.timeoutIntervalForResource = 60
        self.session = session ?? URLSession(configuration: config, delegate: ChatGPTNoRedirects(), delegateQueue: nil)
    }
    private func load() throws -> ChatGPTJournal {
        // Every operation must pass the Keychain boundary, even after an earlier successful read.
        var saved = try inMemory ? (journal ?? ChatGPTJournal()) : persistence.load()
        if let pendingJournal {
            try persistence.save(pendingJournal)
            self.pendingJournal = nil; saved = pendingJournal
        }
        if !pendingCredentials.isEmpty {
            var changed = false
            for (id, pending) in pendingCredentials {
                // Preserve sign-out, a newer sign-in and other registrations if they changed during refresh.
                if saved.records[id]?.refresh == pending.previousRefresh, saved.records[id]?.access.isEmpty == false {
                    saved.records[id] = pending.record; changed = true
                }
            }
            if changed, !inMemory { try persistence.save(saved) }
            pendingCredentials.removeAll()
        }
        journal = saved
        return saved
    }
    private func save(_ journal: ChatGPTJournal) throws {
        if !inMemory {
            do { try persistence.save(journal) }
            catch {
                // A rotating token may already be consumed remotely. Keep its replacement for a durable retry.
                pendingJournal = journal
                throw error
            }
        }
        pendingJournal = nil; self.journal = journal
    }
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
    func activate(_ record: ChatGPTRecord) throws {
        var saved = try load(); saved.records[record.client] = record; saved.last = record.client; saved.active = record.client
        try save(saved)
        unresolvedAccountRetired = true; recoveredAccount = nil
    }
    func disconnect() async throws {
        guard let id = try load().active else { return }
        disconnecting.insert(id)
        unresolvedAccountRetired = true; recoveredAccount = nil
        defer { disconnecting.remove(id) }
        // Revoke the latest token, including one the server is replacing right now.
        if let refresh = refreshes[id] { _ = try? await refresh.task.value }
        var saved = try load()
        guard var record = saved.records[id] else { throw ChatGPTError.unavailable }
        let refreshToken = record.refresh
        saved.active = nil; record.access = ""; record.refresh = ""; record.idToken = ""
        saved.records[id] = record
        try save(saved) // Stop local access even if revocation is temporarily unavailable.
        if refreshToken.isEmpty { return }
        _ = try await request(URL(string: "https://auth.openai.com/api/accounts/oauth/revoke")!,
            fields: ["client_id": id, "token": refreshToken, "token_type_hint": "refresh_token"], empty: true)
    }
    private func credentials(_ value: [String: Any], client: String) throws -> ChatGPTRecord {
        guard let access = value["access_token"] as? String, !access.isEmpty,
              let refresh = value["refresh_token"] as? String, !refresh.isEmpty,
              let scope = value["scope"] as? String, scope.split(separator: " ").contains("chatgpt.tokens.use.direct"),
              let expires = value["expires_in"] as? Double, expires > 0,
              (value["token_type"] as? String)?.lowercased() == "bearer" else { throw ChatGPTError.unavailable }
        return ChatGPTRecord(client: client, access: access, refresh: refresh, scope: scope, expires: Date().timeIntervalSince1970 + expires)
    }
    private func token(_ captured: String) async throws -> String {
        try Task.checkCancellation()
        let saved = try load()
        let id: String
        if captured == "unavailable" {
            guard !unresolvedAccountRetired else { throw ChatGPTError.unavailable }
            if recoveredAccount == nil { recoveredAccount = saved.active }
            guard let recoveredAccount else { throw ChatGPTError.unavailable }
            id = recoveredAccount
        } else { id = captured }
        guard !disconnecting.contains(id), let current = saved.records[id], !current.access.isEmpty,
              current.scope.split(separator: " ").contains("chatgpt.tokens.use.direct") else { throw ChatGPTError.unavailable }
        if current.expires > Date().timeIntervalSince1970 + 60 { return current.access }
        let refresh: Refresh
        if let running = refreshes[id] { refresh = running }
        else {
            // This broker-owned task finishes the durable replacement even if every dictation waiter cancels.
            refresh = Refresh(task: Task {
                do {
                    let tokens = try await self.request(ChatGPTProtocol.tokenURL, fields: ["grant_type": "refresh_token", "client_id": id,
                        "refresh_token": current.refresh, "resource": ChatGPTProtocol.resource])
                    var next = try self.credentials(tokens, client: id)
                    next.subject = current.subject; next.email = current.email; next.idToken = current.idToken
                    self.pendingCredentials[id] = PendingCredential(previousRefresh: current.refresh, record: next)
                    let saved = try self.load()
                    guard saved.records[id]?.refresh == next.refresh, saved.records[id]?.access.isEmpty == false else {
                        throw ChatGPTError.unavailable
                    }
                    return next
                } catch {
                    if error is ChatGPTReauthentication {
                        var cleared = current
                        cleared.access = ""; cleared.refresh = ""; cleared.idToken = ""
                        self.pendingCredentials[id] = PendingCredential(previousRefresh: current.refresh, record: cleared)
                        _ = try self.load()
                    }
                    throw error
                }
            })
            refreshes[id] = refresh
        }
        defer {
            // An older waiter must not remove a newer refresh after actor reentrancy.
            if refreshes[id]?.id == refresh.id { refreshes[id] = nil }
        }
        let next = try await refresh.task.value
        try Task.checkCancellation()
        guard !disconnecting.contains(id), try load().records[id]?.refresh == next.refresh else {
            throw ChatGPTError.unavailable
        }
        return next.access
    }
    func response(account: String, body: [String: Any]) async throws -> [String: Any] {
        do {
            guard body["model"] as? String == "gpt-6-luna" else { throw ChatGPTError.unavailable }
            let bearer = try await token(account)
            try Task.checkCancellation()
            return try await request(URL(string: ChatGPTProtocol.resource + "/responses")!, bearer: bearer, body: ChatGPTProtocol.planRequest(body))
        }
        catch is CancellationError { throw CancellationError() }
        catch { if Task.isCancelled { throw CancellationError() }; throw ChatGPTError.unavailable }
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
