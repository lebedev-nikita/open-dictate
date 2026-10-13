import Foundation
import Combine
import AppKit
import Network
import OpenDictateCore

@MainActor
final class GoogleDriveSync: ObservableObject {
    enum Status { case disconnected, waiting, busy, synced, failed, sourceSelection, authorization, signInTimedOut, configuration, clientConfiguration }
    @Published private(set) var status: Status = .disconnected
    @Published private(set) var lastSyncedAt: Date?
    var needsSignIn: Bool { status == .authorization || status == .signInTimedOut || status == .configuration || status == .clientConfiguration }
    private let preferences: Preferences
    private let client: GoogleDriveClient
    private let deleteCredentials: () throws -> Void
    private let refreshToken: (() async throws -> String)?
    private struct ConnectionChoice {
        let local: SettingsSyncDocument
        let cloud: SettingsSyncDocument
        var source: SettingsSyncDocument.ConnectionSource?
    }
    private var connectionChoice: ConnectionChoice?
    @Published private(set) var needsSourceSelection = false
    private let authorization = GoogleDriveAuthorization()
    private var task: Task<Void, Never>?
    private var debounce: Task<Void, Never>?
    private let automaticallySync: Bool
    private let pathMonitor = NWPathMonitor()
    private var networkAvailable = true
    private var wakeSubscription: AnyCancellable?
    private var subscription: AnyCancellable?
    private var generation = 0
    private var schedule = SettingsSyncSchedule()
    private let clockOrigin = ContinuousClock.now
    private var now: Int64 {
        let elapsed = clockOrigin.duration(to: .now).components
        return elapsed.seconds * 1_000 + elapsed.attoseconds / 1_000_000_000_000_000
    }
    private let localOnly: Bool

    init(preferences: Preferences, localOnly: Bool = false, client: GoogleDriveClient = GoogleDriveClient(),
         refreshToken: (() async throws -> String)? = nil, automaticallySync: Bool = true,
         deleteCredentials: @escaping () throws -> Void = DriveCredentialStore.delete) {
        self.preferences = preferences; self.localOnly = localOnly
        self.automaticallySync = automaticallySync
        self.client = client; self.refreshToken = refreshToken; self.deleteCredentials = deleteCredentials
        guard !localOnly else { return }
        status = preferences.driveSyncEnabled ? .waiting : .disconnected
        subscription = preferences.syncChanges.sink { [weak self] in
            self?.settingsChanged()
        }
        guard automaticallySync else { return }
        wakeSubscription = NSWorkspace.shared.notificationCenter.publisher(for: NSWorkspace.didWakeNotification)
            .sink { [weak self] _ in self?.refreshIfStale() }
        pathMonitor.pathUpdateHandler = { [weak self] path in
            let available = path.status == .satisfied
            Task { @MainActor [weak self] in
                guard let self else { return }
                let restored = !networkAvailable && available
                networkAvailable = available
                if restored { schedule.networkRestored(now: now) }
                pump()
            }
        }
        networkAvailable = false
        pathMonitor.start(queue: DispatchQueue(label: "OpenDictate.settings-connectivity"))
        pump()
    }

    private func settingsChanged() {
        guard preferences.driveSyncEnabled else { return }
        schedule.changed(now: now)
        pump()
    }

    private func pump() {
        guard !localOnly, preferences.driveSyncEnabled, !needsSourceSelection, task == nil else { return }
        debounce?.cancel()
        guard networkAvailable else { return }
        if automaticallySync { schedule.refreshIfStale(now: now, maxAge: SettingsSyncSchedule.backgroundInterval) }
        guard schedule.begin(now: now) else {
            if let delay = schedule.delayUntilReady(now: now, includePeriodic: automaticallySync) {
                debounce = Task { [weak self] in
                    do { try await Task.sleep(nanoseconds: UInt64(delay) * 1_000_000) } catch { return }
                    self?.pump()
                }
            }
            return
        }
        status = .busy
        let run = generation
        task = Task { [weak self] in
            guard let self else { return }
            var completion = SettingsSyncSchedule.Completion.deferred
            defer { finish(run: run, completion: completion) }
            do {
                let token: String
                if let refreshToken { token = try await refreshToken() }
                else { token = try await authorization.refresh(client: client) }
                completion = try await synchronize(token: token, run: run) ? .success : .deferred
            }
            catch {
                completion = pausesForSignIn(error) ? .authorization : .retry
                if generation == run { show(error) }
            }
        }
    }

