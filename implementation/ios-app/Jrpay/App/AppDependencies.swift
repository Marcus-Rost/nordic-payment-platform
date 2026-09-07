// AppDependencies — manual DI graph. Lazy holders mirror the Android
// JrpayApplication; this is a singleton because SwiftUI's @StateObject
// wants a stable identity for the lifetime of the app process.

import Foundation
import SwiftUI

@MainActor
public final class AppDependencies: ObservableObject {

    public static let shared = AppDependencies()

    public let apiClient: ApiClient
    public let scaClient: ScaClient
    public let oAuthClient: OAuthClient
    public let offlineCache: OfflineCache

    private init() {
        // 10.0.2.2-style host loopback isn't a thing on iOS. The simulator
        // shares the host's loopback already; physical-device builds need
        // either a LAN IP or the staging URL.
        let api = ApiClient(baseURL: URL(string: "http://localhost")!)
        self.apiClient = api
        self.scaClient = ScaClient(api: api)
        self.oAuthClient = OAuthClient(identityBaseURL: URL(string: "http://localhost:8097")!)
        self.offlineCache = OfflineCache()
    }
}
