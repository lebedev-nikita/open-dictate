import Foundation

/// A short, in-memory envelope of input levels, ordered from oldest to newest.
public struct AudioLevelHistory: Equatable, Sendable {
    public static let count = 10
    public private(set) var samples = Array(repeating: Float.zero, count: count)
    private static let updateInterval: Duration = .milliseconds(100)
    private var nextUpdate: Duration?

    public init() {}

    /// Preserve the original one-second display window independently of audio callback size.
    @discardableResult
    public mutating func append(_ level: Float, at time: Duration) -> Bool {
        let deadline = nextUpdate ?? time
        guard time >= deadline else { return false }
        // Keep the cadence anchored when a callback arrives between display ticks.
        // After a gap, advance once rather than rapidly replaying missed updates.
        let intervals = Int((time - deadline) / Self.updateInterval) + 1
        nextUpdate = deadline + Self.updateInterval * intervals
        samples.removeFirst()
        samples.append(level.isFinite ? min(1, max(0, level)) : 0)
        return true
    }

    public mutating func reset() { self = Self() }
}
