package team.jetbrains.mcp.space

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

class SpaceApiClient(
    private val credentialStore: SpaceCredentialStore,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            prettyPrint = true
        }

    suspend fun getCurrentUser(): SpaceProfile =
        withAuthorizedCredentials { credentials ->
            get(
                credentials = credentials,
                path = listOf("team-directory", "profiles", "me"),
                fields = "id,username,name(firstName,lastName)",
            )
        }

    suspend fun listProjects(
        term: String?,
        limit: Int,
        offset: Int,
    ): BatchResponse<ProjectSummary> =
        withAuthorizedCredentials { credentials ->
            get(
                credentials = credentials,
                path = listOf("projects"),
                query =
                    mapOf(
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
    ): BatchResponse<ReviewListItemResponse> =
        withAuthorizedCredentials { credentials ->
            val response =
                get<BatchResponse<ReviewListItemResponse>>(
                    credentials = credentials,
                    path = listOf("projects", "key:$projectKey", "code-reviews"),
                    query =
                        reviewQuery(
                            repository = repository,
                            state = state,
                            type = type,
                            text = text,
                            author = author,
                            reviewer = reviewer,
                            from = from,
                            to = to,
                            sort = sort,
                            limit = limit,
                            offset = offset,
                        ),
                    fields = REVIEW_LIST_FIELDS,
                )
            response.copy(
                data = response.data.map { it.copy(review = it.review.normalized()) },
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
        val targetProjects = loadTargetProjects(projectKey, maxProjects)
        val aggregated = linkedMapOf<String, MutableCrossProjectReview>()
        var scannedProjects = 0
        val reviewFilters = roleFilters(role)
        val perProjectReviewLimit = perProjectLimit.coerceAtLeast(1)

        projectLoop@ for (project in targetProjects.data) {
            scannedProjects += 1

            for (filter in reviewFilters) {
                val batch =
                    listReviews(
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
                        limit = perProjectReviewLimit,
                        offset = 0,
                    )
                mergeReviewBatch(aggregated, project, filter.matchedRole, batch)
            }

            if (aggregated.size >= limit) {
                break@projectLoop
            }
        }

        return buildMyReviewsResponse(
            aggregated = aggregated,
            totalProjects = targetProjects.totalCount ?: targetProjects.data.size,
            scannedProjects = scannedProjects,
            projectScoped = projectKey != null,
            limit = limit,
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
    ): ReviewDetailsBundle =
        withAuthorizedCredentials { credentials ->
            val review = fetchReviewSummary(credentials, projectKey, reviewRef)
            ReviewDetailsBundle(
                review = review,
                commits = if (includeCommits) fetchReviewCommits(credentials, projectKey, reviewRef) else emptyList(),
                comments =
                    if (includeComments) {
                        loadComments(
                            credentials = credentials,
                            feedChannelId = requireFeedChannelId(review),
                            discussionReplyLimit = discussionReplyLimit,
                            feedBatchSize = feedBatchSize,
                            feedBatchLimit = feedBatchLimit,
                        )
                    } else {
                        null
                    },
            )
        }

    suspend fun listReviewComments(
        projectKey: String,
        reviewRef: String,
        author: String?,
        discussionReplyLimit: Int,
        feedBatchSize: Int,
        feedBatchLimit: Int,
    ): ReviewCommentsResponse =
        withAuthorizedCredentials { credentials ->
            val review = fetchReviewSummary(credentials, projectKey, reviewRef)
            val comments =
                loadComments(
                    credentials = credentials,
                    feedChannelId = requireFeedChannelId(review),
                    discussionReplyLimit = discussionReplyLimit,
                    feedBatchSize = feedBatchSize,
                    feedBatchLimit = feedBatchLimit,
                )
            val filtered = filterCommentEntries(comments.entries, author)
            ReviewCommentsResponse(
                review = review,
                feedChannelId = comments.feedChannelId,
                authorFilter = author,
                count = filtered.size,
                comments = filtered,
            )
        }

    suspend fun postReviewComment(
        projectKey: String,
        reviewRef: String,
        text: String,
        pending: Boolean,
    ): SentMessageResult =
        withAuthorizedCredentials { credentials ->
            sendChannelMessage(
                credentials = credentials,
                channelId = fetchReviewFeedChannelId(credentials, projectKey, reviewRef),
                text = text,
                pending = pending,
            )
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
    ): DiscussionCreationResult =
        withAuthorizedCredentials { credentials ->
            val response =
                postJson<GenericApiRecord>(
                    credentials = credentials,
                    path = listOf("projects", "key:$projectKey", "code-reviews", "code-discussions"),
                    body =
                        createDiscussionRequestBody(
                            reviewRef = reviewRef,
                            repository = repository,
                            revision = revision,
                            filename = filename,
                            line = line,
                            oldLine = oldLine,
                            endLine = endLine,
                            endOldLine = endOldLine,
                            text = text,
                            pending = pending,
                        ),
                )

            DiscussionCreationResult(
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
    ): SentMessageResult =
        withAuthorizedCredentials { credentials ->
            sendChannelMessage(credentials, channelId, text, pending)
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

        val threads =
            grouped.values.map { messages ->
                val first = messages.first()
                val discussion = first.details?.codeDiscussion
                val channelId = discussion?.channel?.id
                val threadMessages =
                    if (channelId != null && discussionReplyLimit > 0) {
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

        val mappedFeedMessages =
            feedMessages.map {
                FeedMessage(
                    id = it.id,
                    text = it.text,
                    created = it.created,
                    author = it.author ?: it.projectedItem?.author,
                    details = it.details,
                )
            }

        return ReviewCommentBundle(
            feedChannelId = feedChannelId,
            feedMessages = mappedFeedMessages,
            codeDiscussions = threads,
            entries = buildCommentEntries(mappedFeedMessages, threads),
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
            val query =
                mapOf(
                    "batchInfo" to "{etag:$etag,batchSize:$batchSize}",
                    "channel" to "id:$channelId",
                )
            val batch =
                get<SyncBatchResponse>(
                    credentials = credentials,
                    path = listOf("chats", "messages", "sync-batch"),
                    query = query,
                    fields =
                        "data(chatMessage(id,text,created,author(name,details(className,user(id)))," +
                            "details(className,codeDiscussion(id,channel(id),anchor(filename,line,oldLine,revision)))," +
                            "projectedItem(author(name,details(className,user(id)))))),etag,hasMore",
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
    ): List<ChannelMessage> =
        get<MessagesResponse>(
            credentials = credentials,
            path = listOf("chats", "messages"),
            query =
                mapOf(
                    "channel" to "id:$channelId",
                    "sorting" to "FromOldestToNewest",
                    "batchSize" to batchSize.toString(),
                ),
            fields = "messages(id,text,author(name,details(className,user(id))),created)",
        ).messages

    private suspend fun sendChannelMessage(
        credentials: StoredCredentials,
        channelId: String,
        text: String,
        pending: Boolean,
    ): SentMessageResult {
        val response =
            postJson<GenericChatMessageRecord>(
                credentials = credentials,
                path = listOf("chats", "messages", "send"),
                body =
                    buildJsonObject {
                        put("channel", JsonPrimitive(channelId))
                        put("text", JsonPrimitive(text))
                        put("pending", JsonPrimitive(pending))
                    },
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
    ): T =
        requestJson(credentials, path, query, fields) { url ->
            get(url) {
                authorize(credentials)
                header(HttpHeaders.Accept, ContentType.Application.Json)
            }
        }

    private suspend inline fun <reified T> postJson(
        credentials: StoredCredentials,
        path: List<String>,
        body: JsonObject,
    ): T =
        requestJson(credentials, path) { url ->
            post(url) {
                authorize(credentials)
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }

    private fun httpClient(): HttpClient =
        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(json)
            }
            expectSuccess = false
        }

    private suspend fun authorizedCredentials(): StoredCredentials {
        val stored = credentialStore.load()
        val envCredentials = environmentCredentials(stored)
        if (envCredentials != null) {
            return envCredentials
        }

        val persisted =
            stored
                ?: throw AuthorizationRequiredException(
                    "Space credentials are not configured. Run the space_authorize tool first or set SPACE_ACCESS_TOKEN.",
                )

        val accessToken =
            persisted.accessToken
                ?: throw AuthorizationRequiredException(
                    "No Space access token is stored. Run the space_authorize tool first or set SPACE_ACCESS_TOKEN.",
                )

        val expiresAt = persisted.expiresAtEpochSeconds
        val isExpired = expiresAt != null && Instant.now().epochSecond >= (expiresAt - 30)
        if (!isExpired) {
            return persisted.copy(accessToken = accessToken)
        }

        val refreshed = refreshAccessToken(persisted)
        credentialStore.save(refreshed)
        return refreshed
    }

    private suspend inline fun <T> withAuthorizedCredentials(crossinline block: suspend (StoredCredentials) -> T): T =
        block(authorizedCredentials())

    private suspend fun loadTargetProjects(
        projectKey: String?,
        maxProjects: Int,
    ): BatchResponse<ProjectSummary> =
        if (projectKey != null) {
            singleProjectBatch(projectKey)
        } else {
            listProjects(
                term = null,
                limit = maxProjects.coerceAtLeast(1),
                offset = 0,
            )
        }

    private fun singleProjectBatch(projectKey: String): BatchResponse<ProjectSummary> =
        BatchResponse(
            data = listOf(ProjectSummary(id = projectKey, key = projectKey, name = projectKey)),
            totalCount = 1,
        )

    private fun mergeReviewBatch(
        aggregated: LinkedHashMap<String, MutableCrossProjectReview>,
        project: ProjectSummary,
        matchedRole: String,
        batch: BatchResponse<ReviewListItemResponse>,
    ) {
        for (item in batch.data) {
            val key = "${project.key}:${item.review.id}"
            val existing = aggregated[key]
            if (existing == null) {
                aggregated[key] =
                    MutableCrossProjectReview(
                        projectKey = project.key,
                        projectName = project.name,
                        matchedRoles = linkedSetOf(matchedRole),
                        review = item.review,
                    )
            } else {
                existing.matchedRoles += matchedRole
            }
        }
    }

    private fun buildMyReviewsResponse(
        aggregated: Map<String, MutableCrossProjectReview>,
        totalProjects: Int,
        scannedProjects: Int,
        projectScoped: Boolean,
        limit: Int,
    ): MyReviewsResponse {
        val reviews =
            aggregated.values
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

        return MyReviewsResponse(
            reviews = reviews,
            scannedProjects = scannedProjects,
            projectScanTruncated = !projectScoped && totalProjects > scannedProjects,
            requestedLimit = limit,
        )
    }

    private suspend fun fetchReviewSummary(
        credentials: StoredCredentials,
        projectKey: String,
        reviewRef: String,
    ): ReviewSummary =
        get<ReviewSummary>(
            credentials = credentials,
            path = listOf("projects", "key:$projectKey", "code-reviews", normalizeReviewIdentifier(reviewRef)),
            fields = REVIEW_SUMMARY_FIELDS,
        ).normalized()

    private suspend fun fetchReviewCommits(
        credentials: StoredCredentials,
        projectKey: String,
        reviewRef: String,
    ): List<ReviewCommitInReview> =
        get<RawReviewDetailsResponse>(
            credentials = credentials,
            path = listOf("projects", "key:$projectKey", "code-reviews", normalizeReviewIdentifier(reviewRef), "details"),
        ).normalizedCommits()

    private suspend fun fetchReviewFeedChannelId(
        credentials: StoredCredentials,
        projectKey: String,
        reviewRef: String,
    ): String = requireFeedChannelId(fetchReviewSummary(credentials, projectKey, reviewRef))

    private fun requireFeedChannelId(review: ReviewSummary): String =
        review.feedChannelId
            ?: throw IllegalStateException("Review ${review.number} has no feed channel id.")

    private fun createDiscussionRequestBody(
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
    ): JsonObject =
        buildJsonObject {
            put("text", JsonPrimitive(text))
            put("repository", JsonPrimitive(repository))
            put("reviewId", JsonPrimitive(normalizeReviewIdentifier(reviewRef)))
            put("pending", JsonPrimitive(pending))
            put("anchor", buildAnchor(revision, filename, line, oldLine))
            if (endLine != null || endOldLine != null) {
                put("endAnchor", buildAnchor(revision, filename, endLine, endOldLine))
            }
        }

    private fun buildCommentEntries(
        feedMessages: List<FeedMessage>,
        threads: List<CodeDiscussionThread>,
    ): List<ReviewCommentEntry> {
        val directFeedComments =
            feedMessages.mapNotNull { message ->
                val author = message.author ?: return@mapNotNull null
                val kind = message.details?.className
                if (kind != "M2TextItemContent" || message.text.isNullOrBlank() || !author.isUser()) {
                    return@mapNotNull null
                }
                ReviewCommentEntry(
                    id = message.id,
                    kind = "review-feed",
                    text = message.text,
                    created = message.created,
                    author = author,
                )
            }

        val discussionComments =
            threads.flatMap { thread ->
                thread.messages.mapNotNull { message ->
                    if (message.text.isNullOrBlank() || message.author?.isUser() != true) {
                        return@mapNotNull null
                    }
                    ReviewCommentEntry(
                        id = message.id,
                        kind = "code-discussion",
                        text = message.text,
                        created = message.created,
                        author = message.author,
                        discussionId = thread.id,
                        channelId = thread.channelId,
                        anchor = thread.anchor,
                        feedMessageId = thread.feedMessageId,
                    )
                }
            }

        return (directFeedComments + discussionComments)
            .sortedBy { it.created?.timestamp ?: Long.MIN_VALUE }
    }

    private fun filterCommentEntries(
        entries: List<ReviewCommentEntry>,
        author: String?,
    ): List<ReviewCommentEntry> {
        val filter = author?.trim()?.takeIf { it.isNotEmpty() } ?: return entries
        return entries.filter { it.author?.matches(filter) == true }
    }

    private fun buildAnchor(
        revision: String,
        filename: String,
        line: Int?,
        oldLine: Int?,
    ): JsonObject =
        buildJsonObject {
            put("revision", JsonPrimitive(revision))
            put("filename", JsonPrimitive(filename))
            line?.let { put("line", JsonPrimitive(it)) }
            oldLine?.let { put("oldLine", JsonPrimitive(it)) }
        }

    private suspend inline fun <reified T> requestJson(
        credentials: StoredCredentials,
        path: List<String>,
        query: Map<String, String?> = emptyMap(),
        fields: String? = null,
        request: HttpClient.(String) -> HttpResponse,
    ): T {
        val client = httpClient()
        client.use {
            val url = buildApiUrl(credentials.apiBaseUrl, path, query, fields)
            return decodeResponse(url, client.request(url))
        }
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
                    if (!value.isNullOrBlank()) parameters.append(key, value)
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

    private fun HttpRequestBuilder.authorize(credentials: StoredCredentials) {
        header(HttpHeaders.Authorization, "Bearer ${credentials.accessToken}")
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

    private fun roleFilters(role: String): List<MyReviewFilter> =
        when (role.trim().lowercase()) {
            "authored", "author", "created" ->
                listOf(
                    MyReviewFilter(
                        author = "me",
                        reviewer = null,
                        matchedRole = "author",
                    ),
                )

            "reviewing", "reviewer", "assigned" ->
                listOf(
                    MyReviewFilter(
                        author = null,
                        reviewer = "me",
                        matchedRole = "reviewer",
                    ),
                )

            else ->
                listOf(
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

    private fun reviewQuery(
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
    ): Map<String, String?> =
        mapOf(
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
        )

    private companion object {
        const val REVIEW_LIST_FIELDS =
            "data(review(className,id,key,number,title,state,createdAt,timestamp,feedChannelId,branchPair(repository,sourceBranchRef,sourceBranchInfo(displayName,ref,deleted,head),targetBranchInfo(displayName,ref,deleted,head),isMerged,isStale),author(id,username,name(firstName,lastName)),createdBy(id,username,name(firstName,lastName)),participants(role,profile(id,username,name(firstName,lastName))),reviewers(reviewer(id,username,name(firstName,lastName))))),totalCount,hasMore,next"
        const val REVIEW_SUMMARY_FIELDS =
            "className,id,key,number,title,state,createdAt,timestamp,feedChannelId,branchPair(repository,sourceBranchRef,sourceBranchInfo(displayName,ref,deleted,head),targetBranchInfo(displayName,ref,deleted,head),isMerged,isStale),author(id,username,name(firstName,lastName)),createdBy(id,username,name(firstName,lastName)),participants(role,profile(id,username,name(firstName,lastName))),reviewers(reviewer(id,username,name(firstName,lastName)))"
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
