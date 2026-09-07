// Dynamic-linking hash — byte-identical to the Java services and the web /
// Android clients (gui/architecture/DYNAMIC_LINKING.md).
//
// Algorithm: SHA-256 of canonical JSON
//   - members lex-sorted by UTF-16 code units
//   - strings UTF-8 NFC
//   - no whitespace
//   - arrays preserve insertion order
//   - amounts MUST be strings (numbers forbidden in canonical inputs)
// Output: lowercase hex of the 32-byte digest.
//
// On Apple platforms this uses CryptoKit. On Linux (and any platform without
// CryptoKit) it falls back to Apple's swift-crypto package, whose `Crypto`
// module is API-compatible with CryptoKit. Either path produces the same
// 32-byte digest, so the parity guarantee with the Java/web/Android clients
// holds on every platform we build for.

#if canImport(CryptoKit)
import CryptoKit
#else
import Crypto
#endif
import Foundation

public enum DlHash {

    public struct Result: Equatable {
        public let canonical: String
        public let hex: String
    }

    // MARK: - Customer-side input classes

    public struct PaymentInitiation {
        public let creditorIban: String
        public let debtorAccountId: String
        public let amount: String
        public let currency: String
        public let reference: String?
        public let executionDate: String?

        public init(creditorIban: String, debtorAccountId: String, amount: String,
                    currency: String, reference: String? = nil, executionDate: String? = nil) {
            self.creditorIban = creditorIban
            self.debtorAccountId = debtorAccountId
            self.amount = amount
            self.currency = currency
            self.reference = reference
            self.executionDate = executionDate
        }

        public func toMap() -> [String: Any] {
            var m: [String: Any] = [
                "amount": amount,
                "class": "payment-initiation",
                "creditorIban": creditorIban,
                "currency": currency,
                "debtorAccountId": debtorAccountId,
            ]
            if let executionDate { m["executionDate"] = executionDate }
            if let reference { m["reference"] = reference }
            return m
        }
    }

    public struct MandateAction {
        public let action: String          // CREATE | AMEND | TERMINATE
        public let creditorId: String
        public let debtorAccountId: String
        public let capAmount: String?
        public let capCurrency: String?

        public init(action: String, creditorId: String, debtorAccountId: String,
                    capAmount: String? = nil, capCurrency: String? = nil) {
            self.action = action
            self.creditorId = creditorId
            self.debtorAccountId = debtorAccountId
            self.capAmount = capAmount
            self.capCurrency = capCurrency
        }

        public func toMap() -> [String: Any] {
            var m: [String: Any] = [
                "action": action,
                "class": "mandate-action",
                "creditorId": creditorId,
                "debtorAccountId": debtorAccountId,
            ]
            if let capAmount { m["capAmount"] = capAmount }
            if let capCurrency { m["capCurrency"] = capCurrency }
            return m
        }
    }

    public struct ConsentGrant {
        public let consentType: String     // AISP | PISP | CBPII | VRP
        public let tppId: String
        public let expiresAt: String
        public let accountIds: [String]?

        public init(consentType: String, tppId: String, expiresAt: String, accountIds: [String]? = nil) {
            self.consentType = consentType
            self.tppId = tppId
            self.expiresAt = expiresAt
            self.accountIds = accountIds
        }

        public func toMap() -> [String: Any] {
            var m: [String: Any] = [
                "class": "consent-grant",
                "consentType": consentType,
                "expiresAt": expiresAt,
                "tppId": tppId,
            ]
            if let accountIds, !accountIds.isEmpty { m["accountIds"] = accountIds }
            return m
        }
    }

    public struct GdprAction {
        public let action: String          // EXPORT | ERASURE_REQUEST
        public let customerId: String
        public let notesHash: String
        public init(action: String, customerId: String, notesHash: String) {
            self.action = action
            self.customerId = customerId
            self.notesHash = notesHash
        }
        public func toMap() -> [String: Any] {
            ["action": action, "class": "gdpr-action",
             "customerId": customerId, "notesHash": notesHash]
        }
    }

    // MARK: - public hashing

    public static func hash(_ map: [String: Any]) -> Result {
        let canonical = canonicalise(map)
        let digest = SHA256.hash(data: Data(canonical.utf8))
        return Result(canonical: canonical, hex: digest.map { String(format: "%02x", $0) }.joined())
    }

    public static func sha256HexNfc(_ value: String) -> String {
        let nfc = NSString(string: value).precomposedStringWithCanonicalMapping
        let digest = SHA256.hash(data: Data(nfc.utf8))
        return digest.map { String(format: "%02x", $0) }.joined()
    }

    // MARK: - canonical JSON

    private static func canonicalise(_ value: Any?) -> String {
        if value is NSNull { return "null" }
        if let v = value as? String { return canonicaliseString(v) }
        if let v = value as? Bool { return v ? "true" : "false" }
        if let v = value as? Int { return String(v) }
        if let v = value as? Int64 { return String(v) }
        if let v = value as? Double { return v.isFinite ? String(v) : "null" }
        if let arr = value as? [Any?] {
            return "[" + arr.map { canonicalise($0) }.joined(separator: ",") + "]"
        }
        if let dict = value as? [String: Any?] {
            // Sort by UTF-16 code-unit comparison (Swift's `<` on String does this).
            let entries = dict.keys.sorted()
            return "{" + entries.map { k in
                "\(canonicaliseString(k)):\(canonicalise(dict[k] ?? NSNull()))"
            }.joined(separator: ",") + "}"
        }
        if let any = value, let mirrored = any as? [String: Any] {
            // Cast to nullable-value variant.
            let nullable: [String: Any?] = mirrored.mapValues { $0 as Any? }
            return canonicalise(nullable)
        }
        if value == nil { return "null" }
        return canonicaliseString(String(describing: value!))
    }

    private static func canonicaliseString(_ value: String) -> String {
        let nfc = NSString(string: value).precomposedStringWithCanonicalMapping
        var out = "\""
        for ch in nfc.unicodeScalars {
            switch ch {
            case "\"": out += "\\\""
            case "\\": out += "\\\\"
            case "\u{08}": out += "\\b"
            case "\u{0c}": out += "\\f"
            case "\n": out += "\\n"
            case "\r": out += "\\r"
            case "\t": out += "\\t"
            default:
                if ch.value < 0x20 {
                    out += String(format: "\\u%04x", ch.value)
                } else {
                    out += String(ch)
                }
            }
        }
        out += "\""
        return out
    }
}
