// SessionStore — Keychain-backed token persistence + observable in-memory
// session for SwiftUI.
//
// Tokens are stored in the iOS Keychain (kSecAttrAccessibleWhenUnlocked) so
// they survive process kills but are unavailable when the device is locked.
// On UC-G27 the cold-start LocalAuthentication prompt gates exposure of the
// in-memory session even though the Keychain item itself is already
// accessibility-restricted.

import Foundation
import Security

@MainActor
public final class SessionStore: ObservableObject {

    @Published public private(set) var session: Session?

    public var accessToken: String? { session?.accessToken }

    public init() {
        self.session = loadFromKeychain()
    }

    public struct Session: Codable, Equatable {
        public let accessToken: String
        public let refreshToken: String?
        public let expiresAtSeconds: Int64
        public let claims: JwtClaims
        public func isExpired(skewSeconds: Int64 = 30) -> Bool {
            let now = Int64(Date().timeIntervalSince1970)
            return now >= expiresAtSeconds - skewSeconds
        }
    }

    public struct JwtClaims: Codable, Equatable {
        public let sub: String?
        public let exp: Int64?
        public let aud: String?
        public let scope: String?
        public let acr: String?
        public let jrpay_uid: String?
    }

    public func save(accessToken: String, refreshToken: String?, expiresIn: Int64) {
        let claims = decodeClaims(accessToken) ?? JwtClaims(sub: nil, exp: nil, aud: nil, scope: nil, acr: nil, jrpay_uid: nil)
        let now = Int64(Date().timeIntervalSince1970)
        let s = Session(
            accessToken: accessToken,
            refreshToken: refreshToken,
            expiresAtSeconds: now + expiresIn,
            claims: claims
        )
        self.session = s
        persist(s)
    }

    public func clear() {
        session = nil
        SecItemDelete([
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: KeychainService,
        ] as CFDictionary)
    }

    // MARK: - Keychain

    private let KeychainService = "no.jrpay.client.session"
    private let KeychainAccount = "session-blob"

    private func persist(_ s: Session) {
        guard let data = try? JSONEncoder().encode(s) else { return }
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: KeychainService,
            kSecAttrAccount: KeychainAccount,
        ]
        SecItemDelete(query as CFDictionary)
        var attrs = query
        attrs[kSecValueData] = data
        attrs[kSecAttrAccessible] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        _ = SecItemAdd(attrs as CFDictionary, nil)
    }

    private func loadFromKeychain() -> Session? {
        let query: [CFString: Any] = [
            kSecClass: kSecClassGenericPassword,
            kSecAttrService: KeychainService,
            kSecAttrAccount: KeychainAccount,
            kSecReturnData: true,
            kSecMatchLimit: kSecMatchLimitOne,
        ]
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else { return nil }
        let s = try? JSONDecoder().decode(Session.self, from: data)
        return (s?.isExpired() == false) ? s : nil
    }

    private func decodeClaims(_ token: String) -> JwtClaims? {
        let parts = token.split(separator: ".")
        guard parts.count >= 2 else { return nil }
        var b64 = String(parts[1])
            .replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        while b64.count % 4 != 0 { b64.append("=") }
        guard let data = Data(base64Encoded: b64) else { return nil }
        return try? JSONDecoder().decode(JwtClaims.self, from: data)
    }
}