    private func finish(run: Int, completion: SettingsSyncSchedule.Completion) {
        guard generation == run else { return }
        task = nil; schedule.finish(now: now, completion: completion); pump()
    }

    func connect(clientSecret: String) {
        guard !localOnly, task == nil else { return }
        status = .busy
        let run = generation
        let clientID = preferences.driveClientID.trimmingCharacters(in: .whitespacesAndNewlines)
        task = Task { [weak self] in
            guard let self else { return }
            var completion = SettingsSyncSchedule.Completion.deferred
            defer { finish(run: run, completion: completion) }
            do {
                let token = try await authorization.connect(clientID: clientID, clientSecret: clientSecret, client: client)
                try Task.checkCancellation()
                guard generation == run else { return }
                schedule.refresh()
                _ = schedule.begin(now: now)
                preferences.driveConnectionPending = true
                connectionChoice = nil; needsSourceSelection = false
                preferences.driveSyncEnabled = true
                completion = try await synchronize(token: token, run: run) ? .success : .deferred
            } catch {
                completion = pausesForSignIn(error) ? .authorization : .retry
                if generation == run { show(error) }
            }
        }
    }

    func chooseSource(_ source: SettingsSyncDocument.ConnectionSource) {
        guard needsSourceSelection, connectionChoice != nil else { return }
        connectionChoice?.source = source
        needsSourceSelection = false
        syncNow()
    }

    func syncNow() {
        guard !localOnly, preferences.driveSyncEnabled else { return }
        schedule.refresh()
        pump()
    }

    func refreshIfStale() {
        guard !localOnly, preferences.driveSyncEnabled else { return }
        schedule.refreshIfStale(now: now)
        pump()
    }

    private func synchronize(token: String, run: Int) async throws -> Bool {
        let remote = try await client.download(token: token, deviceID: preferences.syncDeviceID, forceRefresh: preferences.driveConnectionPending)
        try Task.checkCancellation()
        guard generation == run else { return false }
        // Do not apply or publish a dictionary edit until its quiet period has elapsed.
        if schedule.hasPendingChange {
            status = .waiting
            return false
        }
        var source: SettingsSyncDocument.ConnectionSource?
        if preferences.driveConnectionPending {
            try preferences.validateSync(remote.document)
            let local = try preferences.syncDocument()
            if local.needsConnectionChoice(with: remote.document) {
                if let choice = connectionChoice, let selected = choice.source,
                   choice.local.hasSameSettings(as: local), choice.cloud.hasSameSettings(as: remote.document) {
                    source = selected
                } else {
                    connectionChoice = ConnectionChoice(local: local, cloud: remote.document)
                    needsSourceSelection = true; status = .sourceSelection
                    return false
                }
            }
        }
        let merged = try preferences.mergeSync(remote.document, source: source)
        if remote.needsUpload(merged) {
            try await client.upload(token: token, deviceID: preferences.syncDeviceID, fileID: remote.ownFileID, document: merged)
        }
        try Task.checkCancellation()
        guard generation == run else { return false }
        preferences.driveConnectionPending = false
        connectionChoice = nil
        lastSyncedAt = Date(); status = .synced
        return true
    }

    func disconnect() {
        generation += 1
        task?.cancel(); task = nil; debounce?.cancel(); authorization.cancel()
        schedule = SettingsSyncSchedule()
        preferences.driveSyncEnabled = false
        preferences.driveConnectionPending = false
        connectionChoice = nil; needsSourceSelection = false
        do { try deleteCredentials(); status = .disconnected; lastSyncedAt = nil }
        catch { status = .authorization }
    }

    deinit { pathMonitor.cancel() }

    private func pausesForSignIn(_ error: Error) -> Bool {
        guard let error = error as? DriveError else { return false }
        return [.authorization, .signInTimedOut, .configuration, .clientConfiguration].contains(error)
    }

    private func show(_ error: Error) {
        if error is CancellationError { status = preferences.driveSyncEnabled ? .waiting : .disconnected }
        else if let error = error as? DriveError, error == .signInTimedOut { status = .signInTimedOut }
        else if let error = error as? DriveError, error == .authorization { status = .authorization }
        else if let error = error as? DriveError, error == .configuration { status = .configuration }
        else if let error = error as? DriveError, error == .clientConfiguration { status = .clientConfiguration }
        else { status = .failed }
    }
}
