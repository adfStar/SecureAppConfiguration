package de.starfinanz.pkl.vault

/**
 * Replacement shown wherever a secret value would otherwise be rendered.
 */
public const val REDACTED: String = "«redacted»"

/**
 * Returns a log-safe placeholder for [secret]. The value itself is never
 * included; only whether it was empty is distinguishable, which avoids
 * confusing an empty secret with a redacted non-empty one during debugging.
 */
public fun redactSecret(secret: String?): String =
    if (secret.isNullOrEmpty()) "«empty»" else REDACTED

/**
 * Wraps a secret so it cannot be printed or logged by accident: [toString]
 * redacts, and the raw value is only reachable through [reveal].
 *
 * `equals`/`hashCode` are intentionally identity-based so secrets are never
 * compared by value in logs or collections that might serialize them.
 */
public class Secret(private val value: String) {
    /** Returns the underlying secret value. Call only at the point of use. */
    public fun reveal(): String = value

    /** Length of the secret; safe to expose for validation without leaking it. */
    public val length: Int get() = value.length

    override fun toString(): String = REDACTED
}
