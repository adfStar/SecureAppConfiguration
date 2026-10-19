package de.starfinanz.pkl.vault

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.util.regex.Pattern
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.pkl.core.EvaluatorBuilder
import org.pkl.core.ModuleSource

/**
 * Drives a real in-process pkl [org.pkl.core.Evaluator] with [PklVaultReader]
 * registered, backed by a Ktor MockEngine so no live Vault is needed. This is
 * the actual JVM binding path Android/CI use, exercised end to end.
 */
class PklVaultReaderTest {

    private fun reader(onRequest: (String) -> Unit = {}): PklVaultReader {
        val engine = MockEngine { request ->
            onRequest(request.url.toString())
            respond(
                content = """{"data":{"data":{"password":"s3cr3t","host":"db.internal"},"metadata":{"version":3}}}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val http = HttpClient(engine)
        val client = VaultClient(http, VaultEnv("https://vault.example.com", "test-token"))
        return PklVaultReader(VaultResolver(client), onClose = { http.close() })
    }

    private fun evaluatorWith(reader: PklVaultReader) =
        EvaluatorBuilder.preconfigured()
            .let { it.setAllowedResources(it.allowedResources + Pattern.compile("vault:.*")) }
            .addResourceReader(reader)
            .build()

    @Test
    fun resolvesVaultFieldDuringEvaluation() {
        var requestedUrl: String? = null
        reader { requestedUrl = it }.use { r ->
            evaluatorWith(r).use { evaluator ->
                val value = evaluator.evaluateExpressionString(
                    ModuleSource.text(
                        """password = read("vault:secret/data/myapp/db?version=3#password").text""",
                    ),
                    "password",
                )
                assertEquals("s3cr3t", value)
            }
        }
        assertTrue(
            requestedUrl?.contains("/v1/secret/data/myapp/db") == true &&
                requestedUrl.contains("version=3"),
            "reader must call KV v2 path with the pinned version, was: $requestedUrl",
        )
    }

    @Test
    fun rejectsUnversionedReadDuringEvaluation() {
        reader().use { r ->
            evaluatorWith(r).use { evaluator ->
                val error = runCatching {
                    evaluator.evaluateExpressionString(
                        ModuleSource.text(
                            """password = read("vault:secret/data/myapp/db#password").text""",
                        ),
                        "password",
                    )
                }.exceptionOrNull()
                assertTrue(error != null, "unversioned read must fail evaluation")
                assertTrue(
                    error.message?.contains("version") == true,
                    "failure should name the missing version, was: ${error.message}",
                )
            }
        }
    }
}
