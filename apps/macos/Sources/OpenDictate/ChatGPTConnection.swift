import AppKit
import Combine
import OpenDictateCore

struct ChatGPTAccount { let client: String; let email: String }

@MainActor final class ChatGPTConnection: ObservableObject {
    @Published private(set) var accounts = [ChatGPTAccount]()
    @Published private(set) var accountID: String?
    @Published private(set) var email = ""
    @Published private(set) var busy = false
    @Published private(set) var failed = false
    @Published var welcome = false
    @Published private(set) var revocationUnconfirmed = false
    private let defaults: UserDefaults
    private let localOnly: Bool
    private let persistence: ChatGPTPersistence
    private let authorization = ChatGPTAuthorization()
    private var work: Task<Void, Never>?
    init(defaults: UserDefaults, localOnly: Bool, persistence: ChatGPTPersistence = .keychain) {
        self.defaults = defaults; self.localOnly = localOnly; self.persistence = persistence
        if !localOnly {
            do { apply(try persistence.load()) }
            catch { accountID = "unavailable"; failed = true }
        }
    }
    var connected: Bool { accountID != nil }
    /// Retry a failed startup read without turning a selected ChatGPT route into API billing.
    @discardableResult func capturedAccount() -> String? {
        if !localOnly, !busy, accountID == "unavailable" {
            do { apply(try persistence.load()) } catch { failed = true }
        }
        return accountID
    }
    private func apply(_ saved: ChatGPTJournal) {
        accountID = saved.active
        let active = saved.active.flatMap { saved.records[$0] }
        email = active?.email ?? ""
        accounts = saved.records.values.filter { !$0.subject.isEmpty }
            .map { ChatGPTAccount(client: $0.client, email: $0.email) }.sorted { $0.client < $1.client }
        failed = saved.active != nil && (active?.access.isEmpty != false ||
            active?.scope.split(separator: " ").contains("chatgpt.tokens.use.direct") != true)
    }
    func connect(hasKey: @escaping () -> Bool, account: String? = "__last__") {
        guard !localOnly, hasKey(), !busy else { return }
        busy = true; failed = false; revocationUnconfirmed = false
        work = Task {
            do {
                let previous: ChatGPTRecord?
                if account == "__last__" { previous = try await ChatGPTSession.shared.registration() }
                else if let account { previous = try await ChatGPTSession.shared.registration(id: account) }
                else { previous = nil }
                let host = defaults.string(forKey: "chatGPTHost") ?? "urn:uuid:" + UUID().uuidString
                defaults.set(host, forKey: "chatGPTHost")
                let result = try await authorization.authorize(host: host, client: previous?.client,
                    idToken: previous?.idToken.isEmpty == false ? previous?.idToken : nil,
                    consent: previous != nil && previous?.scope.split(separator: " ").contains("chatgpt.tokens.use.direct") == false)
                let record = try await ChatGPTSession.shared.exchange(result, expected: previous)
                try Task.checkCancellation()
                guard hasKey() else { throw ChatGPTError.unavailable }
                try await ChatGPTSession.shared.activate(record)
                accountID = record.client; email = record.email
                accounts.removeAll { $0.client == record.client }; accounts.append(ChatGPTAccount(client: record.client, email: record.email))
                welcome = !defaults.bool(forKey: "chatGPTWelcomed")
            } catch is CancellationError { }
            catch { failed = true }
            busy = false
        }
    }
    func markFailed() { failed = true }
    func cancel() { work?.cancel(); authorization.cancel() }
    func disconnect() {
        guard !localOnly, !busy else { return }
        busy = true; failed = false
        work = Task {
            do { try await ChatGPTSession.shared.disconnect(); accountID = nil; email = "" }
            catch {
                // Check authoritative local selection; an unconfirmed revoke must not restore API billing accidentally.
                do {
                    if try await ChatGPTSession.shared.active() == nil {
                        accountID = nil; email = ""; revocationUnconfirmed = true
                    } else { failed = true }
                } catch { failed = true }
            }
            busy = false
        }
    }
    func dismissWelcome() { welcome = false; defaults.set(true, forKey: "chatGPTWelcomed") }
}
