package team.jetbrains.mcp.space

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

    suspend fun status(apiClient: SpaceApiClient): AuthStatus {
        val credentials = resolveConfiguredCredentials(credentialStore)
        if (credentials == null) {
            return buildAuthStatus()
        }

        return try {
            buildAuthStatus(
                credentials = credentials,
                authenticated = true,
                currentUser = apiClient.getCurrentUser(),
            )
        } catch (_: Throwable) {
            buildAuthStatus(credentials = credentials)
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
        val callbackConfig = parseRedirectConfig(redirectUri)

        val redirectServer = HttpServer.create(
            InetSocketAddress(callbackConfig.host, callbackConfig.port),
            0,
        )
        redirectServer.executor = Executors.newSingleThreadExecutor()

        val state = UUID.randomUUID().toString()
        val codeVerifier = generateCodeVerifier()
        val callback = CompletableFuture<AuthorizationCallback>()

        redirectServer.createContext(callbackConfig.path) { exchange ->
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
            redirectUri = callbackConfig.uri.toString(),
            accessToken = tokenInfo.accessToken,
            refreshToken = tokenInfo.refreshToken,
            expiresAtEpochSeconds = expiresAt,
        )
        credentialStore.save(stored)

        OAuthAuthorizationResult(
            serverUrl = stored.serverUrl,
            apiBaseUrl = stored.apiBaseUrl,
            redirectUri = callbackConfig.uri.toString(),
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

    val tokenInfo = createAuthHttpClient().use { client ->
        try {
            requestOAuthToken(
                client = client,
                serverUrl = credentials.serverUrl,
                clientId = clientId,
                clientSecret = credentials.clientSecret,
                formParameters = Parameters.build {
                    append("grant_type", "refresh_token")
                    append("refresh_token", refreshToken)
                    append("scope", credentials.scope)
                },
            )
        } catch (e: IllegalStateException) {
            throw AuthorizationRequiredException("Failed to refresh the Space access token: ${e.message}")
        }
    }

    val expiresAt = tokenInfo.expiresIn?.let { Instant.now().plusSeconds(it.toLong()).epochSecond }
    return credentials.copy(
        accessToken = tokenInfo.accessToken,
        refreshToken = tokenInfo.refreshToken ?: refreshToken,
        expiresAtEpochSeconds = expiresAt,
    )
}

internal fun resolveConfiguredCredentials(credentialStore: SpaceCredentialStore): StoredCredentials? {
    val stored = credentialStore.load()
    return environmentCredentials(stored) ?: stored
}

internal fun environmentCredentials(stored: StoredCredentials?): StoredCredentials? {
    val envToken = readEnv("SPACE_ACCESS_TOKEN") ?: return null
    val serverUrl = readEnv("SPACE_SERVER_URL")?.trimEnd('/')
        ?: stored?.serverUrl
        ?: SpaceAuthService.DEFAULT_SERVER_URL
    val apiBaseUrl = readEnv("SPACE_API_BASE_URL")?.trimEnd('/')
        ?: stored?.apiBaseUrl
        ?: SpaceAuthService.defaultApiBaseUrl(serverUrl)

    return StoredCredentials(
        serverUrl = serverUrl,
        apiBaseUrl = apiBaseUrl,
        clientId = readEnv("SPACE_CLIENT_ID") ?: stored?.clientId,
        clientSecret = stored?.clientSecret,
        scope = readEnv("SPACE_SCOPE") ?: stored?.scope ?: SpaceAuthService.DEFAULT_SCOPE,
        redirectUri = stored?.redirectUri,
        accessToken = envToken,
        refreshToken = stored?.refreshToken,
        expiresAtEpochSeconds = null,
    )
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
    return requestOAuthToken(
        client = client,
        serverUrl = serverUrl,
        clientId = clientId,
        clientSecret = clientSecret,
        formParameters = Parameters.build {
            append("grant_type", "authorization_code")
            append("code", authorizationCode)
            append("redirect_uri", redirectUri)
            append("code_verifier", codeVerifier)
        },
    )
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

private fun readEnv(name: String): String? {
    return System.getenv(name)?.takeIf { it.isNotBlank() }
}

private fun buildAuthStatus(
    credentials: StoredCredentials? = null,
    authenticated: Boolean = false,
    currentUser: SpaceProfile? = null,
): AuthStatus {
    return AuthStatus(
        configured = credentials != null,
        authenticated = authenticated,
        serverUrl = credentials?.serverUrl,
        apiBaseUrl = credentials?.apiBaseUrl,
        clientId = credentials?.clientId,
        scope = credentials?.scope,
        expiresAtEpochSeconds = credentials?.expiresAtEpochSeconds,
        currentUser = currentUser,
    )
}

private fun parseRedirectConfig(redirectUri: String): RedirectConfig {
    val uri = URI(redirectUri)
    val host = uri.host
        ?: throw IllegalArgumentException("redirectUri must include a host.")
    val port = if (uri.port != -1) uri.port else uri.toURL().defaultPort
    require(port > 0) { "redirectUri must include an explicit port." }
    val path = uri.path?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("redirectUri must include a path.")
    return RedirectConfig(
        uri = uri,
        host = host,
        port = port,
        path = path,
    )
}

private suspend fun requestOAuthToken(
    client: HttpClient,
    serverUrl: String,
    clientId: String,
    clientSecret: String?,
    formParameters: Parameters,
): OAuthTokenResponse {
    val response = client.submitForm(
        url = "${serverUrl.trimEnd('/')}/oauth/token",
        formParameters = withClientId(formParameters, clientId, clientSecret),
    ) {
        basicAuthorization(clientId, clientSecret)
    }

    if (!response.status.isSuccess()) {
        throw IllegalStateException("${response.status.value} ${response.bodyAsText()}")
    }

    return response.body()
}

private fun withClientId(
    formParameters: Parameters,
    clientId: String,
    clientSecret: String?,
): Parameters {
    if (clientSecret != null) {
        return formParameters
    }

    return Parameters.build {
        formParameters.forEach { key, values ->
            values.forEach { value -> append(key, value) }
        }
        append("client_id", clientId)
    }
}

private fun io.ktor.client.request.HttpRequestBuilder.basicAuthorization(
    clientId: String,
    clientSecret: String?,
) {
    if (clientSecret == null) {
        return
    }

    headers.append(
        "Authorization",
        "Basic " + Base64.getEncoder()
            .encodeToString("$clientId:$clientSecret".toByteArray(StandardCharsets.UTF_8)),
    )
}

private data class RedirectConfig(
    val uri: URI,
    val host: String,
    val port: Int,
    val path: String,
)
