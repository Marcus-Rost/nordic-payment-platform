// SCA orchestration against /sca/start + /sca/{id}/verify.

import Foundation

@MainActor
public final class ScaClient: ObservableObject {

    public let api: ApiClient

    public init(api: ApiClient) {
        self.api = api
    }

    public struct StartRequest: Encodable {
        public let inputs: [String: AnyJsonValue]
        public let factorHint: String?
    }

    public struct StartResponse: Decodable {
        public let scaSessionId: String
        public let dl_hash: String
        public let challenge: Challenge
        public let exempt: Bool?
        public let stepUpToken: String?
    }

    public struct Challenge: Decodable {
        public let type: String
    }

    public struct VerifyRequest: Encodable {
        public let proof: Proof
    }

    public enum Proof: Encodable {
        case totp(String)
        case bankIdSimulated(String)

        enum CodingKeys: String, CodingKey { case type, code, assertion }

        public func encode(to encoder: Encoder) throws {
            var c = encoder.container(keyedBy: CodingKeys.self)
            switch self {
            case .totp(let code):
                try c.encode("TOTP", forKey: .type)
                try c.encode(code, forKey: .code)
            case .bankIdSimulated(let assertion):
                try c.encode("BANKID_SIMULATED", forKey: .type)
                try c.encode(assertion, forKey: .assertion)
            }
        }
    }

    public struct VerifyResponse: Decodable {
        public let status: String
        public let stepUpToken: String
    }

    public func start(inputs: [String: Any], factorHint: String? = nil) async throws -> StartResponse {
        let mapped = inputs.mapValues { AnyJsonValue.from($0) }
        return try await api.post("/sca/start", body: StartRequest(inputs: mapped, factorHint: factorHint))
    }

    public func verify(sessionId: String, proof: Proof) async throws -> VerifyResponse {
        try await api.post("/sca/\(sessionId)/verify", body: VerifyRequest(proof: proof))
    }
}

/// Helper Codable that round-trips arbitrary JSON values (string / number /
/// bool / null / array / object). Used as the value type in SCA inputs maps.
public enum AnyJsonValue: Encodable, Decodable {
    case string(String)
    case int(Int)
    case double(Double)
    case bool(Bool)
    case array([AnyJsonValue])
    case object([String: AnyJsonValue])
    case null

    public static func from(_ any: Any?) -> AnyJsonValue {
        switch any {
        case nil: return .null
        case let v as String: return .string(v)
        case let v as Bool: return .bool(v)
        case let v as Int: return .int(v)
        case let v as Double: return .double(v)
        case let v as [Any]: return .array(v.map(from))
        case let v as [String: Any]: return .object(v.mapValues(from))
        default: return .string(String(describing: any!))
        }
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .string(let v): try c.encode(v)
        case .int(let v): try c.encode(v)
        case .double(let v): try c.encode(v)
        case .bool(let v): try c.encode(v)
        case .array(let v): try c.encode(v)
        case .object(let v): try c.encode(v)
        case .null: try c.encodeNil()
        }
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null; return }
        if let v = try? c.decode(Bool.self) { self = .bool(v); return }
        if let v = try? c.decode(Int.self) { self = .int(v); return }
        if let v = try? c.decode(Double.self) { self = .double(v); return }
        if let v = try? c.decode(String.self) { self = .string(v); return }
        if let v = try? c.decode([AnyJsonValue].self) { self = .array(v); return }
        if let v = try? c.decode([String: AnyJsonValue].self) { self = .object(v); return }
        self = .null
    }
}
