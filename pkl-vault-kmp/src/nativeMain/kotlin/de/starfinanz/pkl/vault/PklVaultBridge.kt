package de.starfinanz.pkl.vault

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/**
 * Entry point into the shared Vault core for Apple targets.
 *
 * pkl-swift's `ResourceReader.read` is `async`, and Kotlin/Native exports a
 * `suspend` function to Swift as `async`, so [resolve] maps directly onto it —
 * no thread blocking. The Swift adapter (`swift-adapter/VaultResourceReader.swift`)
 * forwards `read(url:)` here.
 *
 * One bridge owns one Ktor Darwin client; call [close] when codegen finishes.
 */
public class PklVaultBridge private constructor(
    private val resolver: VaultResolver,
    private val http: HttpClient,
) {
    /** The URI scheme this bridge serves (`vault`). */
    public val uriScheme: String get() = VaultUri.SCHEME

    /**
     * Resolve [uri] to its secret bytes. Throws a typed [VaultException] (e.g.
     * [VaultVersionRequiredException] for unversioned URIs) — never logs a
     * secret value.
     */
    public suspend fun resolve(uri: String): ByteArray = resolver.resolve(uri)

    /** Releases the owned HTTP client. */
    public fun close() {
        http.close()
    }

    public companion object {
        /**
         * Bridge backed by the process environment (`VAULT_ADDR` + token),
         * with a Darwin HTTP client it owns. Fails closed via [resolveVaultEnv]
         * when configuration is incomplete.
         */
        public fun fromEnvironment(target: VaultTarget = VaultTarget.SERVER): PklVaultBridge {
            val http = HttpClient(Darwin)
            return PklVaultBridge(VaultResolver.fromEnvironment(http, target), http)
        }
    }
}
