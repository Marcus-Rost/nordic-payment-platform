// Typed URLSession-based API client. Mirrors the web + Android clients:
//   - injects bearer + Idempotency-Key + Step-Up-Token + traceparent
//   - encodes / decodes JSON via Codable
//   - throws ProblemError on 4xx/5xx (RFC 9457)

import Foundation

@MainActor
public final class ApiClient: ObservableObject {

    public let baseURL: URL
    public let session: URLSession
    public weak var sessionStore: SessionStore?

    private let encoder: JSONEncoder = {
        let e = JSONEncoder()
        e.dateEncodingStrategy = .iso8601
        return e
    }()
    private let decoder: JSONDecoder = {
        let d = JSONDecoder()
        d.dateDecodingStrategy = .iso8601
        return d
    }()

    public init(baseURL: URL, session: URLSession = .shared) {
        self.baseURL = baseURL
        self.session = session
    }

    public func get<T: Decodable>(
        _ path: String,
        query: [URLQueryItem] = []
    ) async throws -> T {
        try await call("GET", path: path, query: query, body: Optional<EmptyRequest>.none, stepUpToken: nil)
    }

    public func post<Req: Encodable, Res: Decodable>(
        _ path: String,
        body: Req,
        stepUpToken: String? = nil
    ) async throws -> Res {
        try await call("POST", path: path, query: [], body: body, stepUpToken: stepUpToken)
    }

    @discardableResult
    public func postEmpty<Req: Encodable>(
        _ path: String,
        body: Req,
        stepUpToken: String? = nil
    ) async throws -> Empty {
        try await call("POST", path: path, query: [], body: body, stepUpToken: stepUpToken) as Empty
    }

    public func patch<Req: Encodable, Res: Decodable>(
        _ path: String,
        body: Req,
        stepUpToken: String? = nil
    ) async throws -> Res {
        try await call("PATCH", path: path, query: [], body: body, stepUpToken: stepUpToken)
    }

    @discardableResult
    public func delete<Res: Decodable>(
        _ path: String,
        stepUpToken: String? = nil
    ) async throws -> Res {
        try await call("DELETE", path: path, query: [], body: Optional<EmptyRequest>.none, stepUpToken: stepUpToken)
    }

    // MARK: - internals

    private struct EmptyRequest: Codable {}
    public struct Empty: Codable {}

    private func call<Req: Encodable, Res: Decodable>(
        _ method: String,
        path: String,
        query: [URLQueryItem],
        body: Req?,
        stepUpToken: String?
    ) async throws -> Res {
        var components = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false)!
        if !query.isEmpty { components.queryItems = query }
        var req = URLRequest(url: components.url!)
        req.httpMethod = method

        if let token = sessionStore?.accessToken {
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let t = stepUpToken {
            req.setValue(t, forHTTPHeaderField: "Step-Up-Token")
        }
        if method != "GET" {
            req.setValue(UUID().uuidString, forHTTPHeaderField: "Idempotency-Key")
        }
        req.setValue(traceparent(), forHTTPHeaderField: "traceparent")

        if let body, !(body is EmptyRequest) {
            req.httpBody = try encoder.encode(body)
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        } else if method != "GET" {
            req.httpBody = Data()
            req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }

        let (data, response) = try await session.data(for: req)
        guard let http = response as? HTTPURLResponse else {
            throw URLError(.badServerResponse)
        }
        if !(200...299).contains(http.statusCode) {
            let problem = (try? decoder.decode(Problem.self, from: data)) ?? Problem(
                type: "urn:problem:unexpected",
                title: HTTPURLResponse.localizedString(forStatusCode: http.statusCode),
                status: http.statusCode,
                detail: String(data: data, encoding: .utf8)
            )
            throw ProblemError(problem: problem, httpResponse: http)
        }
        if Res.self == Empty.self {
            return Empty() as! Res
        }
        if data.isEmpty {
            return Empty() as! Res
        }
        return try decoder.decode(Res.self, from: data)
    }

    private func traceparent() -> String {
        let trace = randomHex(16)
        let span = randomHex(8)
        return "00-\(trace)-\(span)-01"
    }

    private func randomHex(_ count: Int) -> String {
        var bytes = [UInt8](repeating: 0, count: count)
        _ = SecRandomCopyBytes(kSecRandomDefault, count, &bytes)
        return bytes.map { String(format: "%02x", $0) }.joined()
    }
}
