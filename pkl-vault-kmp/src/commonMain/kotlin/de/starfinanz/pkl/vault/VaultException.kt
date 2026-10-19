package de.starfinanz.pkl.vault

/**
 * Base type for all failures raised by the pkl-vault library.
 *
 * Messages are constructed to be safe to log: they never embed a secret value.
 */
public open class VaultException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/** Required Vault environment (address/token) is missing or blank. Fail-closed. */
public class VaultConfigException(message: String) : VaultException(message)

/** Authentication/authorization failure (HTTP 401/403). */
public class VaultAuthException(message: String) : VaultException(message)

/** The requested secret path does not exist (HTTP 404). */
public class VaultNotFoundException(message: String) : VaultException(message)

/** The secret exists but the requested field is absent from its data. */
public class VaultFieldNotFoundException(message: String) : VaultException(message)

/**
 * A `vault:` URI did not pin a KV v2 version (`?version=<n>`). Unversioned reads
 * are disallowed so that rebuilding an old app revision resolves the exact
 * secret revision it shipped with, rather than whatever is latest today.
 */
public class VaultVersionRequiredException(raw: String) : VaultException(
    "Vault URI '$raw' must pin a version with '?version=<n>'; " +
        "unversioned reads are disallowed to keep builds reproducible",
)

/** Transport-level failure (DNS, connection, TLS, timeout). */
public class VaultNetworkException(message: String, cause: Throwable) :
    VaultException(message, cause)

/** Unexpected HTTP status or response body shape from Vault. */
public class VaultResponseException(message: String) : VaultException(message)
