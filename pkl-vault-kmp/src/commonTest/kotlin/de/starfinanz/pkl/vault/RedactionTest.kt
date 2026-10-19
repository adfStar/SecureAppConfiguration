package de.starfinanz.pkl.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RedactionTest {

    @Test
    fun redacts_non_empty_secret() {
        assertEquals(REDACTED, redactSecret("hunter2"))
    }

    @Test
    fun marks_null_or_empty_as_empty() {
        assertEquals("«empty»", redactSecret(null))
        assertEquals("«empty»", redactSecret(""))
    }

    @Test
    fun secret_toString_never_reveals_value() {
        val secret = Secret("hunter2")
        assertEquals(REDACTED, secret.toString())
        assertTrue(!secret.toString().contains("hunter2"))
    }

    @Test
    fun secret_reveals_value_and_length_on_demand() {
        val secret = Secret("hunter2")
        assertEquals("hunter2", secret.reveal())
        assertEquals(7, secret.length)
    }

    @Test
    fun vault_env_toString_redacts_token() {
        val env = VaultEnv("https://vault.example.com", "s3cret-token", "ns")
        val rendered = env.toString()
        assertTrue(!rendered.contains("s3cret-token"), rendered)
        assertTrue(rendered.contains(REDACTED), rendered)
        assertTrue(rendered.contains("https://vault.example.com"), rendered)
    }

    @Test
    fun vault_env_rejects_blank_fields() {
        assertFailsWith<IllegalArgumentException> { VaultEnv("", "token") }
        assertFailsWith<IllegalArgumentException> { VaultEnv("https://v", "   ") }
    }
}
