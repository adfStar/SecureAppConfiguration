package de.starfinanz.pkl.vault

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val BODY =
    """{"data":{"data":{"password":"s3cret","port":5432},"metadata":{"version":1}}}"""

private val env = VaultEnv("https://vault.example.com", "test-token")

private fun resolver(requests: MutableList<String>? = null): VaultResolver {
    val http = HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                requests?.add(request.url.encodedPath)
                respond(BODY, HttpStatusCode.OK)
            }
        }
    }
    return VaultResolver(VaultClient(http, env))
}

class VaultResolverTest {

    @Test
    fun resolves_field_to_text_bytes() = runTest {
        val bytes = resolver().resolve("vault:secret/data/myapp/db#password")
        assertEquals("s3cret", bytes.decodeToString())
    }

    @Test
    fun resolves_whole_secret_without_fragment() = runTest {
        val bytes = resolver().resolve("vault:secret/data/myapp/db")
        val text = bytes.decodeToString()
        assertTrue(text.contains("\"password\":\"s3cret\""), text)
        assertTrue(text.contains("\"port\":5432"), text)
    }

    @Test
    fun missing_field_fails() = runTest {
        assertFailsWith<VaultFieldNotFoundException> {
            resolver().resolve("vault:secret/data/myapp/db#absent")
        }
    }

    @Test
    fun caches_repeated_identical_requests() = runTest {
        val requests = mutableListOf<String>()
        val r = resolver(requests)
        r.resolve("vault:secret/data/myapp/db#password")
        r.resolve("vault:secret/data/myapp/db#password")
        assertEquals(1, requests.size)
    }

    @Test
    fun deduplicates_concurrent_identical_requests() = runTest {
        val requests = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    requests.add(request.url.encodedPath)
                    gate.await() // hold the first request open so the second joins in-flight
                    respond(BODY, HttpStatusCode.OK)
                }
            }
        }
        val r = VaultResolver(VaultClient(http, env))

        val a = async { r.resolve("vault:secret/data/myapp/db#password") }
        val b = async { r.resolve("vault:secret/data/myapp/db#password") }
        gate.complete(Unit)

        assertEquals("s3cret", a.await().decodeToString())
        assertEquals("s3cret", b.await().decodeToString())
        assertEquals(1, requests.size)
    }

    @Test
    fun failure_is_not_cached() = runTest {
        var attempts = 0
        val http = HttpClient(MockEngine) {
            engine {
                addHandler {
                    attempts++
                    if (attempts == 1) respond("", HttpStatusCode.InternalServerError)
                    else respond(BODY, HttpStatusCode.OK)
                }
            }
        }
        val r = VaultResolver(VaultClient(http, env))
        assertFailsWith<VaultResponseException> { r.resolve("vault:secret/data/app#password") }
        assertEquals("s3cret", r.resolve("vault:secret/data/app#password").decodeToString())
        assertEquals(2, attempts)
    }
}
