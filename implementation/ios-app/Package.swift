// swift-tools-version:5.9
//
// JRPay iOS — Swift Package describing the source layout.
//
// Two targets:
//
//   * `JrpayCore`   — Foundation + Crypto only. Builds on Linux and Apple
//                     platforms. Holds the dynamic-linking hash, the one
//                     piece that MUST stay byte-identical with the Java/web/
//                     Android clients. Linux CI can run its parity tests.
//
//   * `Jrpay`       — full iOS app (SwiftUI/UIKit/Combine/LocalAuthentication).
//                     Apple-only. Re-exported from `Jrpay/Sca/DlHash.swift`
//                     so view files keep using `DlHash.PaymentInitiation`
//                     without explicit `import JrpayCore`.
//
// Build paths:
//
//   Linux (Ubuntu, CI):   swift build && swift test
//                         (only JrpayCore + JrpayCoreTests compile)
//
//   macOS / Xcode 15+:    open Package.swift, or
//                         xcodebuild -scheme Jrpay -sdk iphonesimulator17.0 build

import PackageDescription

#if os(macOS)
// iOS app target. Holds SwiftUI/UIKit/Combine/LocalAuthentication code that
// can only be built on Apple platforms. The matching XCTest target for the
// app (XCUITest, SwiftUI snapshot tests, etc.) lives under JrpayTests/ and
// will be added once those tests are wired — until then we don't list a
// test target here, since SPM rejects targets that point at empty paths.
let applePlatformTargets: [Target] = [
    .target(
        name: "Jrpay",
        dependencies: ["JrpayCore"],
        path: "Jrpay",
        resources: [
            .process("Resources/nb.lproj"),
            .process("Resources/en.lproj"),
        ]
    ),
]
#else
let applePlatformTargets: [Target] = []
#endif

let package = Package(
    name: "Jrpay",
    platforms: [.iOS(.v17), .macOS(.v13)],
    products: [
        .library(name: "JrpayCore", targets: ["JrpayCore"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/swift-crypto.git", from: "3.0.0"),
    ],
    targets: [
        .target(
            name: "JrpayCore",
            dependencies: [
                .product(name: "Crypto", package: "swift-crypto"),
            ],
            path: "Sources/JrpayCore"
        ),
        .testTarget(
            name: "JrpayCoreTests",
            dependencies: ["JrpayCore"],
            path: "Tests/JrpayCoreTests"
        ),
    ] + applePlatformTargets
)
