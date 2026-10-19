package de.starfinanz.pkl.vault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class VaultEnvTest {

    @Test
    fun env_token_wins_over_helper() {
        val env = buildVaultEnv("https://v", "env-token", "helper-token", null)
        assertEquals("env-token", env.token)
    }

    @Test
    fun falls_back_to_helper_token() {
        val env = buildVaultEnv("https://v", null, "helper-token", null)
        assertEquals("helper-token", env.token)
    }

    @Test
    fun blank_env_token_falls_back_to_helper() {
        val env = buildVaultEnv("https://v", "   ", "helper-token", null)
        assertEquals("helper-token", env.token)
    }

    @Test
    fun fails_closed_when_no_token_available() {
        val ex = assertFailsWith<VaultConfigException> {
            buildVaultEnv("https://v", null, null, null)
        }
        assertTrue(ex.message!!.contains("vault login"), ex.message!!)
    }

    @Test
    fun fails_closed_when_address_missing() {
        assertFailsWith<VaultConfigException> {
            buildVaultEnv(null, "env-token", null, null)
        }
    }

    @Test
    fun carries_namespace_through() {
        val env = buildVaultEnv("https://v", "env-token", null, "team-ns")
        assertEquals("team-ns", env.namespace)
    }
}
