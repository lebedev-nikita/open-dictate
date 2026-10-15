import XCTest
import OpenDictateCore
import CryptoKit
import AppKit
import SwiftUI
@testable import OpenDictate

private final class DriveHTTP: URLProtocol, @unchecked Sendable {
    static var handler: ((URLRequest) throws -> (Int, Data))?
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, body) = try Self.handler!(request)
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status,
                httpVersion: "HTTP/1.1", headerFields: nil)!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body); client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

final class GoogleDriveTests: XCTestCase {
    func testUnchangedPollsAndCloudOnlyEditsDoNotUploadButLocalEditsAndFirstLinkDo() {
        var cloud = SettingsSyncDocument()
        cloud.record(["dictionary": "Cloud"], deviceId: "android", now: 42)
        let existing = GoogleDriveClient.Remote(document: cloud, ownFileID: "own-file")
        XCTAssertFalse(existing.needsUpload(cloud))
        var imported = SettingsSyncDocument(); imported.merge(cloud)
        XCTAssertFalse(existing.needsUpload(imported))
        XCTAssertTrue(GoogleDriveClient.Remote(document: cloud, ownFileID: nil).needsUpload(imported))
        imported.record(["dictionary": "Local"], deviceId: "mac", now: 50)
        XCTAssertTrue(existing.needsUpload(imported))
    }

    private func client() -> GoogleDriveClient {
        let config = URLSessionConfiguration.ephemeral; config.protocolClasses = [DriveHTTP.self]
        return GoogleDriveClient(session: URLSession(configuration: config))
    }
    func testPaginationMergesReplicasAndUploadsOnlyOwnFile() async throws {
        var calls = 0
        DriveHTTP.handler = { request in
            calls += 1
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-drive-token")
            let response: String
            switch calls {
            case 1:
                XCTAssertTrue(request.url!.absoluteString.contains("spaces=appDataFolder"))
                response = #"{"nextPageToken":"page2","files":[{"id":"android-file","name":"opendictate-settings-v1-android.json","size":"150"}]}"#
            case 2: response = #"{"schemaVersion":1,"entries":{"dictionary":{"value":"Никита","modifiedAt":20,"deviceId":"android"}}}"#
            case 3:
                XCTAssertTrue(request.url!.absoluteString.contains("pageToken=page2"))
                response = #"{"files":[{"id":"own-file","name":"opendictate-settings-v1-mac.json","size":"150"}]}"#
            case 4: response = #"{"schemaVersion":1,"entries":{"textModel":{"value":"gpt-6-sol","modifiedAt":30,"deviceId":"mac"}}}"#
            default:
                XCTAssertEqual(request.httpMethod, "PATCH")
                XCTAssertEqual(request.url?.path, "/upload/drive/v3/files/own-file")
                response = "{}"
            }
            return (200, Data(response.utf8))
        }
        let client = client(), remote = try await client.download(token: "synthetic-drive-token", deviceID: "mac")
        XCTAssertEqual(remote.ownFileID, "own-file")
        XCTAssertEqual(remote.document.entries["dictionary"]?.value, "Никита")
        XCTAssertEqual(remote.document.entries["textModel"]?.value, "gpt-6-sol")
        try await client.upload(token: "synthetic-drive-token", deviceID: "mac", fileID: remote.ownFileID, document: remote.document)
        XCTAssertEqual(calls, 5)
    }
    func testReplicaCacheTracksVersionsDeletionReconnectAndOwnUploads() async throws {
        var version = "1", present = true, mediaCalls = 0
        DriveHTTP.handler = { request in
            if request.httpMethod == "PATCH" { return (200, Data("{}".utf8)) }
            if request.url?.query?.contains("alt=media") == true {
                mediaCalls += 1
                return (200, Data("{\"schemaVersion\":1,\"entries\":{\"dictionary\":{\"value\":\"v\(version)\",\"modifiedAt\":1,\"deviceId\":\"mac\"}}}".utf8))
            }
            XCTAssertTrue(request.url!.absoluteString.contains("version"))
            let metadata = version.isEmpty ? "" : ",\"version\":\"\(version)\""
            let files = present ? "{\"id\":\"own-file\",\"name\":\"opendictate-settings-v1-mac.json\",\"size\":\"150\"\(metadata)}" : ""
            return (200, Data("{\"files\":[\(files)]}".utf8))
        }
        let client = client()
        _ = try await client.download(token: "synthetic", deviceID: "mac")
        _ = try await client.download(token: "synthetic", deviceID: "mac")
        XCTAssertEqual(mediaCalls, 1)
        version = "2"
        let changed = try await client.download(token: "synthetic", deviceID: "mac")
        XCTAssertEqual(changed.document.entries["dictionary"]?.value, "v2")
        XCTAssertEqual(mediaCalls, 2)
        present = false
        let deleted = try await client.download(token: "synthetic", deviceID: "mac")
        XCTAssertTrue(deleted.document.entries.isEmpty); XCTAssertNil(deleted.ownFileID)
        present = true
        _ = try await client.download(token: "synthetic", deviceID: "mac")
        XCTAssertEqual(mediaCalls, 3)
        _ = try await client.download(token: "synthetic", deviceID: "mac", forceRefresh: true)
        XCTAssertEqual(mediaCalls, 4)
        try await client.upload(token: "synthetic", deviceID: "mac", fileID: "own-file", document: changed.document)
        _ = try await client.download(token: "synthetic", deviceID: "mac")
        XCTAssertEqual(mediaCalls, 5)
        version = ""
        _ = try await client.download(token: "synthetic", deviceID: "mac")
        _ = try await client.download(token: "synthetic", deviceID: "mac")
        XCTAssertEqual(mediaCalls, 7)
    }

