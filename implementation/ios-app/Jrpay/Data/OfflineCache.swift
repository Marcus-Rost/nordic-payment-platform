// UC-G28 — offline read-only cache. Stores the last-fetched JSON payload
// of "safe" customer surfaces (account list, recent transactions, etc.) on
// disk under the app's library directory. Mutating screens never cache.

import Foundation

public final class OfflineCache {

    private let dir: URL

    public init() {
        let library = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask).first!
        let cacheDir = library.appendingPathComponent("Caches/jrpay-offline", isDirectory: true)
        try? FileManager.default.createDirectory(at: cacheDir, withIntermediateDirectories: true)
        self.dir = cacheDir
    }

    public func put(key: String, data: Data) throws {
        try data.write(to: file(for: key), options: .atomic)
    }

    public func get(key: String) -> Data? {
        try? Data(contentsOf: file(for: key))
    }

    public func clear(key: String) {
        try? FileManager.default.removeItem(at: file(for: key))
    }

    public func clearAll() {
        try? FileManager.default.removeItem(at: dir)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
    }

    private func file(for key: String) -> URL {
        let safe = key
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: ":", with: "_")
        return dir.appendingPathComponent("\(safe).json")
    }
}
