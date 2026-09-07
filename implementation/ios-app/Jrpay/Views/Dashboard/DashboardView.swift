import SwiftUI

public struct AccountSummary: Decodable, Identifiable {
    public let accountId: String
    public let iban: String?
    public let ibanMasked: String?
    public let nickname: String?
    public let type: String
    public let currency: String
    public let bookedBalance: String?
    public let availableBalance: String?
    public let status: String

    public var id: String { accountId }
}

struct AccountList: Decodable { let items: [AccountSummary] }

struct DashboardView: View {
    @EnvironmentObject private var api: ApiClient
    @State private var accounts: [AccountSummary] = []
    @State private var error: String?

    var body: some View {
        NavigationStack {
            List(accounts) { account in
                AccountRow(account: account)
            }
            .listStyle(.insetGrouped)
            .navigationTitle("dashboard_title".loc)
            .overlay(alignment: .top) {
                if let error { Text(error).foregroundStyle(.red).padding() }
            }
            .task {
                do {
                    let res: AccountList = try await api.get("/api/v1/customer/accounts")
                    accounts = res.items
                } catch {
                    self.error = (error as? ProblemError)?.errorDescription ?? error.localizedDescription
                }
            }
        }
    }
}

private struct AccountRow: View {
    let account: AccountSummary

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(account.nickname ?? account.type).font(.headline)
            Text(account.iban ?? account.ibanMasked ?? "")
                .font(.caption.monospaced())
                .foregroundStyle(.secondary)
            HStack {
                Text("accounts_available".loc).foregroundStyle(.secondary)
                Spacer()
                Text("\(account.availableBalance ?? "0.00") \(account.currency)")
                    .font(.title2)
                    .monospacedDigit()
            }
        }
        .padding(.vertical, 4)
    }
}
