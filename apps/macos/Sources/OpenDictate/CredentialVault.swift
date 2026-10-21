import Foundation
import Security

enum CredentialSlot: String, CaseIterable {
    case apiKey = "openai-api-key"
    case drive = "google-drive-oauth"
    case chatGPT = "chatgpt-oauth"
}

protocol CredentialKeychain {
    func read(account: String) throws -> Data?
    func write(_ data: Data, account: String) throws
    func delete(account: String) throws
}

private struct SystemCredentialKeychain: CredentialKeychain {
    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "com.opendictate.mac",
         kSecAttrAccount as String: account, kSecAttrSynchronizable as String: false]
    }
    func read(account: String) throws -> Data? {
        var item = query(account)
        item[kSecReturnData as String] = true; item[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: CFTypeRef?
        let status = SecItemCopyMatching(item as CFDictionary, &result)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let data = result as? Data else { throw DictationError.keychain }
        return data
    }
    func write(_ data: Data, account: String) throws {
        let attributes: [String: Any] = [kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleWhenUnlockedThisDeviceOnly]
        var status = SecItemUpdate(query(account) as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var item = query(account); item.merge(attributes) { _, new in new }
            item[kSecAttrLabel as String] = "OpenDictate credentials"
            status = SecItemAdd(item as CFDictionary, nil)
        }
        guard status == errSecSuccess else { throw DictationError.keychain }
    }
    func delete(account: String) throws {
        let status = SecItemDelete(query(account) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw DictationError.keychain }
    }
}

/// One authorization boundary, with independent payloads and serialized read/modify/write.
/// Legacy entries are retired only after their data has been durably copied.
final class CredentialVault: @unchecked Sendable {
    static let shared = CredentialVault(keychain: SystemCredentialKeychain())
    static let account = "credentials-v1"
    private struct Document: Codable {
        var version = 1
        var migrated = Set<String>()
        var values = [String: Data]()
    }
    private let keychain: any CredentialKeychain
    private let lock = NSLock()

    init(keychain: any CredentialKeychain) { self.keychain = keychain }

    private func document() throws -> Document {
        let value = try keychain.read(account: Self.account).map { try JSONDecoder().decode(Document.self, from: $0) } ?? Document()
        guard value.version == 1, Set(value.values.keys).isSubset(of: value.migrated) else { throw DictationError.keychain }
        return value
    }
    private func save(_ value: Document) throws {
        try keychain.write(JSONEncoder().encode(value), account: Self.account)
    }
    func read(_ slot: CredentialSlot) throws -> Data? {
        lock.lock(); defer { lock.unlock() }
        var value = try document()
        if !value.migrated.contains(slot.rawValue) {
            // A denied legacy read or failed write must remain retryable, never become "missing".
            value.values[slot.rawValue] = try keychain.read(account: slot.rawValue)
            value.migrated.insert(slot.rawValue)
            try save(value)
            if value.values[slot.rawValue] != nil {
                // Cleanup failure preserves both copies; it must not make a migrated key unavailable.
                try? keychain.delete(account: slot.rawValue)
            }
        }
        return value.values[slot.rawValue]
    }
    func write(_ data: Data, to slot: CredentialSlot) throws {
        lock.lock(); defer { lock.unlock() }
        var value = try document()
        let replacesLegacy = !value.migrated.contains(slot.rawValue)
        value.values[slot.rawValue] = data
        value.migrated.insert(slot.rawValue)
        try save(value)
        // Replace the old credential only after its replacement is safely in Keychain.
        if replacesLegacy { try? keychain.delete(account: slot.rawValue) }
    }
    func delete(_ slot: CredentialSlot) throws {
        lock.lock(); defer { lock.unlock() }
        var value = try document()
        value.values[slot.rawValue] = nil
        value.migrated.insert(slot.rawValue)
        try save(value)
        // The persisted migration marker prevents resurrection even if cleanup fails.
        try keychain.delete(account: slot.rawValue)
    }
}
