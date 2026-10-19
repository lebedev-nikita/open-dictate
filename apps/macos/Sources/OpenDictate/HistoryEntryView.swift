import SwiftUI
import OpenDictateCore

struct HistoryEntryView: View {
    let entry: HistoryEntry
    let russian: Bool
    let onCopy: () -> Void
    let onDelete: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(entry.date, format: .dateTime.day().month().hour().minute()).foregroundStyle(.secondary)
                Spacer()
                Button(action: onCopy) { Image(systemName: "doc.on.doc") }
                    .help(russian ? "Скопировать текст" : "Copy transcript")
                    .accessibilityLabel(russian ? "Скопировать текст" : "Copy transcript")
                Button(action: onDelete) { Image(systemName: "trash") }
                    .help(russian ? "Удалить текст" : "Delete transcript")
                    .accessibilityLabel(russian ? "Удалить текст" : "Delete transcript")
            }.font(.caption).buttonStyle(.borderless)
            let labels = HistoryTiming.labels(for: entry, russian: russian)
            if !labels.isEmpty {
                Text(labels.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.primary)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityLabel(HistoryTiming.labels(for: entry, russian: russian, abbreviated: false).joined(separator: ", "))
            }
            Text(entry.text).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
            Divider().padding(.top, 8)
        }
    }
}
