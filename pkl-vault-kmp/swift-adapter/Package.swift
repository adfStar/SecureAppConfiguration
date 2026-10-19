// swift-tools-version:5.9
//
// SwiftPM package that vends the `vault:` resource reader for Pkl on Apple
// platforms. It binds the `PklVault` XCFramework (the shared Kotlin/Native core,
// built by Gradle) and depends on pkl-swift for the `ResourceReader` protocol.
//
// Prerequisite: build the XCFramework first —
//   ./gradlew :pkl-vault-kmp:assemblePklVaultReleaseXCFramework
// which writes ../build/XCFrameworks/release/PklVault.xcframework (referenced
// by the binary target below).
//
// Status: the Kotlin side (XCFramework) builds today; compiling this Swift
// package end to end is part of M4 (iOS pilot), where it is wired into the app's
// SPM/Xcode build and evaluated against a real Pkl codegen step.
import PackageDescription

let package = Package(
    name: "PklVaultSwift",
    platforms: [
        .iOS(.v15),
        .macOS(.v13),
    ],
    products: [
        .library(name: "PklVaultSwift", targets: ["PklVaultSwift"]),
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
    ]
)
