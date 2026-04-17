package team.jetbrains.mcp.space

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import space.jetbrains.api.runtime.OAuthAccessType
import space.jetbrains.api.runtime.OAuthRequestCredentials
import space.jetbrains.api.runtime.PermissionScope
import space.jetbrains.api.runtime.Space
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.ktorClientForSpace
import java.awt.Desktop
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
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
    ): OAuthAuthorizationResult =
        withContext(Dispatchers.IO) {
            val normalizedServerUrl = serverUrl.trimEnd('/')
            val callbackConfig = parseRedirectConfig(redirectUri)
            val appInstance =
                if (clientSecret.isNullOrBlank()) {
                    SpaceAppInstance.withoutSecret(clientId, normalizedServerUrl)
                } else {
                    SpaceAppInstance(clientId, clientSecret, normalizedServerUrl)
                }

            val redirectServer =
                HttpServer.create(
                    InetSocketAddress(callbackConfig.host, callbackConfig.port),
                    0,
                )
            redirectServer.executor = Executors.newSingleThreadExecutor()

            val state = UUID.randomUUID().toString()
            val codeVerifier = Space.generateCodeVerifier()
            val callback = CompletableFuture<AuthorizationCallback>()

            redirectServer.createContext(callbackConfig.path) { exchange ->
                val query = exchange.requestURI.rawQuery.orEmpty()
                val parameters = parseQuery(query)
                val callbackState = parameters["state"]
                val error = parameters["error"]
                val description = parameters["error_description"]
                val code = parameters["code"]

                val (statusCode, body) =
                    when {
                        error != null -> {
                            callback.completeExceptionally(
                                IllegalStateException("Space authorization failed: $error ${description.orEmpty()}".trim()),
                            )
                            400 to
                                "<html><body><h2>Space authorization failed.</h2><p>${escapeHtml(description ?: error)}</p></body></html>"
                        }
                        callbackState != state -> {
                            callback.completeExceptionally(IllegalStateException("Space authorization failed: state mismatch."))
                            400 to "<html><body><h2>Space authorization failed.</h2><p>State mismatch.</p></body></html>"
                        }
                        code.isNullOrBlank() -> {
                            callback.completeExceptionally(
                                IllegalStateException("Space authorization failed: no authorization code returned."),
                            )
                            400 to "<html><body><h2>Space authorization failed.</h2><p>No authorization code returned.</p></body></html>"
                        }
                        else -> {
                            callback.complete(AuthorizationCallback(code))
                            200 to
                                "<html><body><h2>Space authorization complete.</h2><p>You can close this tab and return to the MCP client.</p></body></html>"
                        }
                    }

                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                val bytes = body.toByteArray(StandardCharsets.UTF_8)
                exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }

            redirectServer.start()
            val authUrl =
                Space.authCodeSpaceUrl(
                    appInstance = appInstance,
                    scope = PermissionScope.fromString(scope),
                    state = state,
                    redirectUri = redirectUri,
                    requestCredentials = OAuthRequestCredentials.DEFAULT,
                    accessType = OAuthAccessType.OFFLINE,
                    codeVerifier = codeVerifier,
                )

            if (openBrowser && Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(authUrl))
            }

            val authorizationCode =
                try {
                    callback.get(timeoutSeconds.toLong(), TimeUnit.SECONDS).code
                } finally {
                    redirectServer.stop(0)
                    (redirectServer.executor as? java.util.concurrent.ExecutorService)?.shutdownNow()
                }

            val tokenInfo =
                createAuthHttpClient().use { client ->
                    Space.exchangeAuthCodeForToken(
                        ktorClient = client,
                        appInstance = appInstance,
                        authCode = authorizationCode,
                        redirectUri = redirectUri,
                        codeVerifier = codeVerifier,
                    )
                }

            val stored =
                StoredCredentials(
                    serverUrl = normalizedServerUrl,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    scope = scope,
                    redirectUri = callbackConfig.uri.toString(),
                    accessToken = tokenInfo.accessToken,
                    refreshToken = tokenInfo.refreshToken,
                    expiresAtEpochSeconds = tokenInfo.expires?.epochSeconds,
                )
            credentialStore.save(stored)

            OAuthAuthorizationResult(
                serverUrl = stored.serverUrl,
                redirectUri = callbackConfig.uri.toString(),
                scope = scope,
                clientId = clientId,
                hasClientSecret = clientSecret != null,
                expiresAtEpochSeconds = stored.expiresAtEpochSeconds,
            )
        }

    companion object {
        const val DEFAULT_SERVER_URL = "https://jetbrains.team"
        const val DEFAULT_SCOPE = "**"
        const val DEFAULT_REDIRECT_URI = "http://localhost:63363/api/space/oauth/authorization_code"
    }
}

class AuthorizationRequiredException(
    message: String,
) : IllegalStateException(message)

private data class AuthorizationCallback(
    val code: String,
)

internal fun createAuthHttpClient(): HttpClient = ktorClientForSpace()

internal fun resolveConfiguredCredentials(credentialStore: SpaceCredentialStore): StoredCredentials? {
    val stored = credentialStore.load()
    return environmentCredentials(stored) ?: stored
}

internal fun environmentCredentials(stored: StoredCredentials?): StoredCredentials? {
    val envToken = readEnv("SPACE_ACCESS_TOKEN") ?: return null
    val serverUrl =
        readEnv("SPACE_SERVER_URL")?.trimEnd('/')
            ?: stored?.serverUrl
            ?: SpaceAuthService.DEFAULT_SERVER_URL

    return StoredCredentials(
        serverUrl = serverUrl,
        clientId = readEnv("SPACE_CLIENT_ID") ?: stored?.clientId,
        clientSecret = stored?.clientSecret,
        scope = readEnv("SPACE_SCOPE") ?: stored?.scope ?: SpaceAuthService.DEFAULT_SCOPE,
        redirectUri = stored?.redirectUri,
        accessToken = envToken,
        refreshToken = stored?.refreshToken,
        expiresAtEpochSeconds = null,
    )
}

private fun parseQuery(query: String): Map<String, String> {
    if (query.isBlank()) return emptyMap()
    return query
        .split('&')
        .mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            val key = pieces.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val value = pieces.getOrNull(1)?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }.orEmpty()
            key to value
        }.toMap()
}

private fun escapeHtml(value: String): String =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

private fun readEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

private fun buildAuthStatus(
    credentials: StoredCredentials? = null,
    authenticated: Boolean = false,
    currentUser: SpaceProfile? = null,
): AuthStatus =
    AuthStatus(
        configured = credentials != null,
        authenticated = authenticated,
        serverUrl = credentials?.serverUrl,
        clientId = credentials?.clientId,
        scope = credentials?.scope,
        expiresAtEpochSeconds = credentials?.expiresAtEpochSeconds,
        currentUser = currentUser,
    )

private fun parseRedirectConfig(redirectUri: String): RedirectConfig {
    val uri = URI(redirectUri)
    val host =
        uri.host
            ?: throw IllegalArgumentException("redirectUri must include a host.")
    val port = if (uri.port != -1) uri.port else uri.toURL().defaultPort
    require(port > 0) { "redirectUri must include an explicit port." }
    val path =
        uri.path?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("redirectUri must include a path.")
    return RedirectConfig(
        uri = uri,
        host = host,
        port = port,
        path = path,
    )
}

private data class RedirectConfig(
    val uri: URI,
    val host: String,
    val port: Int,
    val path: String,
)
