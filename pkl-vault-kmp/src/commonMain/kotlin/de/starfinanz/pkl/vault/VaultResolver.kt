package de.starfinanz.pkl.vault

import io.ktor.client.HttpClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Where resolved config is destined to run. A per-project guard can use this to
 * keep high-value secrets out of client builds; full enforcement arrives with
 * the `clientSafe` schema marker in the Pkl package (M3). Threaded here so the
 * policy hook exists from the start.
 */
public enum class VaultTarget { APP, SERVER }

/**
 * Resolves `vault:` URIs to their secret bytes for a single build/run.
 *
 * Responsibilities:
 * - parse + validate the URI ([VaultUri]),
 * - fetch via [VaultClient] (fail-closed: constructed only from a valid env),
 * - cache results and de-duplicate concurrent identical requests,
 * - never log secret values (redaction is structural: values are not logged).
 */
public class VaultResolver(
    private val client: VaultClient,
    private val target: VaultTarget = VaultTarget.SERVER,
) {
    private val cacheLock = Mutex()
    private val cache = mutableMapOf<String, CompletableDeferred<ByteArray>>()

    /**
     * Resolve [rawUri] to bytes: a single field's text when the URI has a
     * fragment, otherwise the whole secret object as JSON text.
     *
     * Successful results are cached for the lifetime of this resolver;
     * failures are not cached, so a transient error can be retried.
     */
    public suspend fun resolve(rawUri: String): ByteArray {
        val uri = VaultUri.parse(rawUri)
        if (uri.version == null) {
            // Enforced at evaluation time: no unversioned reads reach Vault.
            throw VaultVersionRequiredException(rawUri)
        }
        val (deferred, isOwner) = cacheLock.withLock {
            val existing = cache[rawUri]
            if (existing != null) {
                existing to false
            } else {
                val fresh = CompletableDeferred<ByteArray>()
                cache[rawUri] = fresh
                fresh to true
            }
        }

        if (!isOwner) return deferred.await()

        return try {
            val bytes = fetch(uri)
            deferred.complete(bytes)
            bytes
        } catch (e: Throwable) {
            cacheLock.withLock { cache.remove(rawUri) }
            deferred.completeExceptionally(e)
            throw e
        }
    }

    private suspend fun fetch(uri: VaultUri): ByteArray {
        val secret = client.read(uri)
        val text = if (uri.field != null) {
            secret.fieldText(uri.field)
                ?: throw VaultFieldNotFoundException(
                    "Secret ${uri.mount}/${uri.path} has no field '${uri.field}'",
                )
        } else {
            secret.asJsonText()
        }
        return text.encodeToByteArray()
    }

    public companion object {
        /**
         * Build a resolver from the process environment, failing closed via
         * [resolveVaultEnv] when `VAULT_ADDR`/`VAULT_TOKEN` are absent.
         */
        public fun fromEnvironment(
            http: HttpClient,
            target: VaultTarget = VaultTarget.SERVER,
        ): VaultResolver = VaultResolver(VaultClient(http, resolveVaultEnv()), target)
    }
}