    func testFailedReplicaReadDoesNotCacheInvalidData() async throws {
        var invalid = true, mediaCalls = 0
        DriveHTTP.handler = { request in
            if request.url?.query?.contains("alt=media") == true {
                mediaCalls += 1
                return (200, Data((invalid ? "invalid" : "{\"schemaVersion\":1,\"entries\":{}}").utf8))
            }
            return (200, Data(#"{"files":[{"id":"cloud","name":"opendictate-settings-v1-android.json","version":"1"}]}"#.utf8))
        }
        let client = client()
        do { _ = try await client.download(token: "synthetic", deviceID: "mac"); XCTFail("Invalid data must fail") }
        catch {}
        invalid = false
        _ = try await client.download(token: "synthetic", deviceID: "mac")
        _ = try await client.download(token: "synthetic", deviceID: "mac")
        XCTAssertEqual(mediaCalls, 2)
    }

    @MainActor func testReopeningFreshSettingsDoesNotMakeAnotherRequestButManualSyncDoes() async throws {
        let suite = "drive-freshness-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults); preferences.driveSyncEnabled = true
        var reads = 0
        DriveHTTP.handler = { request in
            if request.httpMethod == "GET" { reads += 1 }
            return (200, Data("{\"files\":[]}".utf8))
        }
        let sync = GoogleDriveSync(preferences: preferences, client: client(), refreshToken: { "synthetic" },
            automaticallySync: false, deleteCredentials: {})
        sync.syncNow()
        XCTAssertFalse(sync.canSyncNow)
        sync.syncNow() // A repeated click/shortcut during the same exchange must not enqueue another run.
        try await awaitStatus(sync, .synced)
        XCTAssertTrue(sync.canSyncNow)
        XCTAssertEqual(reads, 1)
        for _ in 0..<5 { sync.refreshIfStale() }
        try await Task.sleep(nanoseconds: 50_000_000)
        XCTAssertEqual(reads, 1)
        sync.syncNow(); try await awaitStatus(sync, .synced)
        XCTAssertEqual(reads, 2)
        sync.disconnect()
        XCTAssertFalse(sync.canSyncNow)
    }

    @MainActor func testDisconnectRemovesOnlyLocalAuthorizationAndPreservesSettings() async throws {
        let suite = "drive-local-sign-out-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        preferences.driveSyncEnabled = true
        preferences.dictionary = "Keep local settings"
        var credentials: DriveCredentials? = DriveCredentials(clientID: "synthetic.apps.googleusercontent.com",
            clientSecret: "synthetic-secret", refreshToken: "synthetic-refresh")
        var deletions = 0, requests = 0
        DriveHTTP.handler = { request in
            requests += 1
            // Local sign-out must not send a Google grant-revocation request.
            XCTAssertEqual(request.url?.host, "www.googleapis.com")
            return (200, Data("{\"files\":[]}".utf8))
        }
        let sync = GoogleDriveSync(preferences: preferences, client: client(), refreshToken: {
            guard credentials != nil else { throw DriveError.authorization }
            return "synthetic-access"
        }, automaticallySync: false, deleteCredentials: { credentials = nil; deletions += 1 })
        sync.syncNow()
        try await awaitStatus(sync, .synced)
        let document = try preferences.syncDocument(), completedRequests = requests
        sync.disconnect()
        XCTAssertNil(credentials)
        XCTAssertEqual(deletions, 1)
        XCTAssertEqual(sync.status, .disconnected)
        XCTAssertNil(sync.lastSyncedAt)
        XCTAssertFalse(preferences.driveSyncEnabled)
        XCTAssertFalse(preferences.driveConnectionPending)
        XCTAssertFalse(sync.canSyncNow)
        XCTAssertEqual(try preferences.syncDocument(), document)
        sync.refreshIfStale()
        sync.syncNow()
        XCTAssertEqual(requests, completedRequests)
    }

    @MainActor func testManualSyncUploadsPendingEditsImmediately() async throws {
        let suite = "drive-immediate-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults); preferences.driveSyncEnabled = true
        var writes = 0
        DriveHTTP.handler = { request in
            if request.httpMethod != "GET" { writes += 1; return (200, Data("{}".utf8)) }
            return (200, Data("{\"files\":[]}".utf8))
        }
        let sync = GoogleDriveSync(preferences: preferences, client: client(), refreshToken: { "synthetic" },
            automaticallySync: false, deleteCredentials: {})
        defer { sync.disconnect() }
        preferences.dictionary = "Edited just before manual sync"
        sync.syncNow()
        XCTAssertEqual(sync.status, .busy)
        try await awaitStatus(sync, .synced)
        XCTAssertEqual(writes, 1)
        XCTAssertEqual(preferences.dictionary, "Edited just before manual sync")
    }

    @MainActor func testEditsDuringManualSyncRemainLocalUntilTheNextSync() async throws {
        let suite = "drive-in-flight-edit-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults); preferences.driveSyncEnabled = true
        var writes = 0
        DriveHTTP.handler = { request in
            if request.httpMethod != "GET" { writes += 1; return (200, Data("{}".utf8)) }
            return (200, Data("{\"files\":[]}".utf8))
        }
        let started = expectation(description: "Manual sync started")
        var resume: CheckedContinuation<String, Never>?
        var refreshes = 0
        let sync = GoogleDriveSync(preferences: preferences, client: client(), refreshToken: {
            refreshes += 1
            guard refreshes == 1 else { return "synthetic" }
            return await withCheckedContinuation { continuation in
                resume = continuation
                started.fulfill()
            }
        }, automaticallySync: false, deleteCredentials: {})
        defer { sync.disconnect() }
        preferences.dictionary = "Before manual sync"
        sync.syncNow()
        await fulfillment(of: [started], timeout: 5)
        preferences.dictionary = "Edited during manual sync"
        resume?.resume(returning: "synthetic")
        try await awaitStatus(sync, .waiting)
        XCTAssertEqual(writes, 0)
        XCTAssertEqual(preferences.dictionary, "Edited during manual sync")
        sync.syncNow(); try await awaitStatus(sync, .synced)
        XCTAssertEqual(writes, 1)
        XCTAssertEqual(preferences.dictionary, "Edited during manual sync")
    }

    @MainActor func testManualRetryBypassesFailureBackoffAndRecovers() async throws {
        let suite = "drive-manual-retry-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults); preferences.driveSyncEnabled = true
        var reads = 0
        DriveHTTP.handler = { request in
            if request.httpMethod == "GET" {
                reads += 1
                if reads == 1 { return (503, Data()) }
            }
            return (200, Data("{\"files\":[]}".utf8))
        }
        let sync = GoogleDriveSync(preferences: preferences, client: client(), refreshToken: { "synthetic" },
            automaticallySync: false, deleteCredentials: {})
        sync.syncNow(); try await awaitStatus(sync, .failed)
        XCTAssertTrue(sync.canSyncNow)
        preferences.dictionary = "Edited just before retry"
        sync.syncNow(); try await awaitStatus(sync, .synced)
        XCTAssertEqual(reads, 2)
        XCTAssertNotNil(sync.lastSyncedAt)
        sync.disconnect()
    }

    @MainActor func testSyncControlRendersWaitingBusySuccessAndFailureInBothLanguagesAndThemes() async throws {
        let suite = "drive-control-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults); preferences.driveSyncEnabled = true
        var fail = false
        DriveHTTP.handler = { _ in (fail ? 503 : 200, Data("{\"files\":[]}".utf8)) }
        let sync = GoogleDriveSync(preferences: preferences, client: client(), refreshToken: { "synthetic" },
            automaticallySync: false, deleteCredentials: {})
        func render(_ state: String) throws {
            for language in ["en", "ru"] {
                preferences.interfaceLanguage = language
                for appearance in [NSAppearance.Name.aqua, .darkAqua] {
                    let host = NSHostingView(rootView: VStack(alignment: .leading, spacing: 20) {
                        HStack(alignment: .top, spacing: 16) {
                            Spacer()
                            SettingsSyncControl(sync: sync, preferences: preferences)
                            Image(systemName: "circle.lefthalf.filled").frame(width: 24, height: 24)
                        }
                        GoogleDriveSettingsView(sync: sync, preferences: preferences)
                    }.padding(28).frame(width: 464).background(Color(nsColor: .windowBackgroundColor)))
                    host.appearance = NSAppearance(named: appearance)
                    host.setFrameSize(host.fittingSize); host.layoutSubtreeIfNeeded()
                    XCTAssertEqual(host.frame.width, 464, accuracy: 1)
                    XCTAssertLessThan(host.frame.height, 650)
                    if let output = ProcessInfo.processInfo.environment["OPENDICTATE_VIEW_CAPTURES"] {
                        let bitmap = try XCTUnwrap(host.bitmapImageRepForCachingDisplay(in: host.bounds))
                        host.cacheDisplay(in: host.bounds, to: bitmap)
                        let data = try XCTUnwrap(bitmap.representation(using: .png, properties: [:]))
                        let directory = URL(fileURLWithPath: output)
                        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                        try data.write(to: directory.appendingPathComponent("sync-\(state)-\(language)-\(appearance.rawValue).png"))
                    }
                }
            }
        }
        try render("waiting")
        sync.syncNow(); try render("busy")
        try await awaitStatus(sync, .synced); try render("synced")
        fail = true; sync.syncNow()
        try await awaitStatus(sync, .failed); try render("failed")
        DriveHTTP.handler = { _ in (401, Data()) }
        sync.syncNow(); try await awaitStatus(sync, .authorization); try render("authorization")
        sync.disconnect()
        try render("disconnected")
    }

    @MainActor func testDriveSwitchBindingCancelsSyncAndSignsOutLocally() async throws {
        let suite = "drive-native-switch-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        preferences.driveSyncEnabled = true
        preferences.dictionary = "Keep this dictionary"
        var deletedCredentials = false
        let started = expectation(description: "Token refresh started")
        let cancelled = expectation(description: "Token refresh cancelled")
        let sync = GoogleDriveSync(preferences: preferences, refreshToken: {
            started.fulfill()
            do {
                try await Task.sleep(nanoseconds: 60_000_000_000)
                XCTFail("The switch should cancel token refresh")
                return "synthetic"
            } catch {
                cancelled.fulfill()
                throw error
            }
        }, automaticallySync: false, deleteCredentials: { deletedCredentials = true })
        defer { sync.disconnect() }
        sync.syncNow()
        await fulfillment(of: [started], timeout: 5)
        let view = GoogleDriveSettingsView(sync: sync, preferences: preferences)
        XCTAssertTrue(view.connection.wrappedValue)
        view.connection.wrappedValue = false
        await fulfillment(of: [cancelled], timeout: 5)
        XCTAssertTrue(deletedCredentials)
        XCTAssertFalse(preferences.driveSyncEnabled)
        XCTAssertEqual(sync.status, .disconnected)
        XCTAssertEqual(preferences.dictionary, "Keep this dictionary")
        XCTAssertFalse(view.connection.wrappedValue)
        // A new browser connection is represented as on immediately, so off can cancel it.
        view.connection.wrappedValue = true
        XCTAssertEqual(sync.status, .busy)
        XCTAssertTrue(view.connection.wrappedValue)
        XCTAssertFalse(preferences.driveSyncEnabled)
        view.connection.wrappedValue = false
        await Task.yield()
        XCTAssertEqual(sync.status, .disconnected)
        XCTAssertFalse(view.connection.wrappedValue)
    }

    func testInitialUploadUsesAppDataAndMultipart() async throws {
        DriveHTTP.handler = { request in
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.url?.query, "uploadType=multipart")
            XCTAssertTrue(request.value(forHTTPHeaderField: "Content-Type")?.hasPrefix("multipart/related") == true)
            return (200, Data("{}".utf8))
        }
        try await client().upload(token: "synthetic-drive-token", deviceID: "mac", fileID: nil, document: .init())
    }
    func testAuthFailureDoesNotExposeResponseBody() async {
        DriveHTTP.handler = { _ in (401, Data("private-provider-response".utf8)) }
        do { _ = try await client().download(token: "synthetic-drive-token", deviceID: "mac"); XCTFail("Should fail") }
        catch { XCTAssertTrue(error as? DriveError == .authorization); XCTAssertFalse(error.localizedDescription.contains("private-provider-response")) }
    }
    @MainActor func testImportPreservesInFlightLocalEditsAndDoesNotRestampRemote() throws {
        let suite = "opendictate-sync-test-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        var remote = SettingsSyncDocument()
        remote.entries["dictionary"] = .init(value: "Remote", modifiedAt: 100, deviceId: "android")
        remote.entries["textModel"] = .init(value: "gpt-6-sol", modifiedAt: 100, deviceId: "android")
        preferences.dictionary = "Local edit made during download"
        let merged = try preferences.mergeSync(remote)
        XCTAssertEqual(preferences.dictionary, "Local edit made during download")
        XCTAssertEqual(preferences.textModel, "gpt-6-sol")
        XCTAssertEqual(merged.entries["textModel"]?.modifiedAt, 100)
        XCTAssertEqual(merged, try preferences.syncDocument())
        let context = preferences.context
        preferences.liveModel = "changed-later"
        XCTAssertEqual(context.liveModel, "gpt-live-transcribe")
    }
    @MainActor func testInvalidRemoteDoesNotPartiallyApply() throws {
        let suite = "opendictate-sync-test-\(UUID().uuidString)", defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        var remote = SettingsSyncDocument()
        remote.entries["dictionary"] = .init(value: "Remote", modifiedAt: 100, deviceId: "android")
        remote.entries["mode"] = .init(value: "invalid", modifiedAt: 100, deviceId: "android")
        XCTAssertThrowsError(try preferences.mergeSync(remote))
        XCTAssertEqual(preferences.dictionary, "OpenDictate")
    }

    @MainActor func testDesktopOAuthValidatesStateAndExchangesPKCEWithoutRealBrowserOrKeychain() async throws {
        var challenge = "", saved: DriveCredentials?
        let authorization = GoogleDriveAuthorization(openBrowser: { url in
            let query = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!
            func value(_ key: String) -> String { query.first { $0.name == key }!.value! }
            challenge = value("code_challenge")
            XCTAssertEqual(value("code_challenge_method"), "S256")
            XCTAssertEqual(value("scope"), GoogleDriveAuthorization.scope)
            XCTAssertEqual(Set(value("prompt").split(separator: " ")), ["consent", "select_account"])
            let redirect = value("redirect_uri"), state = value("state")
            XCTAssertTrue(redirect.hasPrefix("http://127.0.0.1:"))
            Task { @MainActor in
                // A callback with an incorrect state must not complete or exchange a code.
                _ = try? await URLSession.shared.data(from: URL(string: redirect + "?code=wrong&state=wrong")!)
                _ = try? await URLSession.shared.data(from: URL(string: redirect + "?code=synthetic-code&state=" + state)!)
            }
            return true
        }, saveCredentials: { saved = $0 })
        DriveHTTP.handler = { request in
            XCTAssertEqual(request.url?.absoluteString, "https://oauth2.googleapis.com/token")
            let data: Data
            if let body = request.httpBody { data = body }
            else {
                let stream = try XCTUnwrap(request.httpBodyStream)
                stream.open(); defer { stream.close() }
                var body = Data(), buffer = [UInt8](repeating: 0, count: 4096)
                while stream.hasBytesAvailable {
                    let count = stream.read(&buffer, maxLength: buffer.count)
                    if count <= 0 { break }; body.append(contentsOf: buffer.prefix(count))
                }
                data = body
            }
            let fields = URLComponents(string: "https://example.test/?" + String(decoding: data, as: UTF8.self))!.queryItems!
            func value(_ key: String) -> String { fields.first { $0.name == key }!.value! }
            XCTAssertEqual(value("code"), "synthetic-code")
            let hash = Data(SHA256.hash(data: Data(value("code_verifier").utf8))).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
            XCTAssertEqual(hash, challenge)
            return (200, Data(#"{"access_token":"synthetic-access","refresh_token":"synthetic-refresh","scope":"https://www.googleapis.com/auth/drive.appdata"}"#.utf8))
        }
        let token = try await authorization.connect(clientID: "synthetic.apps.googleusercontent.com", clientSecret: "synthetic-secret", client: client())
        XCTAssertEqual(token, "synthetic-access")
        XCTAssertEqual(saved?.refreshToken, "synthetic-refresh")
    }

    @MainActor private func awaitStatus(_ sync: GoogleDriveSync, _ status: GoogleDriveSync.Status) async throws {
        for _ in 0..<500 {
            if sync.status == status { return }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
        XCTFail("Synchronization did not reach the expected status")
    }

    @MainActor func testReconnectingWithBlankSecretReusesSavedSecretForSameClient() async throws {
        let clientID = "synthetic.apps.googleusercontent.com"
        let stored = DriveCredentials(clientID: clientID, clientSecret: "synthetic-saved-secret", refreshToken: "synthetic-expired-refresh")
        var saved: DriveCredentials?
        let authorization = GoogleDriveAuthorization(openBrowser: { url in
            let query = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!
            XCTAssertTrue(query.first { $0.name == "prompt" }!.value!.split(separator: " ").contains("select_account"))
            let redirect = query.first { $0.name == "redirect_uri" }!.value!
            let state = query.first { $0.name == "state" }!.value!
            Task { _ = try? await URLSession.shared.data(from: URL(string: redirect + "?code=synthetic-code&state=" + state)!) }
            return true
        }, loadCredentials: { stored }, saveCredentials: { saved = $0 })
        DriveHTTP.handler = { request in
            let fields = try Self.formFields(request)
            XCTAssertEqual(fields["client_secret"], stored.clientSecret)
            return (200, Data(#"{"access_token":"synthetic-access","refresh_token":"synthetic-new-refresh","scope":"https://www.googleapis.com/auth/drive.appdata"}"#.utf8))
        }
        _ = try await authorization.connect(clientID: clientID, clientSecret: "", client: client())
        XCTAssertEqual(saved?.clientSecret, stored.clientSecret)
        XCTAssertEqual(saved?.refreshToken, "synthetic-new-refresh")
    }

    @MainActor func testExplicitSecretOverridesSavedCredentialsWithoutLoadingKeychain() async throws {
        let secret = "synthetic-new+secret&with=symbols"
        var saved: DriveCredentials?
        let authorization = GoogleDriveAuthorization(openBrowser: { url in
            let query = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!
            let redirect = query.first { $0.name == "redirect_uri" }!.value!
            let state = query.first { $0.name == "state" }!.value!
            Task { _ = try? await URLSession.shared.data(from: URL(string: redirect + "?code=synthetic-code&state=" + state)!) }
            return true
        }, loadCredentials: { XCTFail("An entered secret must override saved credentials"); return nil }, saveCredentials: { saved = $0 })
        DriveHTTP.handler = { request in
            XCTAssertEqual(try Self.formFields(request)["client_secret"], secret)
            return (200, Data(#"{"access_token":"synthetic-access","refresh_token":"synthetic-refresh","scope":"https://www.googleapis.com/auth/drive.appdata"}"#.utf8))
        }
        _ = try await authorization.connect(clientID: "synthetic.apps.googleusercontent.com", clientSecret: "  " + secret + "\n", client: client())
        XCTAssertEqual(saved?.clientSecret, secret)
    }

    @MainActor func testMissingSecretAndDifferentClientFailBeforeOpeningBrowserWithoutReplacingCredentials() async {
        let old = DriveCredentials(clientID: "old.apps.googleusercontent.com", clientSecret: "synthetic-old-secret", refreshToken: "synthetic-refresh")
        for stored in [nil, old] as [DriveCredentials?] {
            let authorization = GoogleDriveAuthorization(openBrowser: { _ in XCTFail("Missing secret must fail before browser sign-in"); return true },
                loadCredentials: { stored }, saveCredentials: { _ in XCTFail("Existing credentials must remain unchanged") })
            do {
                _ = try await authorization.connect(clientID: "new.apps.googleusercontent.com", clientSecret: "", client: client())
                XCTFail("A different client cannot reuse the saved secret")
            } catch { XCTAssertEqual(error as? DriveError, .clientConfiguration) }
        }
    }

    func testOAuthErrorsDistinguishExpiredGrantFromClientConfigurationWithoutExposingPayloads() async {
        let cases: [(Int, String, DriveError)] = [
            (400, "invalid_grant", .authorization), (400, "invalid_client", .clientConfiguration),
            (401, "invalid_client", .clientConfiguration), (400, "invalid_request", .clientConfiguration),
            (400, "unknown", .authorization), (503, "temporarily_unavailable", .network)
        ]
        for (status, code, expected) in cases {
            DriveHTTP.handler = { _ in
                (status, Data("{\"error\":\"\(code)\",\"error_description\":\"private-provider-description\"}".utf8))
            }
            do {
                _ = try await client().send(URLRequest(url: URL(string: "https://oauth2.googleapis.com/token")!))
                XCTFail("OAuth failure must not succeed")
            } catch {
                XCTAssertEqual(error as? DriveError, expected)
                XCTAssertFalse(error.localizedDescription.contains("private-provider-description"))
            }
        }
    }

    @MainActor func testExpiredRefreshTokenKeepsSavedCredentialsForReconnection() async {
        let credentials = DriveCredentials(clientID: "synthetic.apps.googleusercontent.com", clientSecret: "synthetic-saved-secret", refreshToken: "synthetic-expired-refresh")
        let authorization = GoogleDriveAuthorization(loadCredentials: { credentials }, saveCredentials: { _ in XCTFail("Failed refresh must not replace credentials") })
        DriveHTTP.handler = { request in
            let fields = try Self.formFields(request)
            XCTAssertEqual(fields["grant_type"], "refresh_token")
            XCTAssertEqual(fields["client_secret"], credentials.clientSecret)
            XCTAssertEqual(fields["refresh_token"], credentials.refreshToken)
            return (400, Data(#"{"error":"invalid_grant"}"#.utf8))
        }
        do { _ = try await authorization.refresh(client: client()); XCTFail("Expired grant must fail") }
        catch { XCTAssertEqual(error as? DriveError, .authorization) }
    }

    @MainActor func testClientConfigurationFailureOffersReconnectAndPreservesLocalSettings() async throws {
        let suite = "drive-client-configuration-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        preferences.driveSyncEnabled = true
        preferences.dictionary = "Offline edit"
        let sync = GoogleDriveSync(preferences: preferences, client: client(), refreshToken: { throw DriveError.clientConfiguration },
            automaticallySync: false, deleteCredentials: {})
        sync.syncNow(); try await awaitStatus(sync, .clientConfiguration)
        XCTAssertTrue(sync.needsSignIn)
        XCTAssertTrue(preferences.driveSyncEnabled)
        XCTAssertEqual(preferences.dictionary, "Offline edit")
        sync.disconnect()
    }

    private static func formFields(_ request: URLRequest) throws -> [String: String] {
        let data: Data
        if let body = request.httpBody { data = body }
        else {
            let stream = try XCTUnwrap(request.httpBodyStream)
            stream.open(); defer { stream.close() }
            var body = Data(), buffer = [UInt8](repeating: 0, count: 4096)
            while stream.hasBytesAvailable {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                body.append(contentsOf: buffer.prefix(count))
            }
            data = body
        }
        let items = try XCTUnwrap(URLComponents(string: "https://example.test/?" + String(decoding: data, as: UTF8.self))?.queryItems)
        return Dictionary(uniqueKeysWithValues: items.map { ($0.name, $0.value ?? "") })
    }

    @MainActor func testConnectionWaitsWithoutApplyingOrUploadingAndCancelPreservesSettings() async throws {
        let suite = "drive-choice-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        preferences.dictionary = "Offline edit"
        preferences.driveConnectionPending = true; preferences.driveSyncEnabled = true
        var writes = 0
        DriveHTTP.handler = { request in
            if request.httpMethod != "GET" { writes += 1; return (200, Data("{}".utf8)) }
            if request.url?.query?.contains("alt=media") == true {
                return (200, Data(#"{"schemaVersion":1,"entries":{"dictionary":{"value":"Cloud","modifiedAt":1,"deviceId":"android"}}}"#.utf8))
            }
            return (200, Data(#"{"files":[{"id":"cloud-file","name":"opendictate-settings-v1-android.json"}]}"#.utf8))
        }
        let sync = GoogleDriveSync(preferences: preferences, client: client(), refreshToken: { "synthetic" },
            automaticallySync: false, deleteCredentials: {})
        let before = try preferences.syncDocument()
        sync.syncNow(); try await awaitStatus(sync, .sourceSelection)
        XCTAssertEqual(preferences.dictionary, "Offline edit")
        XCTAssertEqual(try preferences.syncDocument(), before)
        XCTAssertEqual(writes, 0)
        sync.syncNow()
        XCTAssertTrue(sync.needsSourceSelection)
        sync.disconnect()
        XCTAssertFalse(sync.needsSourceSelection); XCTAssertFalse(preferences.driveSyncEnabled)
        XCTAssertFalse(preferences.driveConnectionPending)
        XCTAssertEqual(preferences.dictionary, "Offline edit"); XCTAssertEqual(writes, 0)
    }

    @MainActor func testSourceSelectionRedownloadsAndPromptsAgainIfCloudChanged() async throws {
        let suite = "drive-choice-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let preferences = Preferences(defaults: defaults)
        preferences.dictionary = "Offline edit"
        preferences.driveConnectionPending = true; preferences.driveSyncEnabled = true
        var cloud = "Cloud", writes = 0
        DriveHTTP.handler = { request in
            if request.httpMethod != "GET" { writes += 1; return (200, Data("{}".utf8)) }
            if request.url?.query?.contains("alt=media") == true {
                var remote = SettingsSyncDocument()
                remote.entries["dictionary"] = .init(value: cloud, modifiedAt: 1, deviceId: "android")
                return (200, try JSONEncoder().encode(remote))
            }
            return (200, Data(#"{"files":[{"id":"cloud-file","name":"opendictate-settings-v1-android.json"}]}"#.utf8))
        }
        let sync = GoogleDriveSync(preferences: preferences, client: client(), refreshToken: { "synthetic" },
            automaticallySync: false, deleteCredentials: {})
        sync.syncNow(); try await awaitStatus(sync, .sourceSelection)
        cloud = "Changed on another device"
        sync.chooseSource(.cloud); try await awaitStatus(sync, .sourceSelection)
        XCTAssertEqual(writes, 0); XCTAssertEqual(preferences.dictionary, "Offline edit")
        sync.chooseSource(.cloud); try await awaitStatus(sync, .synced)
        XCTAssertEqual(preferences.dictionary, cloud)
        XCTAssertEqual(writes, 1); XCTAssertFalse(preferences.driveConnectionPending)
    }

    @MainActor func testPendingConnectionSurvivesFailureAndRestartAndLocalChoicePublishes() async throws {
        let suite = "drive-choice-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        var preferences = Preferences(defaults: defaults)
        preferences.dictionary = "Offline edit"
        preferences.driveConnectionPending = true; preferences.driveSyncEnabled = true
        var fail = true, writes = 0
        DriveHTTP.handler = { request in
            if fail { return (503, Data("{}".utf8)) }
            if request.httpMethod != "GET" { writes += 1; return (200, Data("{}".utf8)) }
            if request.url?.query?.contains("alt=media") == true {
                return (200, Data(#"{"schemaVersion":1,"entries":{"dictionary":{"value":"Cloud","modifiedAt":1,"deviceId":"android"}}}"#.utf8))
            }
            return (200, Data(#"{"files":[{"id":"cloud-file","name":"opendictate-settings-v1-android.json"}]}"#.utf8))
        }
        let failed = GoogleDriveSync(preferences: preferences, client: client(), refreshToken: { "synthetic" },
            automaticallySync: false, deleteCredentials: {})
        failed.syncNow(); try await awaitStatus(failed, .failed)
        XCTAssertTrue(preferences.driveConnectionPending); XCTAssertEqual(writes, 0)
        preferences = Preferences(defaults: defaults); fail = false
        let resumed = GoogleDriveSync(preferences: preferences, client: client(), refreshToken: { "synthetic" },
            automaticallySync: false, deleteCredentials: {})
        resumed.syncNow(); try await awaitStatus(resumed, .sourceSelection)
        resumed.chooseSource(.local); try await awaitStatus(resumed, .synced)
        XCTAssertEqual(preferences.dictionary, "Offline edit")
        XCTAssertEqual(writes, 1); XCTAssertFalse(preferences.driveConnectionPending)
    }

    @MainActor func testCancellingOAuthClosesListenerAndDoesNotPersistCredentials() async {
        var saved = false
        let opened = expectation(description: "Browser URL ready")
        let authorization = GoogleDriveAuthorization(openBrowser: { _ in opened.fulfill(); return true }, saveCredentials: { _ in saved = true })
        let task = Task { try await authorization.connect(clientID: "synthetic.apps.googleusercontent.com", clientSecret: "synthetic-secret", client: client()) }
        await fulfillment(of: [opened], timeout: 5)
        task.cancel()
        do { _ = try await task.value; XCTFail("Should cancel") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertFalse(saved)
    }

    @MainActor func testSignInExpiryReportsTimeoutAndRejectsLateCallbackWithoutSavingCredentials() async throws {
        var callback: URL?, saved = false, attempt = 0
        let authorization = GoogleDriveAuthorization(openBrowser: { url in
            let query = URLComponents(url: url, resolvingAgainstBaseURL: false)!.queryItems!
            let redirect = query.first { $0.name == "redirect_uri" }!.value!
            let state = query.first { $0.name == "state" }!.value!
            callback = URL(string: redirect + "?code=synthetic-late-code&state=" + state)
            attempt += 1
            if attempt == 2, let url = callback {
                Task { _ = try? await URLSession.shared.data(from: url) }
            }
            return true
        }, saveCredentials: { _ in saved = true }, signInTimeoutNanoseconds: 100_000_000)
        do {
            _ = try await authorization.connect(clientID: "synthetic.apps.googleusercontent.com", clientSecret: "synthetic-secret", client: client())
            XCTFail("Should expire")
        } catch { XCTAssertEqual(error as? DriveError, .signInTimedOut) }
        let url = try XCTUnwrap(callback)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.timeoutIntervalForRequest = 1
        let session = URLSession(configuration: configuration)
        defer { session.invalidateAndCancel() }
        do { _ = try await session.data(from: url); XCTFail("Expired listener must be closed") }
        catch { XCTAssertEqual((error as? URLError)?.code, .cannotConnectToHost) }
        XCTAssertFalse(saved)
        DriveHTTP.handler = { _ in
            (200, Data(#"{"access_token":"synthetic-retry-access","refresh_token":"synthetic-retry-refresh","scope":"https://www.googleapis.com/auth/drive.appdata"}"#.utf8))
        }
        let token = try await authorization.connect(clientID: "synthetic.apps.googleusercontent.com", clientSecret: "synthetic-secret", client: client())
        XCTAssertEqual(token, "synthetic-retry-access")
        XCTAssertTrue(saved)
    }
}
