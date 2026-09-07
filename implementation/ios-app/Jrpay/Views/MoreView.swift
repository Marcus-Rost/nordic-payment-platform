import SwiftUI

struct MoreView: View {
    @EnvironmentObject private var session: SessionStore

    var body: some View {
        NavigationStack {
            List {
                NavigationLink("mandates_title".loc) { MandatesView() }
                NavigationLink("beneficiaries_title".loc) { BeneficiariesView() }
                NavigationLink("consents_title".loc) { ConsentsView() }
                NavigationLink("notifications_title".loc) { NotificationsView() }
                NavigationLink("profile_title".loc) { ProfileView() }
                NavigationLink("privacy_title".loc) { PrivacyView() }

                Section {
                    Button("common_logout".loc, role: .destructive) {
                        session.clear()
                    }
                }
            }
            .navigationTitle("nav_more".loc)
        }
    }
}
