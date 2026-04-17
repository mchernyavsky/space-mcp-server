package team.jetbrains.mcp.space

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.isSuccess
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal class SpaceHttpFallbacks(
    private val json: Json,
) {
    suspend fun getReviewCommits(
        session: SpaceSession,
        projectKey: String,
        reviewRef: String,
    ): List<ReviewCommitInReview> =
        get<RawReviewDetailsResponse>(
            session = session,
            path = listOf("projects", "key:$projectKey", "code-reviews", normalizeReviewIdentifier(reviewRef), "details"),
        ).normalizedCommits()

    private suspend inline fun <reified T> get(
        session: SpaceSession,
        path: List<String>,
        query: Map<String, String?> = emptyMap(),
        fields: String? = null,
    ): T =
        requestJson(session, path, query, fields) { client, url, accessToken ->
            client.get(url) {
                authorize(accessToken)
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }
        }

    private suspend inline fun <reified T> requestJson(
        session: SpaceSession,
        path: List<String>,
        query: Map<String, String?> = emptyMap(),
        fields: String? = null,
        request: suspend (HttpClient, String, String) -> HttpResponse,
    ): T {
        val url = buildApiUrl(session.credentials.apiBaseUrl, path, query, fields)
        val accessToken = session.accessToken()
        return decodeResponse(url, request(session.httpClient, url, accessToken))
    }

    private fun buildApiUrl(
        apiBaseUrl: String,
        path: List<String>,
        query: Map<String, String?>,
        fields: String?,
    ): String =
        URLBuilder(apiBaseUrl)
            .apply {
                appendPathSegments(*path.toTypedArray())
                query.forEach { (key, value) ->
                    if (!value.isNullOrBlank()) {
                        parameters.append(key, value)
                    }
                }
                if (!fields.isNullOrBlank()) {
                    parameters.append("\$fields", fields)
                }
            }.buildString()

    private suspend inline fun <reified T> decodeResponse(
        url: String,
        response: HttpResponse,
    ): T {
        val bodyText = response.bodyAsText()
        if (!response.status.isSuccess()) {
            throw IllegalStateException("Space API request failed: ${response.status.value} $bodyText")
        }

        return try {
            json.decodeFromString(bodyText)
        } catch (e: SerializationException) {
            throw IllegalStateException("Failed to parse Space API response for $url: ${e.message}\n$bodyText", e)
        }
    }

    private fun HttpRequestBuilder.authorize(accessToken: String) {
        header(HttpHeaders.Authorization, "Bearer $accessToken")
    }

    private fun normalizeReviewIdentifier(reviewRef: String): String {
        val trimmed = reviewRef.trim()
        return when {
            trimmed.startsWith("id:") -> trimmed
            trimmed.startsWith("number:") -> trimmed
            trimmed.startsWith("key:") -> trimmed
            trimmed.toIntOrNull() != null -> "number:$trimmed"
            else -> "id:$trimmed"
        }
    }
}
