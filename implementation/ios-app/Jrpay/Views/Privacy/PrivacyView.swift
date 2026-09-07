import SwiftUI

private struct ExportRequest: Encodable { let format: String }
private struct ExportAck: Decodable { let requestId: String; let status: String }
private struct ErasureRequest: Encodable { let reason: String; let confirmed: Bool }

struct PrivacyView: View {
    @EnvironmentObject private var api: ApiClient
    @EnvironmentObject private var sca: ScaClient
    @EnvironmentObject private var session: SessionStore
    @State private var status: String?

    var body: some View {
        Form {
            Section {
                Button("privacy_request_export".loc) { Task { await requestExport() } }
            }
            Section {
                Button("privacy_request_erasure".loc, role: .destructive) {
                    Task { await requestErasure() }
                }
            }
            if let status { Section { Text(status) } }
        }
        .navigationTitle("privacy_title".loc)
    }

    private func requestExport() async {
        do {
            let ack: ExportAck = try await api.post(
                "/api/v1/customer/gdpr/exports",
                body: ExportRequest(format: "JSON_SIGNED")
            )
            status = "Export queued: \(ack.requestId)"
        } catch {
            status = (error as? ProblemError)?.errorDescription
        }
    }

    private func requestErasure() async {
        guard let customerId = session.session?.claims.jrpay_uid else { return }
        do {
            let notesHash = DlHash.sha256HexNfc("Erasure requested via iOS client")
            let inputs = DlHash.GdprAction(
                action: "ERASURE_REQUEST",
                customerId: customerId,
                notesHash: notesHash
            )
            let start = try await sca.start(inputs: inputs.toMap())
            let verify = try await sca.verify(sessionId: start.scaSessionId, proof: .bankIdSimulated("000000"))
            try await api.postEmpty(
                "/api/v1/customer/gdpr/erasure",
                body: ErasureRequest(reason: "Erasure via mobile app", confirmed: true),
                stepUpToken: verify.stepUpToken
            )
            status = "Erasure requested"
        } catch {
            status = (error as? ProblemError)?.errorDescription
        }
    }
}
