package de.starfinanz.pkl.vault

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun readEnvVar(name: String): String? = getenv(name)?.toKString()

@OptIn(ExperimentalForeignApi::class)
internal actual fun readTokenHelperFile(): String? {
    val home = getenv("HOME")?.toKString() ?: return null
    val path = "$home/.vault-token"
    val file = fopen(path, "r") ?: return null
    try {
        val builder = StringBuilder()
        memScoped {
            val bufferSize = 4096
            val buffer = allocArray<ByteVar>(bufferSize)
            while (true) {
                val chunk = fgets(buffer, bufferSize, file) ?: break
                builder.append(chunk.toKString())
            }
        }
        return builder.toString().trim().ifEmpty { null }
    } finally {
        fclose(file)
    }
}
