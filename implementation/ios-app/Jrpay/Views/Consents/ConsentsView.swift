import SwiftUI

struct Consent: Decodable, Identifiable {
    let consentId: String
    let tppName: String
    let consentType: String
    let expiresAt: String
    let status: String
    var id: String { consentId }
}
private struct ConsentList: Decodable { let items: [Consent] }

struct ConsentsView: View {
    @EnvironmentObject private var api: ApiClient
    @State private var items: [Consent] = []

    var body: some View {
        List(items) { c in
            VStack(alignment: .leading) {
                Text(c.tppName).font(.headline)
                Text("\(c.consentType) · \(c.expiresAt)").font(.caption)
                if c.status == "ACTIVE" {
                    Button("consents_revoke".loc, role: .destructive) { Task { await revoke(c) } }
                }
            }
        }
        .navigationTitle("consents_title".loc)
        .task { await reload() }
    }

    private func reload() async {
        if let res: ConsentList = try? await api.get("/api/v1/customer/consents") {
            items = res.items
        }
    }

    private func revoke(_ c: Consent) async {
        let _: ApiClient.Empty? = try? await api.delete("/api/v1/customer/consents/\(c.consentId)")
        await reload()
    }
}
