package team.jetbrains.space.mcp.space

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SpaceAuthService(
    private val credentialStore: SpaceCredentialStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    suspend fun status(apiClient: SpaceApiClient): AuthStatus {
        val credentials = credentialStore.load()
        if (credentials == null) {
            return AuthStatus(
                configured = false,
                authenticated = false,
                serverUrl = null,
                apiBaseUrl = null,
                clientId = null,
                scope = null,
                expiresAtEpochSeconds = null,
            )
        }

        return try {
            val profile = apiClient.getCurrentUser()
            AuthStatus(
                configured = true,
                authenticated = true,
                serverUrl = credentials.serverUrl,
                apiBaseUrl = credentials.apiBaseUrl,
                clientId = credentials.clientId,
                scope = credentials.scope,
                expiresAtEpochSeconds = credentials.expiresAtEpochSeconds,
                currentUser = profile,
            )
        } catch (_: Throwable) {
            AuthStatus(
                configured = true,
                authenticated = false,
                serverUrl = credentials.serverUrl,
                apiBaseUrl = credentials.apiBaseUrl,
                clientId = credentials.clientId,
                scope = credentials.scope,
                expiresAtEpochSeconds = credentials.expiresAtEpochSeconds,
            )
        }
    }

    suspend fun authorize(
        clientId: String,
        clientSecret: String?,
        serverUrl: String,
        scope: String,
        redirectUri: String,
        openBrowser: Boolean,
        timeoutSeconds: Int,
    ): OAuthAuthorizationResult = withContext(Dispatchers.IO) {
        val normalizedServerUrl = serverUrl.trimEnd('/')
        val callbackUri = URI(redirectUri)
        val callbackHost = callbackUri.host
            ?: throw IllegalArgumentException("redirectUri must include a host.")
        val callbackPort = if (callbackUri.port != -1) callbackUri.port else callbackUri.toURL().defaultPort
        require(callbackPort > 0) { "redirectUri must include an explicit port." }
        val callbackPath = callbackUri.path?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("redirectUri must include a path.")

        val redirectServer = HttpServer.create(InetSocketAddress(callbackHost, callbackPort), 0)
        redirectServer.executor = Executors.newSingleThreadExecutor()

        val state = UUID.randomUUID().toString()
        val codeVerifier = generateCodeVerifier()
        val callback = CompletableFuture<AuthorizationCallback>()

        redirectServer.createContext(callbackPath) { exchange ->
            val query = exchange.requestURI.rawQuery.orEmpty()
            val parameters = parseQuery(query)
            val callbackState = parameters["state"]
            val error = parameters["error"]
            val description = parameters["error_description"]
            val code = parameters["code"]

            val (statusCode, body) = when {
                error != null -> {
                    callback.completeExceptionally(IllegalStateException("Space authorization failed: $error ${description.orEmpty()}".trim()))
                    400 to "<html><body><h2>Space authorization failed.</h2><p>${escapeHtml(description ?: error)}</p></body></html>"
                }
                callbackState != state -> {
                    callback.completeExceptionally(IllegalStateException("Space authorization failed: state mismatch."))
                    400 to "<html><body><h2>Space authorization failed.</h2><p>State mismatch.</p></body></html>"
                }
                code.isNullOrBlank() -> {
                    callback.completeExceptionally(IllegalStateException("Space authorization failed: no authorization code returned."))
                    400 to "<html><body><h2>Space authorization failed.</h2><p>No authorization code returned.</p></body></html>"
                }
                else -> {
                    callback.complete(AuthorizationCallback(code))
                    200 to "<html><body><h2>Space authorization complete.</h2><p>You can close this tab and return to the MCP client.</p></body></html>"
                }
            }

            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }

        redirectServer.start()
        val authUrl = buildAuthorizationUrl(
            serverUrl = normalizedServerUrl,
            clientId = clientId,
            redirectUri = redirectUri,
            state = state,
            scope = scope,
            codeVerifier = codeVerifier,
        )

        if (openBrowser && Desktop.isDesktopSupported()) {
            Desktop.getDesktop().browse(URI(authUrl))
        }

        val authorizationCode = try {
            callback.get(timeoutSeconds.toLong(), TimeUnit.SECONDS).code
        } finally {
            redirectServer.stop(0)
            (redirectServer.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
        }

        val tokenInfo = createAuthHttpClient().use { client ->
            exchangeCodeForTokens(
                client = client,
                serverUrl = normalizedServerUrl,
                clientId = clientId,
                clientSecret = clientSecret,
                authorizationCode = authorizationCode,
                redirectUri = redirectUri,
                codeVerifier = codeVerifier,
            )
        }

        val expiresAt = tokenInfo.expiresIn?.let { Instant.now().plusSeconds(it.toLong()).epochSecond }
        val stored = StoredCredentials(
            serverUrl = normalizedServerUrl,
            apiBaseUrl = defaultApiBaseUrl(normalizedServerUrl),
            clientId = clientId,
            clientSecret = clientSecret,
            scope = scope,
            redirectUri = callbackUri.toString(),
            accessToken = tokenInfo.accessToken,
            refreshToken = tokenInfo.refreshToken,
            expiresAtEpochSeconds = expiresAt,
        )
        credentialStore.save(stored)

        OAuthAuthorizationResult(
            serverUrl = stored.serverUrl,
            apiBaseUrl = stored.apiBaseUrl,
            redirectUri = callbackUri.toString(),
            scope = scope,
            clientId = clientId,
            hasClientSecret = clientSecret != null,
            expiresAtEpochSeconds = expiresAt,
        )
    }

    companion object {
        const val DEFAULT_SERVER_URL = "https://jetbrains.team"
        const val DEFAULT_SCOPE = "**"
        const val DEFAULT_REDIRECT_URI = "http://localhost:63363/api/space/oauth/authorization_code"

        fun defaultApiBaseUrl(serverUrl: String): String = "${serverUrl.trimEnd('/')}/api/http"
    }
}

class AuthorizationRequiredException(message: String) : IllegalStateException(message)

@Serializable
private data class OAuthTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("expires_in")
    val expiresIn: Int? = null,
)

