import SwiftUI

struct R2pRequest: Decodable, Identifiable {
    let requestId: String
    let creditorName: String
    let creditorIban: String
    let debtorAccountId: String
    let amount: String
    let currency: String
    let reference: String?
    let dueDate: String?
    let status: String
    var id: String { requestId }
}
private struct R2pList: Decodable { let items: [R2pRequest] }

struct R2pInboxView: View {
    @EnvironmentObject private var api: ApiClient
    @EnvironmentObject private var sca: ScaClient
    @State private var items: [R2pRequest] = []
    @State private var status: String?

    var body: some View {
        NavigationStack {
            List(items) { r in
                VStack(alignment: .leading, spacing: 4) {
                    Text(r.creditorName).font(.headline)
                    Text("\(r.amount) \(r.currency)").font(.title3.monospacedDigit())
                    if let dueDate = r.dueDate { Text(dueDate).font(.caption).foregroundStyle(.secondary) }
                    HStack {
                        Button("r2p_accept".loc) { Task { await accept(r) } }.buttonStyle(.borderedProminent)
                        Button("r2p_decline".loc) { Task { await decline(r) } }.buttonStyle(.bordered)
                    }.padding(.top, 4)
                }
                .padding(.vertical, 4)
            }
            .navigationTitle("r2p_title".loc)
            .overlay {
                if items.isEmpty { Text("r2p_no_requests".loc).foregroundStyle(.secondary) }
            }
            .task { await reload() }
            .refreshable { await reload() }
            .overlay(alignment: .top) { status.map { Text($0).foregroundStyle(.red).padding() } }
        }
    }

    private func reload() async {
        do {
            let res: R2pList = try await api.get("/api/v1/customer/r2p", query: [.init(name: "status", value: "PENDING")])
            items = res.items
        } catch {
            status = (error as? ProblemError)?.errorDescription
        }
    }

    private func accept(_ r: R2pRequest) async {
        do {
            let inputs = DlHash.PaymentInitiation(
                creditorIban: r.creditorIban,
                debtorAccountId: r.debtorAccountId,
                amount: r.amount,
                currency: r.currency,
                reference: r.reference
            )
            let start = try await sca.start(inputs: inputs.toMap())
            let verify = try await sca.verify(sessionId: start.scaSessionId, proof: .bankIdSimulated("000000"))
            try await api.postEmpty("/api/v1/customer/r2p/\(r.requestId)/accept",
                                    body: ApiClient.Empty(),
                                    stepUpToken: verify.stepUpToken)
            await reload()
        } catch {
            status = (error as? ProblemError)?.errorDescription
        }
    }

    private func decline(_ r: R2pRequest) async {
        struct Body: Encodable { let reason: String }
        do {
            try await api.postEmpty("/api/v1/customer/r2p/\(r.requestId)/decline", body: Body(reason: "OTHER"))
            await reload()
        } catch {
            status = (error as? ProblemError)?.errorDescription
        }
    }
}
