import UserNotifications

@MainActor
final class ErrorNotifications: NSObject, UNUserNotificationCenterDelegate {
    private let center: UNUserNotificationCenter

    override init() {
        center = .current()
        super.init()
        center.delegate = self
    }

    func post(_ message: String) {
        Task {
            do {
                let settings = await center.notificationSettings()
                switch settings.authorizationStatus {
                case .notDetermined:
                    guard try await center.requestAuthorization(options: [.alert]) else { return }
                case .authorized, .provisional, .ephemeral: break
                default: return
                }
                let content = UNMutableNotificationContent()
                content.title = "OpenDictate"
                content.body = message
                // Replace the previous error instead of accumulating repeated failures.
                let request = UNNotificationRequest(identifier: "com.opendictate.mac.error",
                                                    content: content, trigger: nil)
                try await center.add(request)
            } catch {
                // Disabled/unavailable notifications must never open a window or recurse.
            }
        }
    }

    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter,
                                            willPresent notification: UNNotification,
                                            withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .list])
    }
}
