package de.starfinanz.pkl.vault

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.util.network.UnresolvedAddressException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * The `data.data` payload of a Vault KV v2 secret: the author-visible fields.
 */
public class VaultSecret internal constructor(
    public val data: JsonObject,
) {
    /**
     * Returns [field] rendered as text, or `null` if the field is absent.
     * String values yield their raw content; non-string JSON values yield their
     * canonical JSON text so numbers/booleans remain usable from Pkl.
     */
    public fun fieldText(field: String): String? {
        val element = data[field] ?: return null
        return when (element) {
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }

    /** The whole secret object as compact JSON text. */
    public fun asJsonText(): String = data.toString()
}

/**
 * Minimal Vault KV v2 reader over Ktor. Auth is a static token header today;
 * because that is the only auth-aware surface, switching to AppRole/Kubernetes
 * later is a change confined to how [VaultEnv.token] is produced.
 *
 * The [http] client is injected so tests can drive it with Ktor's `MockEngine`
 * and production can supply a platform engine (CIO on JVM, Darwin on Apple).
 */
public class VaultClient(
    private val http: HttpClient,
    private val env: VaultEnv,
    private val json: Json = DefaultJson,
) {
    private val baseUrl: String = env.address.trimEnd('/')

    /**
     * Fetch the secret addressed by [uri]. Throws a typed [VaultException] for
     * auth failure, missing path, unexpected response, or network error.
     */
    public suspend fun read(uri: VaultUri): VaultSecret {
        val versionQuery = uri.version?.let { "?version=$it" } ?: ""
        val url = "$baseUrl/v1/${uri.apiPath()}$versionQuery"
        val response = try {
            http.get(url) {
                header("X-Vault-Token", env.token)
                env.namespace?.let { header("X-Vault-Namespace", it) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: UnresolvedAddressException) {
            throw VaultNetworkException("Could not reach Vault at $baseUrl", e)
        } catch (e: Exception) {
            throw VaultNetworkException("Request to Vault failed for ${uri.raw}", e)
        }

        return parse(response, uri)
    }

    private suspend fun parse(response: HttpResponse, uri: VaultUri): VaultSecret {
        when {
            response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden ->
                throw VaultAuthException(
                    "Vault denied access to ${uri.raw} (HTTP ${response.status.value}); " +
                        "check VAULT_TOKEN and policy",
                )

            response.status == HttpStatusCode.NotFound ->
                throw VaultNotFoundException("Vault has no secret at ${uri.raw} (HTTP 404)")

            !response.status.isSuccess() ->
                throw VaultResponseException(
                    "Unexpected HTTP ${response.status.value} from Vault for ${uri.raw}",
                )
        }

        val body = response.bodyAsText()
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw VaultResponseException("Vault returned a non-JSON body for ${uri.raw}")
        }

        // KV v2 nests the fields under data.data; data.metadata sits alongside.
        val inner = root["data"]?.let { it as? JsonObject }?.get("data") as? JsonObject
            ?: throw VaultResponseException(
                "Vault response for ${uri.raw} is not in KV v2 shape (missing data.data)",
            )
        return VaultSecret(inner)
    }

    public companion object {
        internal val DefaultJson: Json = Json { ignoreUnknownKeys = true }
    }
}
