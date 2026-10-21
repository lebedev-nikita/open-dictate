import Foundation
import XCTest
@testable import OpenDictate

private final class MemoryCredentialKeychain: CredentialKeychain {
    enum Failure: Error { case denied }
    var items = [String: Data]()
    var reads = [String]()
    var deletes = [String]()
    var failRead: String?
    var failWrite = false
    var failDelete = false
    func read(account: String) throws -> Data? {
        reads.append(account)
        if account == failRead { throw Failure.denied }
        return items[account]
    }
    func write(_ data: Data, account: String) throws {
        if failWrite { throw Failure.denied }
        items[account] = data
    }
    func delete(account: String) throws {
        if failDelete { throw Failure.denied }
        deletes.append(account); items[account] = nil
    }
}

final class CredentialVaultTests: XCTestCase {
    func testWritingOneProviderBeforeMigrationPreservesOtherLegacyCredentials() throws {
        let backend = MemoryCredentialKeychain(), vault = CredentialVault(keychain: backend)
        backend.items[CredentialSlot.apiKey.rawValue] = Data("synthetic-api".utf8)
        backend.items[CredentialSlot.drive.rawValue] = Data("synthetic-drive".utf8)
        try vault.write(Data("synthetic-chat".utf8), to: .chatGPT)
        XCTAssertEqual(try vault.read(.apiKey), Data("synthetic-api".utf8))
        XCTAssertEqual(try vault.read(.drive), Data("synthetic-drive".utf8))
        XCTAssertEqual(try vault.read(.chatGPT), Data("synthetic-chat".utf8))
    }

    func testLockedKeychainNeverFallsBackToCachedOrLegacySecrets() throws {
        let backend = MemoryCredentialKeychain(), vault = CredentialVault(keychain: backend)
        try vault.write(Data("synthetic".utf8), to: .apiKey)
        XCTAssertNotNil(try vault.read(.apiKey))
        backend.reads = []; backend.failRead = CredentialVault.account
        XCTAssertThrowsError(try vault.read(.apiKey))
        XCTAssertEqual(backend.reads, [CredentialVault.account])
    }

    func testMigratesIndependentPayloadsThenNextLaunchReadsOnlyOneKeychainItem() throws {
        let backend = MemoryCredentialKeychain()
        for slot in CredentialSlot.allCases { backend.items[slot.rawValue] = Data(slot.rawValue.utf8) }
        let vault = CredentialVault(keychain: backend)
        for slot in CredentialSlot.allCases {
            XCTAssertEqual(try vault.read(slot), Data(slot.rawValue.utf8))
            XCTAssertNil(backend.items[slot.rawValue])
        }
        backend.reads = []; backend.deletes = []
        let nextLaunch = CredentialVault(keychain: backend)
        for _ in 0..<3 { for slot in CredentialSlot.allCases {
            XCTAssertEqual(try nextLaunch.read(slot), Data(slot.rawValue.utf8))
        } }
        try nextLaunch.write(Data("rotated".utf8), to: .drive)
        XCTAssertFalse(backend.reads.isEmpty)
        XCTAssertTrue(backend.reads.allSatisfy { $0 == CredentialVault.account })
        XCTAssertEqual(backend.deletes, [], "Routine token rotation must not revisit old Keychain items")
        XCTAssertEqual(try nextLaunch.read(.apiKey), Data(CredentialSlot.apiKey.rawValue.utf8))
        XCTAssertEqual(try nextLaunch.read(.chatGPT), Data(CredentialSlot.chatGPT.rawValue.utf8))
    }

    func testDeniedMigrationIsRetriedAndNeverBecomesAnEmptyCredential() throws {
        let backend = MemoryCredentialKeychain(), bytes = Data("synthetic".utf8)
        backend.items[CredentialSlot.apiKey.rawValue] = bytes
        backend.failRead = CredentialSlot.apiKey.rawValue
        let vault = CredentialVault(keychain: backend)
        XCTAssertThrowsError(try vault.read(.apiKey))
        XCTAssertNil(backend.items[CredentialVault.account])
        backend.failRead = nil
        backend.failWrite = true
        XCTAssertThrowsError(try vault.read(.apiKey))
        XCTAssertEqual(backend.items[CredentialSlot.apiKey.rawValue], bytes)
        XCTAssertEqual(backend.deletes, [])
        backend.failWrite = false
        XCTAssertEqual(try vault.read(.apiKey), bytes)
    }

    func testDeletionIsScopedAndCannotResurrectALegacyCredentialAfterCleanupFailure() throws {
        let backend = MemoryCredentialKeychain()
        backend.items[CredentialSlot.apiKey.rawValue] = Data("synthetic-api".utf8)
        backend.items[CredentialSlot.drive.rawValue] = Data("synthetic-drive".utf8)
        backend.failDelete = true
        let vault = CredentialVault(keychain: backend)
        _ = try vault.read(.apiKey); _ = try vault.read(.drive)
        XCTAssertThrowsError(try vault.delete(.apiKey))
        XCTAssertNotNil(backend.items[CredentialSlot.apiKey.rawValue])
        let restarted = CredentialVault(keychain: backend)
        XCTAssertNil(try restarted.read(.apiKey))
        XCTAssertEqual(try restarted.read(.drive), Data("synthetic-drive".utf8))
        backend.failDelete = false
        try restarted.delete(.apiKey)
        XCTAssertNil(backend.items[CredentialSlot.apiKey.rawValue])
        XCTAssertEqual(try restarted.read(.drive), Data("synthetic-drive".utf8))
    }

    func testMissingSlotsAreRememberedAndCorruptVaultNeverFallsBackToOldCredentials() throws {
        let backend = MemoryCredentialKeychain(), vault = CredentialVault(keychain: backend)
        XCTAssertNil(try vault.read(.chatGPT)); XCTAssertNil(try vault.read(.chatGPT))
        XCTAssertEqual(backend.reads.filter { $0 == CredentialSlot.chatGPT.rawValue }.count, 1)
        backend.items[CredentialVault.account] = Data("invalid".utf8)
        backend.reads = []
        XCTAssertThrowsError(try CredentialVault(keychain: backend).read(.apiKey))
        XCTAssertEqual(backend.reads, [CredentialVault.account])
    }

    func testConcurrentCredentialUpdatesPreserveTheOtherSlots() throws {
        let backend = MemoryCredentialKeychain(), vault = CredentialVault(keychain: backend)
        for slot in CredentialSlot.allCases { try vault.write(Data(slot.rawValue.utf8), to: slot) }
        DispatchQueue.concurrentPerform(iterations: 90) { index in
            let slot = CredentialSlot.allCases[index % CredentialSlot.allCases.count]
            do { try vault.write(Data(slot.rawValue.utf8), to: slot) }
            catch { XCTFail("Concurrent credential update failed") }
        }
        let reopened = CredentialVault(keychain: backend)
        for slot in CredentialSlot.allCases { XCTAssertEqual(try reopened.read(slot), Data(slot.rawValue.utf8)) }
    }
}
