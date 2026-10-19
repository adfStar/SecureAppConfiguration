package de.starfinanz.pkl.vault

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal actual fun readEnvVar(name: String): String? = getenv(name)?.toKString()
