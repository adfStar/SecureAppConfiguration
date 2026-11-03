// swift-tools-version:5.9
import Foundation
import PackageDescription

let useLocalXCFramework =
    ProcessInfo.processInfo.environment["PICKEL_LOCAL_XCFRAMEWORK"] == "1"

let pklVaultTarget: Target = useLocalXCFramework
    ? .binaryTarget(
        name: "PklVault",
        path: "pkl-vault-kmp/build/XCFrameworks/release/PklVault.xcframework"
    )
    : .binaryTarget(
        name: "PklVault",
        url: "https://github.com/adfStar/SecureAppConfiguration/releases/download/0.0.2/PklVault.xcframework.zip",
        checksum: "cd4dc6ea3b15bfcf646ba187b9cca4effeaa4a4b6545153206fe6b30ce3f8754"
    )

let package = Package(
    name: "Pickel",
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
        pklVaultTarget,
        .target(
            name: "PklVaultSwift",
            dependencies: [
                "PklVault",
                .product(name: "PklSwift", package: "pkl-swift"),
            ],
            path: "pkl-vault-kmp/swift-adapter/Sources/PklVaultSwift"
        ),
        .target(
            name: "PickelConfigRenderer",
            dependencies: [
                "PklVaultSwift",
                .product(name: "PklSwift", package: "pkl-swift"),
            ],
            path: "pkl-vault-kmp/swift-adapter/Sources/PickelConfigRenderer"
        ),
        .executableTarget(
            name: "PickelConfigRendererCLI",
            dependencies: ["PickelConfigRenderer"],
            path: "pkl-vault-kmp/swift-adapter/Sources/PickelConfigRendererCLI"
        ),
        .testTarget(
            name: "PickelConfigRendererTests",
            dependencies: ["PickelConfigRenderer"],
            path: "pkl-vault-kmp/swift-adapter/Tests/PickelConfigRendererTests"
        ),
    ]
)
