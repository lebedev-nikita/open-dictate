/// Batches edits and throttles automatic checks using monotonic milliseconds.
public struct SettingsSyncSchedule {
    public enum Completion { case success, retry, authorization, deferred }
    public static let editDelay: Int64 = 15_000
    public static let backgroundInterval: Int64 = 900_000
    public static let freshnessInterval: Int64 = 300_000
    private var changeDueAt: Int64?
    private var refreshRequested = false
    private var lastSuccessAt: Int64?
    private var retryDueAt: Int64?
    private var failures = 0
    private var authorizationRequired = false
    public private(set) var busy = false
    public var hasPendingChange: Bool { changeDueAt != nil }
    public init() {}

    public mutating func changed(now: Int64) { changeDueAt = now + Self.editDelay }
    /// Explicit requests bypass retry backoff; immediate requests also flush the current edits.
    public mutating func refresh(immediately: Bool = false) {
        refreshRequested = true; retryDueAt = nil; authorizationRequired = false
        if immediately { changeDueAt = nil }
    }
    public mutating func refreshIfStale(now: Int64, maxAge: Int64 = freshnessInterval) {
        guard !busy, !authorizationRequired else { return }
        if lastSuccessAt.map({ now - $0 >= maxAge }) ?? true { refreshRequested = true }
    }
    public mutating func networkRestored(now: Int64) {
        guard !busy, !authorizationRequired else { return }
        if hasPendingChange || retryDueAt != nil || lastSuccessAt.map({ now - $0 >= Self.freshnessInterval }) ?? true {
            refreshRequested = true; retryDueAt = nil
        }
    }
    public func delayUntilReady(now: Int64, includePeriodic: Bool = false) -> Int64? {
        guard !busy, !authorizationRequired else { return nil }
        if refreshRequested || changeDueAt != nil || retryDueAt != nil {
            return max(0, max(changeDueAt ?? now, retryDueAt ?? now) - now)
        }
        return includePeriodic ? max(0, (lastSuccessAt.map { $0 + Self.backgroundInterval } ?? now) - now) : nil
    }
    public mutating func begin(now: Int64) -> Bool {
        guard !busy, !authorizationRequired, changeDueAt.map({ $0 <= now }) ?? true,
              retryDueAt.map({ $0 <= now }) ?? true else { return false }
        guard refreshRequested || changeDueAt != nil || retryDueAt != nil else { return false }
        changeDueAt = nil; refreshRequested = false; retryDueAt = nil; busy = true
        return true
    }
    public mutating func finish(now: Int64, completion: Completion) {
        busy = false
        switch completion {
        case .success:
            lastSuccessAt = now; failures = 0; retryDueAt = nil
        case .retry:
            let delays: [Int64] = [60_000, 300_000, 900_000, 1_800_000]
            retryDueAt = now + delays[min(failures, delays.count - 1)]
            failures = min(failures + 1, delays.count)
        case .authorization:
            authorizationRequired = true; retryDueAt = nil
        case .deferred: break
        }
    }
}
