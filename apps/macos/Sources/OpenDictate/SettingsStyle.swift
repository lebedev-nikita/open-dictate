import AppKit
import SwiftUI

/// The only spacing values settings use. Gaps between groups exceed gaps inside them.
enum Space {
    static let xxs: CGFloat = 4
    static let xs: CGFloat = 8
    static let sm: CGFloat = 12
    static let md: CGFloat = 16
    static let lg: CGFloat = 24
    static let xl: CGFloat = 32
}

/// Cool-tinted surfaces: the page recedes, grouped cards sit one step above it.
enum Surface {
    static let page = adaptive(light: 0xF5F5F7, dark: 0x1C1C1E)
    static let sidebar = adaptive(light: 0xECECEF, dark: 0x161618)
    static let card = adaptive(light: 0xFFFFFF, dark: 0x2A2A2D)
    /// Inputs are one step lighter than cards in dark mode, so they never match the window background.
    static let field = adaptive(light: 0xFFFFFF, dark: 0x3A3A3D)
    static let fieldBorder = Color.primary.opacity(0.14)
    static let hairline = Color.primary.opacity(0.08)
    static let selection = Color.primary.opacity(0.08)

    private static func adaptive(light: UInt32, dark: UInt32) -> Color {
        func color(_ rgb: UInt32) -> NSColor {
            NSColor(srgbRed: CGFloat((rgb >> 16) & 255) / 255, green: CGFloat((rgb >> 8) & 255) / 255,
                    blue: CGFloat(rgb & 255) / 255, alpha: 1)
        }
        return Color(nsColor: NSColor(name: nil) { appearance in
            appearance.bestMatch(from: [.aqua, .darkAqua]) == .darkAqua ? color(dark) : color(light)
        })
    }
}

/// Page title and one line of context. The title names the page; the subtitle stays secondary.
struct PageHeader: View {
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: Space.xxs) {
            Text(title).font(.system(size: 22, weight: .semibold)).accessibilityAddTraits(.isHeader)
            if let subtitle {
                Text(subtitle).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

/// A labelled group of related rows on a raised card, with optional helper text below.
struct SettingsSection<Content: View>: View {
    var title: String?
    var footer: String?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: Space.xs) {
            if let title {
                Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(.secondary)
                    .padding(.horizontal, Space.xxs).accessibilityAddTraits(.isHeader)
            }
            SettingsCard { content }
            if let footer {
                Text(footer).font(.caption).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true).padding(.horizontal, Space.xxs)
            }
        }
    }
}

struct SettingsCard<Content: View>: View {
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 0) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Surface.card, in: RoundedRectangle(cornerRadius: 10))
            .overlay(RoundedRectangle(cornerRadius: 10).strokeBorder(Surface.hairline))
            .shadow(color: .black.opacity(0.04), radius: 1, y: 1)
    }
}

/// A label with optional detail on the leading side and its control on the trailing side.
struct SettingsRow<Accessory: View>: View {
    let title: String
    var detail: String?
    @ViewBuilder var accessory: Accessory

    var body: some View {
        HStack(alignment: .center, spacing: Space.md) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).fixedSize(horizontal: false, vertical: true)
                if let detail {
                    Text(detail).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                }
            }
            Spacer(minLength: Space.md)
            accessory
        }.settingsRowPadding()
    }
}

/// Separates rows inside a card, inset to the row content.
struct RowDivider: View {
    var body: some View { Divider().padding(.leading, Space.md) }
}

/// A short status beside a row's label, such as a granted permission.
struct StatusLabel: View {
    let title: String

    var body: some View {
        HStack(spacing: Space.xxs) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(.green)
            Text(title).foregroundStyle(.secondary)
        }.font(.callout)
    }
}

/// A text input drawn on its own surface, with a focus ring in the accent color.
struct SettingsField: ViewModifier {
    var focus: FocusState<Bool>.Binding?
    @FocusState private var ownFocus: Bool

    func body(content: Content) -> some View {
        let focused = focus ?? $ownFocus
        content.textFieldStyle(.plain).focused(focused)
            .padding(.horizontal, Space.xs).padding(.vertical, 6)
            .background(Surface.field, in: RoundedRectangle(cornerRadius: 6))
            .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(Surface.fieldBorder))
            .overlay(RoundedRectangle(cornerRadius: 6).inset(by: -1)
                .strokeBorder(Color.accentColor.opacity(focused.wrappedValue ? 0.6 : 0), lineWidth: 3))
    }
}

extension View {
    func settingsField(focus: FocusState<Bool>.Binding? = nil) -> some View {
        modifier(SettingsField(focus: focus))
    }

    func settingsRowPadding() -> some View {
        padding(.horizontal, Space.md).padding(.vertical, Space.sm).frame(minHeight: 44)
    }
}
