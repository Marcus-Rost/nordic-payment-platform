// UC-G27 — Face ID / Touch ID cold-start unlock.

import Foundation
import LocalAuthentication

public enum BiometricResult {
    case success
    case failed
    case fallBackToBankId
    case unavailable
}

public enum BiometricUnlock {
    public static func prompt(reason: String) async -> BiometricResult {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            return .unavailable
        }
        return await withCheckedContinuation { cont in
            context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, localizedReason: reason) { ok, evalErr in
                if ok { cont.resume(returning: .success); return }
                let r: BiometricResult
                switch (evalErr as? LAError)?.code {
                case .userFallback: r = .fallBackToBankId
                case .userCancel, .systemCancel, .appCancel: r = .failed
                default: r = .failed
                }
                cont.resume(returning: r)
            }
        }
    }
}
