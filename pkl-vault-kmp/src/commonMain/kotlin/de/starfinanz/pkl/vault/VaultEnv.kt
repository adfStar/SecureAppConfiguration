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
 * Reads [VaultEnv] from process environment variables.
 *
 * Fail-closed: returns a typed [VaultConfigException] (via [resolveVaultEnv])
 * rather than silently degrading when required variables are absent.
 */
internal expect fun readEnvVar(name: String): String?

/**
 * Resolve [VaultEnv] from the environment, failing closed with a clear,
 * secret-free message when `VAULT_ADDR` or `VAULT_TOKEN` is missing.
 */
public fun resolveVaultEnv(): VaultEnv {
    val address = readEnvVar(VaultEnv.ADDRESS_VAR)
    val token = readEnvVar(VaultEnv.TOKEN_VAR)
    val namespace = readEnvVar(VaultEnv.NAMESPACE_VAR)?.takeIf { it.isNotBlank() }

    if (address.isNullOrBlank() || token.isNullOrBlank()) {
        val missing = buildList {
            if (address.isNullOrBlank()) add(VaultEnv.ADDRESS_VAR)
            if (token.isNullOrBlank()) add(VaultEnv.TOKEN_VAR)
        }
        throw VaultConfigException(
            "Vault environment is not configured: missing ${missing.joinToString(", ")}",
        )
    }
    return VaultEnv(address = address, token = token, namespace = namespace)
}
