import SwiftUI

struct Profile: Codable {
    let customerId: String
    let name: String
    let email: String
    var phone: String?
    var address: String?
    var language: String?
}
private struct ProfilePatch: Encodable {
    let phone: String?
    let address: String?
    let language: String?
}

struct ProfileView: View {
    @EnvironmentObject private var api: ApiClient
    @EnvironmentObject private var session: SessionStore
    @State private var profile: Profile?
    @State private var phone = ""
    @State private var address = ""
    @State private var status: String?

    var body: some View {
        Form {
            if let profile {
                Section { Text(profile.name).font(.headline); Text(profile.email).foregroundStyle(.secondary) }
                Section {
                    TextField("Mobile", text: $phone).keyboardType(.phonePad)
                    TextField("Address", text: $address)
                }
                Section {
                    Button("common_save".loc) { Task { await save() } }
                }
            } else {
                Text("common_loading".loc)
            }
            Section {
                Button("common_logout".loc, role: .destructive) {
                    session.clear()
                }
            }
            if let status { Section { Text(status) } }
        }
        .navigationTitle("profile_title".loc)
        .task { await load() }
    }

    private func load() async {
        do {
            let p: Profile = try await api.get("/api/v1/customer/profile")
            profile = p
            phone = p.phone ?? ""
            address = p.address ?? ""
        } catch {
            status = (error as? ProblemError)?.errorDescription
        }
    }

    private func save() async {
        do {
            let p: Profile = try await api.patch(
                "/api/v1/customer/profile",
                body: ProfilePatch(phone: phone, address: address, language: nil)
            )
            profile = p
            status = "Saved"
        } catch {
            status = (error as? ProblemError)?.errorDescription
        }
    }
}
