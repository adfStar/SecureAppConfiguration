package de.starfinanz.pkl.vault

import java.io.File

internal actual fun readEnvVar(name: String): String? = System.getenv(name)

internal actual fun readTokenHelperFile(): String? {
    val home = System.getProperty("user.home") ?: return null
    val file = File(home, ".vault-token")
    if (!file.isFile) return null
    return file.readText().trim().ifEmpty { null }
}
