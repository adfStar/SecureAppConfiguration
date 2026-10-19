package de.starfinanz.pkl.vault

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private val KV_V2_BODY = """
    {"data":{"data":{"password":"s3cret","port":5432},"metadata":{"version":1}}}
""".trimIndent()

private fun client(handler: MockRequestHandler): HttpClient =
    HttpClient(MockEngine) { engine { addHandler(handler) } }

private val env = VaultEnv("https://vault.example.com", "test-token", "team-ns")

class VaultClientTest {

    @Test
    fun reads_fields_and_targets_kv_v2_path() = runTest {
        var seenPath: String? = null
        var seenToken: String? = null
        var seenNamespace: String? = null
        val http = client { request ->
            seenPath = request.url.encodedPath
            seenToken = request.headers["X-Vault-Token"]
            seenNamespace = request.headers["X-Vault-Namespace"]
            respond(KV_V2_BODY, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }

        val secret = VaultClient(http, env).read(VaultUri.parse("vault:secret/data/myapp/db#password"))

        assertEquals("/v1/secret/data/myapp/db", seenPath)
        assertEquals("test-token", seenToken)
        assertEquals("team-ns", seenNamespace)
        assertEquals("s3cret", secret.fieldText("password"))
        assertEquals("5432", secret.fieldText("port"))
        assertNull(secret.fieldText("absent"))
    }

    @Test
    fun omits_namespace_header_when_absent() = runTest {
        var seenNamespace: String? = "unset"
        val http = client { request ->
            seenNamespace = request.headers["X-Vault-Namespace"]
            respond(KV_V2_BODY, HttpStatusCode.OK)
        }
        val noNs = VaultEnv("https://vault.example.com", "test-token")
        VaultClient(http, noNs).read(VaultUri.parse("vault:secret/data/app#password"))
        assertNull(seenNamespace)
    }

    @Test
    fun appends_version_query_when_pinned() = runTest {
        var seenQuery: String? = null
        val http = client { request ->
            seenQuery = request.url.encodedQuery
            respond(KV_V2_BODY, HttpStatusCode.OK)
        }
        VaultClient(http, env).read(VaultUri.parse("vault:secret/data/myapp/db?version=3#password"))
        assertEquals("version=3", seenQuery)
    }

    @Test
    fun omits_version_query_when_unpinned() = runTest {
        var seenQuery: String? = "unset"
        val http = client { request ->
            seenQuery = request.url.encodedQuery
            respond(KV_V2_BODY, HttpStatusCode.OK)
        }
        VaultClient(http, env).read(VaultUri.parse("vault:secret/data/myapp/db#password"))
        assertEquals("", seenQuery)
    }

    @Test
    fun forbidden_maps_to_auth_exception() = runTest {
        val http = client { respond("denied", HttpStatusCode.Forbidden) }
        assertFailsWith<VaultAuthException> {
            VaultClient(http, env).read(VaultUri.parse("vault:secret/data/app#x"))
        }
    }

    @Test
    fun unauthorized_maps_to_auth_exception() = runTest {
        val http = client { respond("", HttpStatusCode.Unauthorized) }
        assertFailsWith<VaultAuthException> {
            VaultClient(http, env).read(VaultUri.parse("vault:secret/data/app#x"))
        }
    }

    @Test
    fun not_found_maps_to_not_found_exception() = runTest {
        val http = client { respond("", HttpStatusCode.NotFound) }
        assertFailsWith<VaultNotFoundException> {
            VaultClient(http, env).read(VaultUri.parse("vault:secret/data/app#x"))
        }
    }

    @Test
    fun server_error_maps_to_response_exception() = runTest {
        val http = client { respond("boom", HttpStatusCode.InternalServerError) }
        assertFailsWith<VaultResponseException> {
            VaultClient(http, env).read(VaultUri.parse("vault:secret/data/app#x"))
        }
    }

    @Test
    fun non_json_body_maps_to_response_exception() = runTest {
        val http = client { respond("<html>nope</html>", HttpStatusCode.OK) }
        assertFailsWith<VaultResponseException> {
            VaultClient(http, env).read(VaultUri.parse("vault:secret/data/app#x"))
        }
    }

    @Test
    fun wrong_shape_maps_to_response_exception() = runTest {
        val http = client { respond("""{"data":{"metadata":{}}}""", HttpStatusCode.OK) }
        assertFailsWith<VaultResponseException> {
            VaultClient(http, env).read(VaultUri.parse("vault:secret/data/app#x"))
        }
    }
}
