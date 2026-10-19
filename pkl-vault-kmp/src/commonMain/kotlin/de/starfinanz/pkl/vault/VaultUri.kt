package de.starfinanz.pkl.vault

import io.ktor.http.encodeURLPathPart

/**
 * Thrown when a `vault:` URI is syntactically invalid and cannot be parsed.
 *
 * The [raw] value is safe to log: it is the URI *locator*, never a secret value.
 */
public class VaultUriException(
    public val raw: String,
    message: String,
) : VaultException("Invalid vault URI '$raw': $message")

/**
 * A parsed, validated `vault:` resource URI.
 *
 * Shape: `vault:<mount>/data/<path>#<field>`
 *
 * The literal `data` segment is required and reflects the Vault KV v2 API path
 * (`<mount>/data/<path>`); keeping it explicit makes the URI a transparent view
 * of the real request. The fragment is optional: when present it selects a
 * single field from the secret, otherwise the whole secret object is returned.
 *
 * Examples:
 * - `vault:secret/data/myapp/db#password` → mount=`secret`, path=`myapp/db`, field=`password`
 * - `vault:secret/data/myapp/db`         → mount=`secret`, path=`myapp/db`, field=`null`
 */
public data class VaultUri(
    val mount: String,
    val path: String,
    val field: String?,
    val raw: String,
) {
    /**
     * The Vault KV v2 read path, relative to `/v1/`, e.g. `secret/data/myapp/db`.
     * Each segment is percent-encoded so that reserved characters in paths do
     * not alter the request.
     */
    public fun apiPath(): String {
        val encodedPath = path.split('/').joinToString("/") { it.encodeURLPathPart() }
        return "${mount.encodeURLPathPart()}/data/$encodedPath"
    }

    public companion object {
        public const val SCHEME: String = "vault"
        private const val DATA_SEGMENT = "data"

        /**
         * Parse and validate [raw]. Throws [VaultUriException] for any malformed
         * input: wrong scheme, missing `data` segment, empty mount/path, or an
         * empty fragment.
         */
        public fun parse(raw: String): VaultUri {
            val prefix = "$SCHEME:"
            if (!raw.startsWith(prefix)) {
                throw VaultUriException(raw, "URI must start with '$prefix'")
            }
            val body = raw.substring(prefix.length)
            if (body.isEmpty()) {
                throw VaultUriException(raw, "URI has no locator after '$prefix'")
            }

            val hashIndex = body.indexOf('#')
            val locator: String
            val field: String?
            if (hashIndex >= 0) {
                locator = body.substring(0, hashIndex)
                val fragment = body.substring(hashIndex + 1)
                if (fragment.isEmpty()) {
                    throw VaultUriException(raw, "fragment '#' is present but empty")
                }
                if (fragment.contains('#')) {
                    throw VaultUriException(raw, "fragment must not contain '#'")
                }
                field = fragment
            } else {
                locator = body
                field = null
            }

            val segments = locator.split('/')
            // Need at least: <mount> / data / <one path segment>.
            if (segments.size < 3) {
                throw VaultUriException(
                    raw,
                    "expected '<mount>/$DATA_SEGMENT/<path>' but got too few segments",
                )
            }
            val mount = segments[0]
            if (mount.isEmpty()) {
                throw VaultUriException(raw, "mount (first segment) must not be empty")
            }
            if (segments[1] != DATA_SEGMENT) {
                throw VaultUriException(
                    raw,
                    "second segment must be '$DATA_SEGMENT' (KV v2); got '${segments[1]}'",
                )
            }
            val pathSegments = segments.drop(2)
            if (pathSegments.any { it.isEmpty() }) {
                throw VaultUriException(raw, "path segments must not be empty")
            }
            val path = pathSegments.joinToString("/")

            return VaultUri(mount = mount, path = path, field = field, raw = raw)
        }
    }
}
