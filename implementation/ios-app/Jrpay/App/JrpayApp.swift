// JrpayApp.swift — SwiftUI entry point.
//
// Single-window app with a TabView root. Auth state determines whether the
// LoginView or the authenticated tab graph is shown.

import SwiftUI

@main
struct JrpayApp: App {
    @StateObject private var session = SessionStore()
    @StateObject private var deps = AppDependencies.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(session)
                .environmentObject(deps.apiClient)
                .environmentObject(deps.scaClient)
                .environmentObject(deps.oAuthClient)
                .onOpenURL { url in
                    Task { await handleDeepLink(url) }
                }
        }
    }

    @MainActor
    private func handleDeepLink(_ url: URL) async {
        guard let comps = URLComponents(url: url, resolvingAgainstBaseURL: false) else { return }
        let code = comps.queryItems?.first(where: { $0.name == "code" })?.value
        let state = comps.queryItems?.first(where: { $0.name == "state" })?.value
        if let code, let state {
            await deps.oAuthClient.complete(code: code, state: state, into: session)
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var session: SessionStore

    var body: some View {
        if session.session == nil {
            LoginView()
        } else {
            MainTabView()
        }
    }
}

struct MainTabView: View {
    var body: some View {
        TabView {
            DashboardView()
                .tabItem { Label("nav_dashboard".loc, systemImage: "creditcard") }
            PaymentsView()
                .tabItem { Label("nav_payments".loc, systemImage: "arrow.up.right.square") }
            R2pInboxView()
                .tabItem { Label("nav_r2p".loc, systemImage: "tray") }
            MoreView()
                .tabItem { Label("nav_more".loc, systemImage: "ellipsis.circle") }
        }
    }
}

extension String {
    /// `Localizable.strings` lookup. Falls back to the key.
    var loc: String { NSLocalizedString(self, comment: "") }
}
