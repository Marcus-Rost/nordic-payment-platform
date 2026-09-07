import SwiftUI

struct LoginView: View {
    @EnvironmentObject private var oAuthClient: OAuthClient

    var body: some View {
        VStack(spacing: 24) {
            Spacer()
            Text("login_title".loc)
                .font(.title)
                .foregroundStyle(.indigo)
            Text("login_subtitle".loc)
                .multilineTextAlignment(.center)
                .padding(.horizontal)
            Button {
                Task { await oAuthClient.beginLogin() }
            } label: {
                Text("login_continue_with_bankid".loc)
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(Color.accentColor)
                    .foregroundColor(.white)
                    .cornerRadius(8)
            }
            .padding(.horizontal)
            Text("login_sca_notice".loc)
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding()
    }
}
