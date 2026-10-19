package de.starfinanz.pkl.vault

/**
 * Connection settings for a Vault server, sourced from the environment.
 *
 * Isolated from auth *mechanism* on purpose: today it is a static token, but
 * swapping to AppRole/Kubernetes auth later means changing only how a [token]
 * is obtained, not any calling code or Pkl.
 */
public data class VaultEnv(
    /** Base address, e.g. `https://vault.example.com`. No trailing `/v1`. */
    val address: String,
    /** Vault token sent as the `X-Vault-Token` header. Treated as a secret. */
    val token: String,
    /** Optional Vault Enterprise namespace (`X-Vault-Namespace` header). */
    val namespace: String? = null,
) {
    init {
        require(address.isNotBlank()) { "VAULT_ADDR must not be blank" }
        require(token.isNotBlank()) { "VAULT_TOKEN must not be blank" }
    }

    /** Redacts the token so a [VaultEnv] can never leak it via logging. */
    override fun toString(): String =
        "VaultEnv(address=$address, token=${redactSecret(token)}, namespace=$namespace)"

    public companion object {
        public const val ADDRESS_VAR: String = "VAULT_ADDR"
        public const val TOKEN_VAR: String = "VAULT_TOKEN"
        public const val NAMESPACE_VAR: String = "VAULT_NAMESPACE"
    }
}

/**
 * Reads a process environment variable, or `null` if unset.
 */
internal expect fun readEnvVar(name: String): String?

/**
 * Reads the Vault CLI token-helper file (`~/.vault-token`), or `null` if it is
 * absent or empty. This is the token cached by `vault login`, letting local
 * builds authenticate without an explicit `VAULT_TOKEN`.
 */
internal expect fun readTokenHelperFile(): String?

/** Default path shown in diagnostics for the token-helper fallback. */
internal const val TOKEN_HELPER_PATH: String = "~/.vault-token"

/**
 * Resolve [VaultEnv] from the environment, failing closed with a clear,
 * secret-free message when configuration is incomplete.
 *
 * Token precedence: `VAULT_TOKEN` (mandatory for CI; env always wins), then the
 * `~/.vault-token` helper file written by `vault login` (local builds), then
 * fail-closed.
 */
public fun resolveVaultEnv(): VaultEnv {
    val address = readEnvVar(VaultEnv.ADDRESS_VAR)
    val envToken = readEnvVar(VaultEnv.TOKEN_VAR)
    val helperToken = readTokenHelperFile()
    val namespace = readEnvVar(VaultEnv.NAMESPACE_VAR)?.takeIf { it.isNotBlank() }
    return buildVaultEnv(address, envToken, helperToken, namespace)
}

/**
 * Pure assembly of [VaultEnv] from already-read sources, so the fail-closed and
 * precedence logic is testable without touching the real environment.
 */
internal fun buildVaultEnv(
    address: String?,
    envToken: String?,
    helperToken: String?,
    namespace: String?,
): VaultEnv {
    val token = envToken?.takeIf { it.isNotBlank() }
        ?: helperToken?.takeIf { it.isNotBlank() }

    if (address.isNullOrBlank() || token == null) {
        val problems = buildList {
            if (address.isNullOrBlank()) add("missing ${VaultEnv.ADDRESS_VAR}")
            if (token == null) {
                add(
                    "no token: set ${VaultEnv.TOKEN_VAR} or run `vault login` " +
                        "to populate $TOKEN_HELPER_PATH",
                )
            }
        }
        throw VaultConfigException(
            "Vault environment is not configured: ${problems.joinToString("; ")}",
        )
    }
    return VaultEnv(address = address, token = token, namespace = namespace)
}
