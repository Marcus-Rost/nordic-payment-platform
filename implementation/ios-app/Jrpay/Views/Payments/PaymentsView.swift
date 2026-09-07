import SwiftUI

struct VopRequest: Encodable {
    let creditorIban: String
    let creditorName: String
}
struct VopResponse: Decodable {
    let result: String
    let actualNameMasked: String?
}
struct PaymentRequest: Encodable {
    let debtorAccountId: String
    let creditorIban: String
    let creditorName: String
    let amount: String
    let currency: String
    let reference: String?
    let executionMode: String
    let executionDate: String?
}
struct PaymentAck: Decodable {
    let paymentId: String
    let status: String
}

struct PaymentsView: View {
    @EnvironmentObject private var api: ApiClient
    @EnvironmentObject private var sca: ScaClient

    @State private var accounts: [AccountSummary] = []
    @State private var debtor: AccountSummary?
    @State private var creditorIban = ""
    @State private var creditorName = ""
    @State private var amount = ""
    @State private var reference = ""
    @State private var vop: String?
    @State private var status: String?

    var body: some View {
        NavigationStack {
            Form {
                Section("payments_from_account".loc) {
                    Picker("payments_from_account".loc, selection: $debtor) {
                        ForEach(accounts) { a in
                            Text("\(a.nickname ?? a.type) (\(a.currency))").tag(a as AccountSummary?)
                        }
                    }
                }
                Section {
                    TextField("payments_creditor_iban".loc, text: $creditorIban)
                        .textInputAutocapitalization(.characters)
                        .font(.body.monospaced())
                    TextField("payments_creditor_name".loc, text: $creditorName)
                    TextField("payments_amount".loc, text: $amount)
                        .keyboardType(.decimalPad)
                    TextField("payments_reference".loc, text: $reference)
                }
                if let vop {
                    Section { vopBadge(vop) }
                }
                Section {
                    Button("payments_submit_instant".loc) {
                        Task { await submit() }
                    }
                    .disabled(!canSubmit)
                }
                if let status {
                    Section { Text(status) }
                }
            }
            .navigationTitle("payments_new_instant".loc)
            .task { await loadAccounts() }
            .task(id: vopKey) { await runVop() }
        }
    }

    private var vopKey: String { "\(creditorIban)|\(creditorName)|\(amount)" }

    private var canSubmit: Bool {
        debtor != nil && !creditorIban.isEmpty && !creditorName.isEmpty &&
            (Decimal(string: amount) ?? 0) > 0 && vop != "MISMATCH" && vop != "NO_MATCH"
    }

    private func loadAccounts() async {
        do {
            let res: AccountList = try await api.get("/api/v1/customer/accounts")
            accounts = res.items
            if debtor == nil { debtor = res.items.first }
        } catch {
            status = (error as? ProblemError)?.errorDescription ?? error.localizedDescription
        }
    }

    private func runVop() async {
        guard !creditorIban.isEmpty, !creditorName.isEmpty, !amount.isEmpty else {
            vop = nil
            return
        }
        try? await Task.sleep(nanoseconds: 400_000_000)
        do {
            let res: VopResponse = try await api.post(
                "/api/v1/customer/payments/verify-payee",
                body: VopRequest(creditorIban: creditorIban, creditorName: creditorName)
            )
            vop = res.result
        } catch {
            if let pe = error as? ProblemError, pe.isVopUnavailable {
                vop = "UNAVAILABLE"
            } else { vop = nil }
        }
    }

    private func submit() async {
        guard let debtor else { return }
        do {
            let inputs = DlHash.PaymentInitiation(
                creditorIban: creditorIban,
                debtorAccountId: debtor.accountId,
                amount: amount,
                currency: debtor.currency,
                reference: reference.isEmpty ? nil : reference
            )
            let start = try await sca.start(inputs: inputs.toMap())
            let verify = try await sca.verify(sessionId: start.scaSessionId, proof: .bankIdSimulated("000000"))
            let ack: PaymentAck = try await api.post(
                "/api/v1/customer/payments",
                body: PaymentRequest(
                    debtorAccountId: debtor.accountId,
                    creditorIban: creditorIban,
                    creditorName: creditorName,
                    amount: amount,
                    currency: debtor.currency,
                    reference: reference.isEmpty ? nil : reference,
                    executionMode: "INSTANT",
                    executionDate: nil
                ),
                stepUpToken: verify.stepUpToken
            )
            status = "\(ack.status) (\(ack.paymentId))"
            creditorIban = ""; creditorName = ""; amount = ""; reference = ""; vop = nil
        } catch {
            status = (error as? ProblemError)?.errorDescription ?? error.localizedDescription
        }
    }

    @ViewBuilder
    private func vopBadge(_ result: String) -> some View {
        switch result {
        case "MATCH":
            Label("payments_vop_match".loc, systemImage: "checkmark.seal.fill").foregroundStyle(.green)
        case "CLOSE_MATCH":
            Label("payments_vop_close".loc, systemImage: "exclamationmark.triangle.fill").foregroundStyle(.orange)
        case "MISMATCH", "NO_MATCH":
            Label("payments_vop_mismatch".loc, systemImage: "xmark.octagon.fill").foregroundStyle(.red)
        case "UNAVAILABLE":
            Label("payments_vop_unavailable".loc, systemImage: "wifi.slash").foregroundStyle(.orange)
        default:
            EmptyView()
        }
    }
}
