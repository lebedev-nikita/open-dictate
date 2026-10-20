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
            Text(entry.text).textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
            let details = HistoryTiming.details(for: entry, russian: russian, abbreviated: false)
            if !details.isEmpty {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(details.enumerated()), id: \.offset) { _, detail in
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            Text(detail.title).fixedSize(horizontal: false, vertical: true)
                            Spacer(minLength: 0)
                            if let duration = detail.duration { Text(duration).fixedSize() }
                        }
                    }
                }
                    .font(.caption).foregroundStyle(.primary).padding(.top, 4)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(HistoryTiming.labels(for: entry, russian: russian, abbreviated: false).joined(separator: ", "))
            }
            Divider().padding(.top, 8)
        }
    }
}