private data class AuthorizationCallback(
    val code: String,
)

internal fun createAuthHttpClient(): HttpClient {
    return HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        expectSuccess = false
    }
}

internal suspend fun refreshAccessToken(credentials: StoredCredentials): StoredCredentials {
    val refreshToken = credentials.refreshToken
        ?: throw AuthorizationRequiredException("No refresh token is stored. Run the space_authorize tool again.")
    val clientId = credentials.clientId
        ?: throw AuthorizationRequiredException("No Space client ID is stored. Run the space_authorize tool again.")

    val client = createAuthHttpClient()
    client.use {
        val response = client.submitForm(
            url = "${credentials.serverUrl.trimEnd('/')}/oauth/token",
            formParameters = Parameters.build {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
                append("scope", credentials.scope)
                if (credentials.clientSecret == null) {
                    append("client_id", clientId)
                }
            }
        ) {
            if (credentials.clientSecret != null) {
                headers.append(
                    "Authorization",
                    "Basic " + Base64.getEncoder().encodeToString("$clientId:${credentials.clientSecret}".toByteArray(StandardCharsets.UTF_8))
                )
            }
        }

        if (!response.status.isSuccess()) {
            throw AuthorizationRequiredException(
                "Failed to refresh the Space access token: ${response.status.value} ${response.bodyAsText()}"
            )
        }

        val tokenInfo = response.body<OAuthTokenResponse>()
        val expiresAt = tokenInfo.expiresIn?.let { Instant.now().plusSeconds(it.toLong()).epochSecond }
        return credentials.copy(
            accessToken = tokenInfo.accessToken,
            refreshToken = tokenInfo.refreshToken ?: refreshToken,
            expiresAtEpochSeconds = expiresAt,
        )
    }
}

private suspend fun exchangeCodeForTokens(
    client: HttpClient,
    serverUrl: String,
    clientId: String,
    clientSecret: String?,
    authorizationCode: String,
    redirectUri: String,
    codeVerifier: String,
): OAuthTokenResponse {
    val response = client.submitForm(
        url = "${serverUrl.trimEnd('/')}/oauth/token",
        formParameters = Parameters.build {
            append("grant_type", "authorization_code")
            append("code", authorizationCode)
            append("redirect_uri", redirectUri)
            append("code_verifier", codeVerifier)
            if (clientSecret == null) {
                append("client_id", clientId)
            }
        }
    ) {
        if (clientSecret != null) {
            headers.append(
                "Authorization",
                "Basic " + Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray(StandardCharsets.UTF_8))
            )
        }
    }

    if (!response.status.isSuccess()) {
        throw IllegalStateException("Space token exchange failed: ${response.status.value} ${response.bodyAsText()}")
    }

    return response.body()
}

private fun buildAuthorizationUrl(
    serverUrl: String,
    clientId: String,
    redirectUri: String,
    state: String,
    scope: String,
    codeVerifier: String,
): String {
    val codeChallenge = createCodeChallenge(codeVerifier)
    return buildString {
        append(serverUrl.trimEnd('/'))
        append("/oauth/auth?")
        append("response_type=code")
        append("&state=").append(urlEncode(state))
        append("&redirect_uri=").append(urlEncode(redirectUri))
        append("&request_credentials=default")
        append("&client_id=").append(urlEncode(clientId))
        append("&scope=").append(urlEncode(scope))
        append("&access_type=offline")
        append("&code_challenge=").append(urlEncode(codeChallenge))
        append("&code_challenge_method=S256")
    }
}

private fun generateCodeVerifier(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

private fun createCodeChallenge(codeVerifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashed = digest.digest(codeVerifier.toByteArray(StandardCharsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed)
}

private fun parseQuery(query: String): Map<String, String> {
    if (query.isBlank()) return emptyMap()
    return query.split('&')
        .mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            val key = pieces.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = pieces.getOrNull(1)?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }.orEmpty()
            key to value
        }
        .toMap()
}

private fun urlEncode(value: String): String {
    return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)
}

private fun escapeHtml(value: String): String {
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
