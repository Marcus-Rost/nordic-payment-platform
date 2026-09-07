import SwiftUI

struct Beneficiary: Decodable, Identifiable {
    let beneficiaryId: String
    let name: String
    let iban: String
    let trusted: Bool
    var id: String { beneficiaryId }
}
private struct BeneficiaryList: Decodable { let items: [Beneficiary] }
private struct TrustToggle: Encodable { let trusted: Bool }

struct BeneficiariesView: View {
    @EnvironmentObject private var api: ApiClient
    @State private var items: [Beneficiary] = []

    var body: some View {
        List(items) { b in
            VStack(alignment: .leading) {
                Text(b.name).font(.headline)
                Text(b.iban).font(.caption.monospaced())
                HStack {
                    Button(b.trusted ? "beneficiaries_untrust".loc : "beneficiaries_trust".loc) {
                        Task { await toggleTrust(b) }
                    }.buttonStyle(.bordered)
                    Button("common_delete".loc, role: .destructive) {
                        Task { await remove(b) }
                    }.buttonStyle(.bordered)
                }.padding(.top, 4)
            }
        }
        .navigationTitle("beneficiaries_title".loc)
        .task { await reload() }
    }

    private func reload() async {
        if let res: BeneficiaryList = try? await api.get("/api/v1/customer/beneficiaries") {
            items = res.items
        }
    }

    private func toggleTrust(_ b: Beneficiary) async {
        try? await api.postEmpty("/api/v1/customer/beneficiaries/\(b.beneficiaryId)/trust",
                                 body: TrustToggle(trusted: !b.trusted))
        await reload()
    }

    private func remove(_ b: Beneficiary) async {
        let _: ApiClient.Empty? = try? await api.delete("/api/v1/customer/beneficiaries/\(b.beneficiaryId)")
        await reload()
    }
}
