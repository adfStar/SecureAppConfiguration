package de.starfinanz.pkl.vault

internal actual fun readEnvVar(name: String): String? = System.getenv(name)
