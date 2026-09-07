// RFC 9457 Problem Details + customer-side discriminators.

import Foundation

public struct Problem: Codable, Equatable {
    public let type: String
    public let title: String
    public let status: Int
    public let detail: String?
    public let instance: String?

    public init(type: String, title: String, status: Int, detail: String? = nil, instance: String? = nil) {
        self.type = type
        self.title = title
        self.status = status
        self.detail = detail
        self.instance = instance
    }
}

public struct ProblemError: Error, LocalizedError {
    public let problem: Problem
    public let httpResponse: HTTPURLResponse?

    public var errorDescription: String? {
        if let detail = problem.detail { return "\(problem.title) — \(detail)" }
        return problem.title
    }

    public var isStepUpRequired: Bool { problem.type == "urn:problem:step-up-required" }
    public var isDynamicLinkingMismatch: Bool { problem.type == "urn:problem:dynamic-linking-mismatch" }
    public var isVopMismatch: Bool { problem.type == "urn:problem:vop-mismatch" }
    public var isVopUnavailable: Bool { problem.type == "urn:problem:vop-unavailable" }
    public var isFraudHold: Bool { problem.type == "urn:problem:fraud-hold" }
    public var isSanctionsBlock: Bool { problem.type == "urn:problem:sanctions-block" }
    public var isMandateCapExceeded: Bool { problem.type == "urn:problem:mandate-cap-exceeded" }
    public var isInsufficientFunds: Bool { problem.type == "urn:problem:insufficient-funds" }
    public var isAccountFrozen: Bool { problem.type == "urn:problem:account-frozen" }
}
