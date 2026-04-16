package team.jetbrains.space.mcp

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import team.jetbrains.space.mcp.space.SpaceApiClient
import team.jetbrains.space.mcp.space.SpaceAuthService
import team.jetbrains.space.mcp.space.SpaceCredentialStore

class SpaceMcpServer {
    private val jsonSupport = ToolJsonSupport()
    private val credentialStore = SpaceCredentialStore()
    private val authService = SpaceAuthService(credentialStore)
    private val apiClient = SpaceApiClient(credentialStore)

    fun run() {
        val server = Server(
            Implementation(
                name = "space-mcp-server",
                version = "0.1.0",
            ),
            ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = false),
                ),
            ),
        )

        registerTools(server)

        val transport = StdioServerTransport(
            System.`in`.asSource().buffered(),
            System.out.asSink().buffered(),
        )

        runBlocking {
            val done = CompletableDeferred<Unit>()
            server.onClose {
                done.complete(Unit)
            }
            server.createSession(transport)
            done.await()
        }
    }

    private fun registerTools(server: Server) {
        server.registerTool(
            name = "space_auth_status",
            description = "Show the current JetBrains Space authorization status and the active user if credentials are valid.",
        ) {
            authService.status(apiClient)
        }

        server.registerTool(
            name = "space_authorize",
            description = "Authorize this MCP server against a Space organization with OAuth authorization code flow and PKCE. Requires a Space client application.",
            required = listOf("clientId"),
            properties = {
                stringProperty("clientId", "Space application client ID.")
                stringProperty("clientSecret", "Optional client secret for confidential clients.")
                stringProperty("serverUrl", "Space base URL, for example https://jetbrains.team. Defaults to https://jetbrains.team.")
                stringProperty("scope", "OAuth scope string. Defaults to **.")
                stringProperty("redirectUri", "Exact redirect URI configured in the Space application. Defaults to http://localhost:63363/api/space/oauth/authorization_code.")
                booleanProperty("openBrowser", "Open the authorization URL in the default browser automatically. Defaults to true.")
                numberProperty("timeoutSeconds", "How long to wait for the browser callback before failing. Defaults to 300.")
            },
        ) { arguments ->
            authService.authorize(
                clientId = arguments.requiredString("clientId"),
                clientSecret = arguments.optionalString("clientSecret"),
                serverUrl = arguments.optionalString("serverUrl") ?: SpaceAuthService.DEFAULT_SERVER_URL,
                scope = arguments.optionalString("scope") ?: SpaceAuthService.DEFAULT_SCOPE,
                redirectUri = arguments.optionalString("redirectUri") ?: SpaceAuthService.DEFAULT_REDIRECT_URI,
                openBrowser = arguments.optionalBoolean("openBrowser") ?: true,
                timeoutSeconds = arguments.optionalInt("timeoutSeconds") ?: 300,
            )
        }

        server.registerTool(
            name = "space_list_projects",
            description = "List Space projects visible to the authorized user.",
            properties = {
                stringProperty("term", "Optional substring filter.")
                numberProperty("limit", "Maximum number of projects to return. Defaults to 50.")
                numberProperty("offset", "Pagination offset. Defaults to 0.")
            },
        ) { arguments ->
            apiClient.listProjects(
                term = arguments.optionalString("term"),
                limit = arguments.optionalInt("limit") ?: 50,
                offset = arguments.optionalInt("offset") ?: 0,
            )
        }

        server.registerTool(
            name = "space_list_reviews",
            description = "List merge requests and code reviews in a Space project. Supports author and reviewer filters, including me.",
            required = listOf("projectKey"),
            properties = {
                stringProperty("projectKey", "Space project key, for example IJ.")
                stringProperty("repository", "Optional repository name filter.")
                stringProperty("state", "Optional state filter. Examples: Opened, Closed, Merged, NeedsReview, RequiresAuthorAttention.")
                stringProperty("type", "Optional review type filter. Examples: MergeRequest, CommitSetReview.")
                stringProperty("text", "Optional text search.")
                stringProperty("author", "Optional author filter. Use me, id:<profileId>, or another Space profile identifier.")
                stringProperty("reviewer", "Optional reviewer filter. Use me, id:<profileId>, or another Space profile identifier.")
                stringProperty("from", "Optional lower created-at bound in Space date format, for example 2026-04-01.")
                stringProperty("to", "Optional upper created-at bound in Space date format, for example 2026-04-16.")
                stringProperty("sort", "Sort order. Defaults to LastUpdatedDesc.")
                numberProperty("limit", "Maximum number of reviews to return. Defaults to 20.")
                numberProperty("offset", "Pagination offset. Defaults to 0.")
            },
        ) { arguments ->
            apiClient.listReviews(
                projectKey = arguments.requiredString("projectKey"),
                repository = arguments.optionalString("repository"),
                state = arguments.optionalString("state"),
                type = arguments.optionalString("type"),
                text = arguments.optionalString("text"),
                author = arguments.optionalString("author"),
                reviewer = arguments.optionalString("reviewer"),
                from = arguments.optionalString("from"),
                to = arguments.optionalString("to"),
                sort = arguments.optionalString("sort") ?: "LastUpdatedDesc",
                limit = arguments.optionalInt("limit") ?: 20,
                offset = arguments.optionalInt("offset") ?: 0,
            )
        }

        server.registerTool(
            name = "space_list_my_reviews",
            description = "List your authored merge requests and assigned reviews across one project or across visible projects.",
            properties = {
                stringProperty("projectKey", "Optional Space project key. If omitted, visible projects are scanned.")
                stringProperty("repository", "Optional repository name filter.")
                stringProperty("state", "Optional state filter. Examples: Opened, Closed, Merged, NeedsReview, RequiresAuthorAttention.")
                stringProperty("type", "Optional review type filter. Examples: MergeRequest, CommitSetReview.")
                stringProperty("text", "Optional text search.")
                stringProperty("role", "Which of your reviews to include: both, authored, or reviewing. Defaults to both.")
                stringProperty("from", "Optional lower created-at bound in Space date format, for example 2026-04-01.")
                stringProperty("to", "Optional upper created-at bound in Space date format, for example 2026-04-16.")
                stringProperty("sort", "Sort order. Defaults to LastUpdatedDesc.")
                numberProperty("limit", "Maximum number of reviews to return overall. Defaults to 50.")
                numberProperty("maxProjects", "Maximum number of projects to scan when projectKey is omitted. Defaults to 50.")
                numberProperty("perProjectLimit", "Maximum number of reviews to fetch per project and role. Defaults to 20.")
            },
        ) { arguments ->
            apiClient.listMyReviews(
                projectKey = arguments.optionalString("projectKey"),
                repository = arguments.optionalString("repository"),
                state = arguments.optionalString("state"),
                type = arguments.optionalString("type"),
                text = arguments.optionalString("text"),
                role = arguments.optionalString("role") ?: "both",
                from = arguments.optionalString("from"),
                to = arguments.optionalString("to"),
                sort = arguments.optionalString("sort") ?: "LastUpdatedDesc",
                limit = arguments.optionalInt("limit") ?: 50,
                maxProjects = arguments.optionalInt("maxProjects") ?: 50,
                perProjectLimit = arguments.optionalInt("perProjectLimit") ?: 20,
            )
        }

        server.registerTool(
            name = "space_get_review",
            description = "Get a Space code review or merge request, including branch info, commits, feed comments, and code discussion threads.",
            required = listOf("projectKey", "review"),
            properties = {
                stringProperty("projectKey", "Space project key, for example IJ.")
                stringProperty("review", "Review identifier. Accepts raw Space id, id:<id>, number:<n>, key:<key>, or a plain integer review number.")
                booleanProperty("includeCommits", "Include commit details. Defaults to true.")
                booleanProperty("includeComments", "Include feed comments and code discussions. Defaults to true.")
                numberProperty("discussionReplyLimit", "Maximum replies to fetch per discussion thread. Defaults to 50.")
                numberProperty("feedBatchSize", "Maximum feed messages to fetch per sync batch. Defaults to 100.")
                numberProperty("feedBatchLimit", "Maximum number of feed sync batches to fetch. Defaults to 50.")
            },
        ) { arguments ->
            apiClient.getReview(
                projectKey = arguments.requiredString("projectKey"),
                reviewRef = arguments.requiredString("review"),
                includeCommits = arguments.optionalBoolean("includeCommits") ?: true,
                includeComments = arguments.optionalBoolean("includeComments") ?: true,
                discussionReplyLimit = arguments.optionalInt("discussionReplyLimit") ?: 50,
                feedBatchSize = arguments.optionalInt("feedBatchSize") ?: 100,
                feedBatchLimit = arguments.optionalInt("feedBatchLimit") ?: 50,
            )
        }

        server.registerTool(
            name = "space_post_review_comment",
            description = "Post a plain text message to the main review feed channel.",
            required = listOf("projectKey", "review", "text"),
            properties = {
                stringProperty("projectKey", "Space project key, for example IJ.")
                stringProperty("review", "Review identifier. Accepts raw Space id, id:<id>, number:<n>, key:<key>, or a plain integer review number.")
                stringProperty("text", "Comment text.")
                booleanProperty("pending", "Create the message as pending. Defaults to false.")
            },
        ) { arguments ->
            apiClient.postReviewComment(
                projectKey = arguments.requiredString("projectKey"),
                reviewRef = arguments.requiredString("review"),
                text = arguments.requiredString("text"),
                pending = arguments.optionalBoolean("pending") ?: false,
            )
        }

        server.registerTool(
            name = "space_create_code_discussion",
            description = "Create a new code discussion anchored to a file and line in a Space review.",
            required = listOf("projectKey", "review", "repository", "revision", "filename", "text"),
            properties = {
                stringProperty("projectKey", "Space project key, for example IJ.")
                stringProperty("review", "Review identifier. Accepts raw Space id, id:<id>, number:<n>, key:<key>, or a plain integer review number.")
                stringProperty("repository", "Repository name.")
                stringProperty("revision", "Revision hash that owns the commented line.")
                stringProperty("filename", "Repository-relative file path.")
                numberProperty("line", "0-based line index on the current side.")
                numberProperty("oldLine", "0-based line index on the old side for deleted-line comments.")
                numberProperty("endLine", "Optional 0-based end line index on the current side.")
                numberProperty("endOldLine", "Optional 0-based end line index on the old side.")
                stringProperty("text", "Comment text.")
                booleanProperty("pending", "Create the discussion as pending. Defaults to false.")
            },
        ) { arguments ->
            apiClient.createCodeDiscussion(
                projectKey = arguments.requiredString("projectKey"),
                reviewRef = arguments.requiredString("review"),
                repository = arguments.requiredString("repository"),
                revision = arguments.requiredString("revision"),
                filename = arguments.requiredString("filename"),
                line = arguments.optionalInt("line"),
                oldLine = arguments.optionalInt("oldLine"),
                endLine = arguments.optionalInt("endLine"),
                endOldLine = arguments.optionalInt("endOldLine"),
                text = arguments.requiredString("text"),
                pending = arguments.optionalBoolean("pending") ?: false,
            )
        }

        server.registerTool(
            name = "space_reply_to_discussion",
            description = "Reply to an existing Space code discussion or review thread channel.",
            required = listOf("channelId", "text"),
            properties = {
                stringProperty("channelId", "Discussion or thread channel id.")
                stringProperty("text", "Reply text.")
                booleanProperty("pending", "Create the reply as pending. Defaults to false.")
            },
        ) { arguments ->
            apiClient.replyToDiscussion(
                channelId = arguments.requiredString("channelId"),
                text = arguments.requiredString("text"),
                pending = arguments.optionalBoolean("pending") ?: false,
            )
        }
    }

    private fun Server.registerTool(
        name: String,
        description: String,
        required: List<String> = emptyList(),
        properties: JsonObjectBuilder.() -> Unit = {},
        handler: suspend (JsonObject) -> Any,
    ) {
        addTool(
            Tool(
                name = name,
                description = description,
                inputSchema = ToolSchema(
                    properties = buildJsonObject(properties),
                    required = required,
                ),
            ),
        ) { request ->
            respond {
                handler(request.argumentsOrEmpty())
            }
        }
    }

    private suspend inline fun <reified T> respond(crossinline block: suspend () -> T): CallToolResult {
        return try {
            result(block())
        } catch (t: Throwable) {
            error("${t::class.simpleName}: ${t.message ?: "Unknown error"}")
        }
    }

    private inline fun <reified T> result(payload: T): CallToolResult {
        return CallToolResult(
            content = listOf(TextContent(text = jsonSupport.encode(payload))),
        )
    }

    private fun error(message: String): CallToolResult {
        return CallToolResult(
            content = listOf(TextContent(text = message)),
            isError = true,
        )
    }
}

private fun CallToolRequest.argumentsOrEmpty(): JsonObject {
    return arguments ?: EMPTY_JSON_OBJECT
}

private val EMPTY_JSON_OBJECT = buildJsonObject {}

private fun JsonObject.requiredString(name: String): String {
    return this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: throw IllegalArgumentException("Missing required string argument '$name'.")
}

private fun JsonObject.optionalString(name: String): String? {
    return this[name]?.jsonPrimitive?.contentOrNull
}

private fun JsonObject.optionalBoolean(name: String): Boolean? {
    return this[name]?.jsonPrimitive?.booleanOrNull
}

private fun JsonObject.optionalInt(name: String): Int? {
    return this[name]?.jsonPrimitive?.intOrNull
}

private fun JsonObjectBuilder.stringProperty(name: String, description: String) {
    putJsonObject(name) {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
    }
}

private fun JsonObjectBuilder.numberProperty(name: String, description: String) {
    putJsonObject(name) {
        put("type", JsonPrimitive("integer"))
        put("description", JsonPrimitive(description))
    }
}

private fun JsonObjectBuilder.booleanProperty(name: String, description: String) {
    putJsonObject(name) {
        put("type", JsonPrimitive("boolean"))
        put("description", JsonPrimitive(description))
    }
}
