// swift-tools-version:5.9
//
// SwiftPM package that vends the `vault:` resource reader and reusable
// configuration renderer for Pkl on Apple platforms.
//
// Prerequisite: build the XCFramework first —
//   ./gradlew :pkl-vault-kmp:assemblePklVaultReleaseXCFramework
// which writes ../build/XCFrameworks/release/PklVault.xcframework (referenced
// by the binary target below).
//
import PackageDescription

let package = Package(
    name: "PklVaultSwift",
    platforms: [
        .iOS(.v15),
        .macOS(.v13),
    ],
    products: [
        .library(name: "PklVaultSwift", targets: ["PklVaultSwift"]),
        .library(name: "PickelConfigRenderer", targets: ["PickelConfigRenderer"]),
        .executable(name: "pickel-config-renderer", targets: ["PickelConfigRendererCLI"]),
    ],
    dependencies: [
        .package(url: "https://github.com/apple/pkl-swift", from: "0.10.0"),
    ],
    targets: [
        .binaryTarget(
            name: "PklVault",
            path: "../build/XCFrameworks/release/PklVault.xcframework"
        ),
        .target(
            name: "PklVaultSwift",
            dependencies: [
                "PklVault",
                .product(name: "PklSwift", package: "pkl-swift"),
            ],
            path: "Sources/PklVaultSwift"
        ),
        .target(
            name: "PickelConfigRenderer",
            dependencies: [
                "PklVaultSwift",
                .product(name: "PklSwift", package: "pkl-swift"),
            ],
            path: "Sources/PickelConfigRenderer"
        ),
        .executableTarget(
            name: "PickelConfigRendererCLI",
            dependencies: ["PickelConfigRenderer"],
            path: "Sources/PickelConfigRendererCLI"
        ),
        .testTarget(
            name: "PickelConfigRendererTests",
            dependencies: ["PickelConfigRenderer"],
            path: "Tests/PickelConfigRendererTests"
        ),
    ]
)
