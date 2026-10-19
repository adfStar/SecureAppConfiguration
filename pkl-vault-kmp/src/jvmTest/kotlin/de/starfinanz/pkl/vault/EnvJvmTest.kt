package de.starfinanz.pkl.vault

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Exercises the JVM `readTokenHelperFile` actual against a redirected
 * `user.home`, covering the `~/.vault-token` local-build fallback that the pure
 * precedence logic can't reach.
 */
class EnvJvmTest {

    private val originalHome: String? = System.getProperty("user.home")

    @AfterTest
    fun restoreHome() {
        if (originalHome != null) System.setProperty("user.home", originalHome)
    }

    @Test
    fun readsTrimmedTokenFromHelperFile() {
        val home = Files.createTempDirectory("vault-home").toFile()
        try {
            System.setProperty("user.home", home.absolutePath)
            File(home, ".vault-token").writeText("  s.tok3n\n")
            assertEquals("s.tok3n", readTokenHelperFile())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun returnsNullWhenHelperFileAbsent() {
        val home = Files.createTempDirectory("vault-home-empty").toFile()
        try {
            System.setProperty("user.home", home.absolutePath)
            assertNull(readTokenHelperFile())
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun returnsNullWhenHelperFileEmpty() {
        val home = Files.createTempDirectory("vault-home-blank").toFile()
        try {
            System.setProperty("user.home", home.absolutePath)
            File(home, ".vault-token").writeText("   \n")
            assertNull(readTokenHelperFile())
        } finally {
            home.deleteRecursively()
        }
    }
}
