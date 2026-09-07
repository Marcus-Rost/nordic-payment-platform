// RFC 7636 PKCE — verifier + S256 challenge generator.

import CryptoKit
import Foundation

public struct Pkce: Codable, Equatable {
    public let verifier: String
    public let challenge: String
    public let state: String
    public let nonce: String
}

public enum PkceFlow {
    public static func newPkce() -> Pkce {
        let verifier = randomBase64Url(byteCount: 32)
        let digest = SHA256.hash(data: Data(verifier.utf8))
        let challenge = base64Url(Data(digest))
        let state = randomBase64Url(byteCount: 16)
        let nonce = randomBase64Url(byteCount: 16)
        return Pkce(verifier: verifier, challenge: challenge, state: state, nonce: nonce)
    }

    private static func randomBase64Url(byteCount: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: byteCount)
        _ = SecRandomCopyBytes(kSecRandomDefault, byteCount, &bytes)
        return base64Url(Data(bytes))
    }

    private static func base64Url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .trimmingCharacters(in: CharacterSet(charactersIn: "="))
    }
}
