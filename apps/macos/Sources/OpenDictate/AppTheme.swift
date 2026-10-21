import SwiftUI

enum AppTheme: String, CaseIterable {
    case system, light, dark

    var appearance: NSAppearance? {
        switch self {
        case .system: return nil
        case .light: return NSAppearance(named: .aqua)
        case .dark: return NSAppearance(named: .darkAqua)
        }
    }

    var symbol: String {
        switch self {
        case .system: return "circle.lefthalf.filled"
        case .light: return "sun.max"
        case .dark: return "moon"
        }
    }

    func title(russian: Bool) -> String {
        switch self {
        case .system: return russian ? "Системная тема" : "System theme"
        case .light: return russian ? "Светлая тема" : "Light theme"
        case .dark: return russian ? "Тёмная тема" : "Dark theme"
        }
    }
}

// Apply the override at the AppKit window boundary. Resetting SwiftUI's
// preferredColorScheme to nil can leave NSHostingView's content in the old
// scheme even though the window chrome has resumed following the system.
struct AppThemeAppearance: NSViewRepresentable {
    var theme: AppTheme

    func makeNSView(context: Context) -> ThemeView { ThemeView(theme: theme) }

    func updateNSView(_ view: ThemeView, context: Context) {
        view.theme = theme
        view.window?.appearance = theme.appearance
    }

    final class ThemeView: NSView {
        var theme: AppTheme

        init(theme: AppTheme) {
            self.theme = theme
            super.init(frame: .zero)
        }

        required init?(coder: NSCoder) { nil }

        override func viewDidMoveToWindow() {
            super.viewDidMoveToWindow()
            window?.appearance = theme.appearance
        }
    }
}
