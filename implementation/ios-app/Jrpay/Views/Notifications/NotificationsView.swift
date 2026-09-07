import SwiftUI

struct Notif: Decodable, Identifiable {
    let notificationId: String
    let title: String
    let body: String
    let severity: String
    let receivedAt: String
    let read: Bool
    var id: String { notificationId }
}
private struct NotifList: Decodable { let items: [Notif] }

struct NotificationsView: View {
    @EnvironmentObject private var api: ApiClient
    @State private var items: [Notif] = []

    var body: some View {
        List(items) { n in
            VStack(alignment: .leading) {
                Text(n.title).font(.headline)
                Text(n.body)
                Text("\(n.severity) · \(n.receivedAt)").font(.caption).foregroundStyle(.secondary)
            }.opacity(n.read ? 0.6 : 1.0)
        }
        .navigationTitle("notifications_title".loc)
        .task {
            if let res: NotifList = try? await api.get("/api/v1/customer/notifications") {
                items = res.items
            }
        }
    }
}
