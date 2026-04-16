package team.jetbrains.space.mcp.space

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

class SpaceApiClient(
    private val credentialStore: SpaceCredentialStore,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        prettyPrint = true
    }

    suspend fun getCurrentUser(): SpaceProfile {
        val credentials = authorizedCredentials()
        return get(
            credentials = credentials,
            path = listOf("team-directory", "profiles", "me"),
            fields = "id,username,name(firstName,lastName)",
        )
    }

    suspend fun listProjects(
        term: String?,
        limit: Int,
        offset: Int,
    ): BatchResponse<ProjectSummary> {
        val credentials = authorizedCredentials()
        return get(
            credentials = credentials,
            path = listOf("projects"),
            query = mapOf(
                "\$top" to limit.toString(),
                "\$skip" to offset.toString(),
                "term" to term,
            ),
            fields = "data(id,key,name,private),totalCount,next",
        )
    }

    suspend fun listReviews(
        projectKey: String,
        repository: String?,
        state: String?,
        type: String?,
        text: String?,
        author: String?,
        reviewer: String?,
        from: String?,
        to: String?,
        sort: String,
        limit: Int,
        offset: Int,
    ): BatchResponse<ReviewListItemResponse> {
        val credentials = authorizedCredentials()
        return get(
            credentials = credentials,
            path = listOf("projects", "key:$projectKey", "code-reviews"),
            query = mapOf(
                "\$top" to limit.toString(),
                "\$skip" to offset.toString(),
                "repository" to repository,
                "state" to state,
                "type" to type,
                "text" to text,
                "author" to author,
                "reviewer" to reviewer,
                "from" to from,
                "to" to to,
                "sort" to sort,
            ),
            fields = "data(review(className,id,key,number,title,state,createdAt,timestamp,feedChannelId,branchPair(repository,sourceBranchRef,sourceBranchInfo(displayName,ref,deleted,head),targetBranchInfo(displayName,ref,deleted,head),isMerged,isStale))),totalCount,hasMore,next",
        )
    }

    suspend fun listMyReviews(
        projectKey: String?,
        repository: String?,
        state: String?,
        type: String?,
        text: String?,
        role: String,
        from: String?,
        to: String?,
        sort: String,
        limit: Int,
        maxProjects: Int,
        perProjectLimit: Int,
    ): MyReviewsResponse {
        val targetProjects = if (projectKey != null) {
            BatchResponse(
                data = listOf(ProjectSummary(id = projectKey, key = projectKey, name = projectKey)),
                totalCount = 1,
            )
        } else {
            listProjects(
                term = null,
                limit = maxProjects.coerceAtLeast(1),
                offset = 0,
            )
        }

        val filters = roleFilters(role)
        val aggregated = linkedMapOf<String, MutableCrossProjectReview>()
        var scannedProjects = 0

        projectLoop@ for (project in targetProjects.data) {
            scannedProjects += 1

            for (filter in filters) {
                val batch = listReviews(
                    projectKey = project.key,
                    repository = repository,
                    state = state,
                    type = type,
                    text = text,
                    author = filter.author,
                    reviewer = filter.reviewer,
                    from = from,
                    to = to,
                    sort = sort,
                    limit = perProjectLimit.coerceAtLeast(1),
                    offset = 0,
                )

                for (item in batch.data) {
                    val key = "${project.key}:${item.review.id}"
                    val existing = aggregated[key]
                    if (existing == null) {
                        aggregated[key] = MutableCrossProjectReview(
                            projectKey = project.key,
                            projectName = project.name,
                            matchedRoles = linkedSetOf(filter.matchedRole),
                            review = item.review,
                        )
                    } else {
                        existing.matchedRoles += filter.matchedRole
                    }
                }
            }

            if (aggregated.size >= limit) {
                break@projectLoop
            }
        }

        val reviews = aggregated.values
            .sortedByDescending { it.review.timestamp ?: it.review.createdAt ?: Long.MIN_VALUE }
            .take(limit.coerceAtLeast(1))
            .map {
                CrossProjectReview(
                    projectKey = it.projectKey,
                    projectName = it.projectName,
                    matchedRoles = it.matchedRoles.toList(),
                    review = it.review,
                )
            }

        val totalCount = targetProjects.totalCount ?: targetProjects.data.size
        return MyReviewsResponse(
            reviews = reviews,
            scannedProjects = scannedProjects,
            projectScanTruncated = projectKey == null && totalCount > scannedProjects,
            requestedLimit = limit,
        )
    }

    suspend fun getReview(
        projectKey: String,
        reviewRef: String,
        includeCommits: Boolean,
        includeComments: Boolean,
        discussionReplyLimit: Int,
        feedBatchSize: Int,
        feedBatchLimit: Int,
    ): ReviewDetailsBundle {
        val credentials = authorizedCredentials()
        val reviewIdentifier = normalizeReviewIdentifier(reviewRef)

        val review = get<ReviewSummary>(
            credentials = credentials,
            path = listOf("projects", "key:$projectKey", "code-reviews", reviewIdentifier),
            fields = "className,id,key,number,title,state,createdAt,timestamp,feedChannelId,branchPair(repository,sourceBranchRef,sourceBranchInfo(displayName,ref,deleted,head),targetBranchInfo(displayName,ref,deleted,head),isMerged,isStale)",
        )

        val commits = if (includeCommits) {
            get<ReviewDetailsResponse>(
                credentials = credentials,
                path = listOf("projects", "key:$projectKey", "code-reviews", reviewIdentifier, "details"),
            ).commits
        } else {
            emptyList()
        }

        val comments = if (includeComments) {
            val feedChannelId = review.feedChannelId
                ?: throw IllegalStateException("Review ${review.number} has no feed channel id.")
            loadComments(credentials, feedChannelId, discussionReplyLimit, feedBatchSize, feedBatchLimit)
        } else {
            null
        }

        return ReviewDetailsBundle(
            review = review,
            commits = commits,
            comments = comments,
        )
    }

    suspend fun postReviewComment(
        projectKey: String,
        reviewRef: String,
        text: String,
        pending: Boolean,
    ): SentMessageResult {
        val credentials = authorizedCredentials()
        val review = getReview(
            projectKey = projectKey,
            reviewRef = reviewRef,
            includeCommits = false,
            includeComments = false,
            discussionReplyLimit = 0,
            feedBatchSize = 0,
            feedBatchLimit = 0,
        ).review

        val channelId = review.feedChannelId
            ?: throw IllegalStateException("Review ${review.number} has no feed channel id.")

        return sendChannelMessage(credentials, channelId, text, pending)
    }

    suspend fun createCodeDiscussion(
        projectKey: String,
        reviewRef: String,
        repository: String,
        revision: String,
        filename: String,
        line: Int?,
        oldLine: Int?,
        endLine: Int?,
        endOldLine: Int?,
        text: String,
        pending: Boolean,
    ): DiscussionCreationResult {
        val credentials = authorizedCredentials()
        val response = postJson<GenericApiRecord>(
            credentials = credentials,
            path = listOf("projects", "key:$projectKey", "code-reviews", "code-discussions"),
            body = buildJsonObject {
                put("text", JsonPrimitive(text))
                put("repository", JsonPrimitive(repository))
                put("reviewId", JsonPrimitive(normalizeReviewIdentifier(reviewRef)))
                put("pending", JsonPrimitive(pending))
                putJsonObject("anchor") {
                    put("revision", JsonPrimitive(revision))
                    put("filename", JsonPrimitive(filename))
                    line?.let { put("line", JsonPrimitive(it)) }
                    oldLine?.let { put("oldLine", JsonPrimitive(it)) }
                }
                if (endLine != null || endOldLine != null) {
                    putJsonObject("endAnchor") {
                        put("revision", JsonPrimitive(revision))
                        put("filename", JsonPrimitive(filename))
                        endLine?.let { put("line", JsonPrimitive(it)) }
                        endOldLine?.let { put("oldLine", JsonPrimitive(it)) }
                    }
                }
            }
        )

        return DiscussionCreationResult(
            id = response.id,
            channelId = response.channel?.id ?: throw IllegalStateException("Discussion channel id is missing from response."),
            pending = response.pending,
            feedItemId = response.feedItemId,
            anchor = response.anchor,
        )
    }

    suspend fun replyToDiscussion(
        channelId: String,
        text: String,
        pending: Boolean,
    ): SentMessageResult {
        val credentials = authorizedCredentials()
        return sendChannelMessage(credentials, channelId, text, pending)
    }

    private suspend fun loadComments(
        credentials: StoredCredentials,
        feedChannelId: String,
        discussionReplyLimit: Int,
        feedBatchSize: Int,
        feedBatchLimit: Int,
    ): ReviewCommentBundle {
        val feedMessages = fetchFeedMessages(credentials, feedChannelId, feedBatchSize, feedBatchLimit)
        val grouped = linkedMapOf<String, MutableList<SyncChatMessage>>()

        for (message in feedMessages) {
            val discussion = message.details?.codeDiscussion ?: continue
            grouped.getOrPut(discussion.id) { mutableListOf() }.add(message)
        }

        val threads = grouped.values.map { messages ->
            val first = messages.first()
            val discussion = first.details?.codeDiscussion
            val channelId = discussion?.channel?.id
            val threadMessages = if (channelId != null && discussionReplyLimit > 0) {
                fetchChannelMessages(credentials, channelId, discussionReplyLimit)
            } else {
                emptyList()
            }
            CodeDiscussionThread(
                id = discussion?.id ?: error("Missing discussion id."),
                channelId = channelId,
                anchor = discussion.anchor,
                feedMessageId = first.id,
                messages = threadMessages,
            )
        }

        return ReviewCommentBundle(
            feedChannelId = feedChannelId,
            feedMessages = feedMessages.map {
                FeedMessage(
                    id = it.id,
                    text = it.text,
                    created = it.created,
                    author = it.projectedItem?.author,
                    details = it.details,
                )
            },
            codeDiscussions = threads,
        )
    }

    private suspend fun fetchFeedMessages(
        credentials: StoredCredentials,
        channelId: String,
        batchSize: Int,
        batchLimit: Int,
    ): List<SyncChatMessage> {
        val messages = mutableListOf<SyncChatMessage>()
        var etag = "0"

        repeat(batchLimit.coerceAtLeast(1)) {
            val batch = get<SyncBatchResponse>(
                credentials = credentials,
                path = listOf("chats", "messages", "sync-batch"),
                query = mapOf(
                    "batchInfo" to "{etag:$etag,batchSize:$batchSize}",
                    "channel" to "id:$channelId",
                ),
                fields = "data(chatMessage(id,text,created,details(className,codeDiscussion(id,channel(id),anchor(filename,line,oldLine,revision))),projectedItem(author(name,details(className,user(id)))))),etag,hasMore",
            )

            messages += batch.data.mapNotNull { it.chatMessage }
            if (batch.hasMore != true || batch.etag.isNullOrBlank()) {
                return messages
            }
            etag = batch.etag
        }

        return messages
    }

    private suspend fun fetchChannelMessages(
        credentials: StoredCredentials,
        channelId: String,
        batchSize: Int,
    ): List<ChannelMessage> {
        return get<MessagesResponse>(
            credentials = credentials,
            path = listOf("chats", "messages"),
            query = mapOf(
                "channel" to "id:$channelId",
                "sorting" to "FromOldestToNewest",
                "batchSize" to batchSize.toString(),
            ),
            fields = "messages(id,text,author(name,details(className,user(id))),created)",
        ).messages
    }

    private suspend fun sendChannelMessage(
        credentials: StoredCredentials,
        channelId: String,
        text: String,
        pending: Boolean,
    ): SentMessageResult {
        val response = postJson<GenericChatMessageRecord>(
            credentials = credentials,
            path = listOf("chats", "messages", "send"),
            body = buildJsonObject {
                put("channel", JsonPrimitive(channelId))
                put("text", JsonPrimitive(text))
                put("pending", JsonPrimitive(pending))
            }
        )

        return SentMessageResult(
            id = response.id,
            channelId = channelId,
        )
    }

    private suspend inline fun <reified T> get(
        credentials: StoredCredentials,
        path: List<String>,
        query: Map<String, String?> = emptyMap(),
        fields: String? = null,
    ): T {
        val client = httpClient()
        client.use {
            val url = URLBuilder(credentials.apiBaseUrl).apply {
                appendPathSegments(*path.toTypedArray())
                query.forEach { (key, value) ->
                    if (!value.isNullOrBlank()) parameters.append(key, value)
                }
                if (!fields.isNullOrBlank()) {
                    parameters.append("\$fields", fields)
                }
            }.buildString()

            val response = client.get(url) {
                header(HttpHeaders.Authorization, "Bearer ${credentials.accessToken}")
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }

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
    }

    private suspend inline fun <reified T> postJson(
        credentials: StoredCredentials,
        path: List<String>,
        body: JsonObject,
    ): T {
        val client = httpClient()
        client.use {
            val url = URLBuilder(credentials.apiBaseUrl).apply {
                appendPathSegments(*path.toTypedArray())
            }.buildString()

            val response = client.post(url) {
                header(HttpHeaders.Authorization, "Bearer ${credentials.accessToken}")
                contentType(ContentType.Application.Json)
                setBody(body)
            }

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
    }

    private fun httpClient(): HttpClient {
        return HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            expectSuccess = false
        }
    }

    private suspend fun authorizedCredentials(): StoredCredentials {
        val stored = credentialStore.load()
            ?: throw AuthorizationRequiredException("Space credentials are not configured. Run the space_authorize tool first.")

        val envToken = System.getenv("SPACE_ACCESS_TOKEN")?.takeIf { it.isNotBlank() }
        if (envToken != null) {
            return stored.copy(accessToken = envToken)
        }

        val accessToken = stored.accessToken
            ?: throw AuthorizationRequiredException("No Space access token is stored. Run the space_authorize tool first.")

        val expiresAt = stored.expiresAtEpochSeconds
        val isExpired = expiresAt != null && Instant.now().epochSecond >= (expiresAt - 30)
        if (!isExpired) {
            return stored.copy(accessToken = accessToken)
        }

        val refreshed = refreshAccessToken(stored)
        credentialStore.save(refreshed)
        return refreshed
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

    private fun roleFilters(role: String): List<MyReviewFilter> {
        return when (role.trim().lowercase()) {
            "authored", "author", "created" -> listOf(
                MyReviewFilter(
                    author = "me",
                    reviewer = null,
                    matchedRole = "author",
                )
            )

            "reviewing", "reviewer", "assigned" -> listOf(
                MyReviewFilter(
                    author = null,
                    reviewer = "me",
                    matchedRole = "reviewer",
                )
            )

            else -> listOf(
                MyReviewFilter(
                    author = "me",
                    reviewer = null,
                    matchedRole = "author",
                ),
                MyReviewFilter(
                    author = null,
                    reviewer = "me",
                    matchedRole = "reviewer",
                ),
            )
        }
    }
}

private data class MyReviewFilter(
    val author: String?,
    val reviewer: String?,
    val matchedRole: String,
)

private data class MutableCrossProjectReview(
    val projectKey: String,
    val projectName: String?,
    val matchedRoles: LinkedHashSet<String>,
    val review: ReviewSummary,
)
