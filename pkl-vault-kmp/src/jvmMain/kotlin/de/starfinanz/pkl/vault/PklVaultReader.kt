package de.starfinanz.pkl.vault

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import java.net.URI
import java.util.Optional
import org.pkl.core.resource.Resource
import org.pkl.core.resource.ResourceReader

/**
 * Pkl [ResourceReader] for the `vault:` scheme (JVM: Android builds, CI, server).
 *
 * Register it on a pkl `EvaluatorBuilder`/`ConfigEvaluatorBuilder` and add a
 * matching allowed-resources pattern; thereafter `read("vault:…")` in any Pkl
 * module resolves through the shared [VaultResolver].
 *
 * The URI is opaque (non-hierarchical, non-globbable): it addresses one secret,
 * never a tree. Pkl's `read` is synchronous, so the suspend resolver is bridged
 * with [runBlocking] — acceptable because evaluation is a build step, not a
 * latency-sensitive runtime path.
 *
 * Secret bytes are returned verbatim; nothing here logs a value (redaction is
 * structural in the core). Use [fromEnvironment] for the common case, or inject
 * a [VaultResolver] directly (e.g. tests) via the primary constructor.
 */
public class PklVaultReader(
    private val resolver: VaultResolver,
    private val onClose: () -> Unit = {},
) : ResourceReader {

    override fun getUriScheme(): String = VaultUri.SCHEME

    /** Vault URIs address a single secret; there is no path hierarchy. */
    override fun hasHierarchicalUris(): Boolean = false

    /** A `vault:` URI never expands to multiple elements. */
    override fun isGlobbable(): Boolean = false

    override fun read(uri: URI): Optional<Any> {
        val bytes = runBlocking { resolver.resolve(uri.toString()) }
        return Optional.of(Resource(uri, bytes))
    }

    /** Closes the owned HTTP client (if any) when the evaluator is closed. */
    override fun close(): Unit = onClose()

    public companion object {
        /**
         * Reader backed by the process environment (`VAULT_ADDR` + token), with
         * a CIO HTTP client this reader owns and closes. Fails closed via
         * [resolveVaultEnv] when configuration is incomplete.
         */
        public fun fromEnvironment(target: VaultTarget = VaultTarget.SERVER): PklVaultReader {
            val http = HttpClient(CIO)
            val resolver = VaultResolver.fromEnvironment(http, target)
            return PklVaultReader(resolver, onClose = { http.close() })
        }
    }
}
