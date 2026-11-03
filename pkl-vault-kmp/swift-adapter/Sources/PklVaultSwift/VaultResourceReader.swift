// VaultResourceReader.swift
//
// Swift adapter that plugs the `vault:` scheme into pkl-swift's evaluator,
// forwarding reads to the shared Kotlin/Native core (`PklVaultBridge`) exposed
// by the `PklVault` XCFramework built from pkl-vault-kmp.
//
import Foundation
import PklSwift
import PklVault

/// Reads `vault:` resources during Pkl evaluation on Apple platforms.
///
/// The URI is opaque: it addresses a single secret, so `hasHierarchicalUris`
/// and `isGlobbable` are both `false`. `read` is `async`, matching the shared
/// core's suspend `resolve`; unversioned URIs throw (versioning is mandatory).
public final class VaultResourceReader: ResourceReader, @unchecked Sendable {
    public let scheme = "vault"
    public let isGlobbable = false
    public let hasHierarchicalUris = false

    private let bridge: PklVaultBridge

    /// Creates a reader backed by the process environment (`VAULT_ADDR` + token).
    /// Fails closed if the environment is incomplete.
    public init(target: VaultTarget = .server) throws {
        bridge = try PklVaultBridge.companion.fromEnvironment(target: target)
    }

    /// Non-hierarchical reader: nothing to enumerate.
    public func listElements(uri: URL) async throws -> [PathElement] { [] }

    public func read(url: URL) async throws -> [UInt8]? {
        do {
            let bytes = try await bridge.resolve(uri: url.absoluteString)
            return bytes.toUInt8()
        } catch {
            fputs("error: Vault read failed for \(url.absoluteString): \(error.localizedDescription)\n", stderr)
            throw error
        }
    }

    /// Releases the owned HTTP client once codegen is done.
    public func close() { bridge.close() }
}

private extension KotlinByteArray {
    /// Copies a Kotlin `ByteArray` into a Swift `[UInt8]`.
    func toUInt8() -> [UInt8] {
        var out = [UInt8]()
        out.reserveCapacity(Int(size))
        var i: Int32 = 0
        while i < size {
            out.append(UInt8(bitPattern: get(index: i)))
            i += 1
        }
        return out
    }
}
