package team.jetbrains.mcp.space

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.SpaceAuth
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.configureKtorClientForSpace

class SpaceApiClient(
    private val credentialStore: SpaceCredentialStore,
    private val httpClientFactory: (Json) -> HttpClient = ::defaultHttpClient,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            prettyPrint = true
        }

    private val sdkFacade = SpaceSdkFacade()
    private val httpFallbacks = SpaceHttpFallbacks(json)

    suspend fun getCurrentUser(): SpaceProfile =
        withSession { session ->
            sdkFacade.getCurrentUser(session)
        }

    suspend fun listProjects(
        term: String?,
        limit: Int,
        offset: Int,
    ): BatchResponse<ProjectSummary> =
        withSession { session ->
            sdkFacade.listProjects(session, term, limit, offset)
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
        withSession { session ->
            listReviews(session, projectKey, repository, state, type, text, author, reviewer, from, to, sort, limit, offset)
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
    ): MyReviewsResponse =
        withSession { session ->
            val targetProjects =
                if (projectKey != null) {
                    singleProjectBatch(projectKey)
                } else {
                    sdkFacade.listProjects(
                        session = session,
                        term = null,
                        limit = maxProjects.coerceAtLeast(1),
                        offset = 0,
                    )
                }

            val aggregated = linkedMapOf<String, MutableCrossProjectReview>()
            var scannedProjects = 0
            val reviewFilters = roleFilters(role)
            val perProjectReviewLimit = perProjectLimit.coerceAtLeast(1)

            projectLoop@ for (project in targetProjects.data) {
                scannedProjects += 1

                for (filter in reviewFilters) {
                    val batch =
                        listReviews(
                            session = session,
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

            buildMyReviewsResponse(
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
        withSession { session ->
            val review = fetchReviewSummary(session, projectKey, reviewRef)
            ReviewDetailsBundle(
                review = review,
                commits = if (includeCommits) fetchReviewCommits(session, projectKey, reviewRef) else emptyList(),
                comments =
                    if (includeComments) {
                        sdkFacade.loadComments(
                            session = session,
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
        withSession { session ->
            val review = fetchReviewSummary(session, projectKey, reviewRef)
            val comments =
                sdkFacade.loadComments(
                    session = session,
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
        withSession { session ->
            sdkFacade.sendChannelMessage(
                session = session,
                channelId = fetchReviewFeedChannelId(session, projectKey, reviewRef),
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
        withSession { session ->
            sdkFacade.createCodeDiscussion(
                session = session,
                projectKey = projectKey,
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
            )
        }

    suspend fun replyToDiscussion(
        channelId: String,
        text: String,
        pending: Boolean,
    ): SentMessageResult =
        withSession { session ->
            sdkFacade.sendChannelMessage(
                session = session,
                channelId = channelId,
                text = text,
                pending = pending,
            )
        }

    private suspend fun listReviews(
        session: SpaceSession,
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
        sdkFacade.listReviews(
            session = session,
            projectKey = projectKey,
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
        )

    private suspend fun fetchReviewSummary(
        session: SpaceSession,
        projectKey: String,
        reviewRef: String,
    ): ReviewSummary = sdkFacade.getReviewSummary(session, projectKey, reviewRef)

    private suspend fun fetchReviewCommits(
        session: SpaceSession,
        projectKey: String,
        reviewRef: String,
    ): List<ReviewCommitInReview> =
        try {
            sdkFacade.getReviewCommits(session, projectKey, reviewRef)
        } catch (_: Exception) {
            httpFallbacks.getReviewCommits(session, projectKey, reviewRef)
        }

    private suspend fun fetchReviewFeedChannelId(
        session: SpaceSession,
        projectKey: String,
        reviewRef: String,
    ): String = requireFeedChannelId(fetchReviewSummary(session, projectKey, reviewRef))

    private fun requireFeedChannelId(review: ReviewSummary): String =
        review.feedChannelId
            ?: throw IllegalStateException("Review ${review.number} has no feed channel id.")

    private fun filterCommentEntries(
        entries: List<ReviewCommentEntry>,
        author: String?,
    ): List<ReviewCommentEntry> {
        val filter = author?.trim()?.takeIf { it.isNotEmpty() } ?: return entries
        return entries.filter { it.author?.matches(filter) == true }
    }

    private suspend inline fun <T> withSession(crossinline block: suspend (SpaceSession) -> T): T {
        val credentials =
            resolveConfiguredCredentials(credentialStore)
                ?: throw AuthorizationRequiredException(
                    "Space credentials are not configured. Run the space_authorize tool first or set SPACE_ACCESS_TOKEN.",
                )

        val client = httpClientFactory(json)
        val session =
            try {
                SpaceSession(
                    credentials = credentials,
                    httpClient = client,
                    sdkClient =
                        SpaceClient(
                            ktorClient = client,
                            appInstance = createAppInstance(credentials),
                            auth = createSpaceAuth(credentials),
                        ),
                )
            } catch (e: Throwable) {
                client.close()
                throw e
            }

        session.use {
            return block(it)
        }
    }

    private fun createAppInstance(credentials: StoredCredentials): SpaceAppInstance =
        when {
            credentials.clientId != null && credentials.clientSecret != null ->
                SpaceAppInstance(credentials.clientId, credentials.clientSecret, credentials.serverUrl)

            credentials.clientId != null ->
                SpaceAppInstance.withoutSecret(credentials.clientId, credentials.serverUrl)

            else ->
                @Suppress("DEPRECATION")
                SpaceAppInstance.withoutCredentials(credentials.serverUrl)
        }

    private fun createSpaceAuth(credentials: StoredCredentials): SpaceAuth {
        val accessToken =
            credentials.accessToken
                ?: throw AuthorizationRequiredException(
                    "No Space access token is configured. Run the space_authorize tool first or set SPACE_ACCESS_TOKEN.",
                )

        if (!System.getenv("SPACE_ACCESS_TOKEN").isNullOrBlank()) {
            return SpaceAuth.Token(accessToken)
        }

        if (credentials.refreshToken != null && credentials.clientId != null) {
            return PersistingSpaceAuth(credentials, credentialStore)
        }

        val expires = credentials.expiresAtEpochSeconds?.let(Instant::fromEpochSeconds)
        if (expires != null && expires.epochSeconds <= Clock.System.now().epochSeconds) {
            throw AuthorizationRequiredException(
                "The stored Space access token has expired. Run the space_authorize tool again or set SPACE_ACCESS_TOKEN.",
            )
        }
        return SpaceAuth.Token(accessToken, expires = expires)
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

    private companion object {
        fun defaultHttpClient(json: Json): HttpClient =
            HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(json)
                }
                configureKtorClientForSpace()
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
