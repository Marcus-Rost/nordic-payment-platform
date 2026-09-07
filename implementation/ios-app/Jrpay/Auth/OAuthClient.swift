// OAuthClient — PKCE flow against the JRPay identity service.
//
// On iOS BankID is reached via `ASWebAuthenticationSession`, the system-
// hosted browser that returns the authorization-code through a custom-scheme
// callback (configured in Info.plist as the URL type `jrpayclient`).

import AuthenticationServices
import Foundation
import SwiftUI

@MainActor
public final class OAuthClient: NSObject, ObservableObject, ASWebAuthenticationPresentationContextProviding {

    public let identityBaseURL: URL
    public let clientId: String
    public let redirectURI: String
    public let scope: String

    private var pending: Pkce?

    public init(
        identityBaseURL: URL,
        clientId: String = "jrpay-client-ios",
        redirectURI: String = "jrpayclient://oauth-callback",
        scope: String = "openid customer:read customer:write"
    ) {
        self.identityBaseURL = identityBaseURL
        self.clientId = clientId
        self.redirectURI = redirectURI
        self.scope = scope
    }

    public func beginLogin(returnTo: String = "/") async {
        let pkce = PkceFlow.newPkce()
        self.pending = pkce
        var c = URLComponents(url: identityBaseURL.appendingPathComponent("/oauth2/authorize"), resolvingAgainstBaseURL: false)!
        c.queryItems = [
            .init(name: "response_type", value: "code"),
            .init(name: "client_id", value: clientId),
            .init(name: "redirect_uri", value: redirectURI),
            .init(name: "scope", value: scope),
            .init(name: "state", value: pkce.state),
            .init(name: "nonce", value: pkce.nonce),
            .init(name: "code_challenge", value: pkce.challenge),
            .init(name: "code_challenge_method", value: "S256"),
        ]
        guard let url = c.url else { return }
        let session = ASWebAuthenticationSession(url: url, callbackURLScheme: "jrpayclient") { [weak self] callback, _ in
            // The actual exchange happens in `complete(...)` triggered by
            // the `.onOpenURL` modifier on `RootView`. This callback is a
            // belt-and-braces for older iOS that doesn't fire SceneDelegate.
            _ = callback
            _ = self
        }
        session.presentationContextProvider = self
        session.prefersEphemeralWebBrowserSession = false
        session.start()
    }

    public func complete(code: String, state: String, into store: SessionStore) async {
        guard let pkce = pending, pkce.state == state else { return }
        defer { pending = nil }
        do {
            let resp = try await tokenCall(params: [
                "grant_type": "authorization_code",
                "code": code,
                "client_id": clientId,
                "redirect_uri": redirectURI,
                "code_verifier": pkce.verifier,
            ])
            store.save(
                accessToken: resp.access_token,
                refreshToken: resp.refresh_token,
                expiresIn: resp.expires_in
            )
        } catch {
            // Surfaced by the caller; ignored here so the auth flow can be
            // retried by tapping "Continue with BankID" again.
        }
    }

    public func refresh(refreshToken: String) async throws -> TokenResponse {
        try await tokenCall(params: [
            "grant_type": "refresh_token",
            "refresh_token": refreshToken,
            "client_id": clientId,
        ])
    }

    public struct TokenResponse: Decodable {
        public let access_token: String
        public let refresh_token: String?
        public let token_type: String
        public let expires_in: Int64
        public let scope: String?
        public let id_token: String?
    }

    private func tokenCall(params: [String: String], path: String = "/oauth2/token") async throws -> TokenResponse {
        var req = URLRequest(url: identityBaseURL.appendingPathComponent(path))
        req.httpMethod = "POST"
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        let formBody = params.map { "\($0.key)=\(urlEncode($0.value))" }.joined(separator: "&")
        req.httpBody = formBody.data(using: .utf8)
        let (data, response) = try await URLSession.shared.data(for: req)
        guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
            throw URLError(.badServerResponse)
        }
        return try JSONDecoder().decode(TokenResponse.self, from: data)
    }

    private func urlEncode(_ s: String) -> String {
        s.addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed) ?? s
    }

    public func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        // SwiftUI doesn't have a window per-se; pick the first connected scene.
        UIApplication.shared.connectedScenes
            .compactMap { ($0 as? UIWindowScene)?.keyWindow }
            .first ?? ASPresentationAnchor()
    }
}
