import SwiftUI

struct Mandate: Decodable, Identifiable {
    let mandateId: String
    let creditorId: String
    let creditorName: String
    let debtorAccountId: String
    let capAmount: String?
    let capCurrency: String?
    let status: String
    var id: String { mandateId }
}
private struct MandateList: Decodable { let items: [Mandate] }

struct MandatesView: View {
    @EnvironmentObject private var api: ApiClient
    @EnvironmentObject private var sca: ScaClient
    @State private var items: [Mandate] = []
    @State private var status: String?

    var body: some View {
        List(items) { m in
            VStack(alignment: .leading) {
                Text(m.creditorName).font(.headline)
                Text("\(m.capAmount ?? "—") \(m.capCurrency ?? "") · \(m.status)").font(.caption)
                if m.status == "ACTIVE" {
                    Button("mandates_terminate".loc, role: .destructive) {
                        Task { await terminate(m) }
                    }.padding(.top, 4)
                }
            }.padding(.vertical, 4)
        }
        .navigationTitle("mandates_title".loc)
        .task { await reload() }
        .overlay(alignment: .top) { status.map { Text($0).foregroundStyle(.red).padding() } }
    }

    private func reload() async {
        do {
            let res: MandateList = try await api.get("/api/v1/customer/mandates")
            items = res.items
        } catch {
            status = (error as? ProblemError)?.errorDescription
        }
    }

    private func terminate(_ m: Mandate) async {
        do {
            let inputs = DlHash.MandateAction(
                action: "TERMINATE",
                creditorId: m.creditorId,
                debtorAccountId: m.debtorAccountId
            )
            let start = try await sca.start(inputs: inputs.toMap())
            let verify = try await sca.verify(sessionId: start.scaSessionId, proof: .bankIdSimulated("000000"))
            let _: ApiClient.Empty = try await api.delete("/api/v1/customer/mandates/\(m.mandateId)",
                                                          stepUpToken: verify.stepUpToken)
            await reload()
        } catch {
            status = (error as? ProblemError)?.errorDescription
        }
    }
}
