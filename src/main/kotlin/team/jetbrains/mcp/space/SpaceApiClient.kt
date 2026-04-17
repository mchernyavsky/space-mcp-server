package team.jetbrains.mcp.space

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import space.jetbrains.api.runtime.Batch
import space.jetbrains.api.runtime.BatchInfo
import space.jetbrains.api.runtime.PermissionScope
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.SpaceAuth
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.SpaceTokenInfo
import space.jetbrains.api.runtime.configureKtorClientForSpace
import space.jetbrains.api.runtime.resources.chats
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.resources.teamDirectory
import space.jetbrains.api.runtime.types.CPrincipal
import space.jetbrains.api.runtime.types.CPrincipalDetails
import space.jetbrains.api.runtime.types.CUserPrincipalDetails
import space.jetbrains.api.runtime.types.CUserWithEmailPrincipalDetails
import space.jetbrains.api.runtime.types.ChangeInReview
import space.jetbrains.api.runtime.types.ChannelIdentifier
import space.jetbrains.api.runtime.types.ChannelItemRecord
import space.jetbrains.api.runtime.types.ChatMessage
import space.jetbrains.api.runtime.types.CodeDiscussionAnchor
import space.jetbrains.api.runtime.types.CodeDiscussionRecord
import space.jetbrains.api.runtime.types.CodeReviewParticipant
import space.jetbrains.api.runtime.types.CodeReviewRecord
import space.jetbrains.api.runtime.types.CodeReviewStateFilter
import space.jetbrains.api.runtime.types.CodeReviewWithCount
import space.jetbrains.api.runtime.types.CommitSetReviewRecord
import space.jetbrains.api.runtime.types.GitAuthorInfo
import space.jetbrains.api.runtime.types.GitCommitChange
import space.jetbrains.api.runtime.types.GitCommitInfo
import space.jetbrains.api.runtime.types.GitDiffSize
import space.jetbrains.api.runtime.types.GitFileProperties
import space.jetbrains.api.runtime.types.GitMergedFile
import space.jetbrains.api.runtime.types.LocalCodeDiscussionAnchorIn
import space.jetbrains.api.runtime.types.M2ItemContentDetails
import space.jetbrains.api.runtime.types.MergeRequestBranch
import space.jetbrains.api.runtime.types.MergeRequestBranchPair
import space.jetbrains.api.runtime.types.MergeRequestRecord
import space.jetbrains.api.runtime.types.MessagesSorting
import space.jetbrains.api.runtime.types.PR_Project
import space.jetbrains.api.runtime.types.ProfileIdentifier
import space.jetbrains.api.runtime.types.ProjectIdentifier
import space.jetbrains.api.runtime.types.ReviewIdentifier
import space.jetbrains.api.runtime.types.ReviewSorting
import space.jetbrains.api.runtime.types.ReviewType
import space.jetbrains.api.runtime.types.RevisionsInReview
import space.jetbrains.api.runtime.types.SyncBatchInfo
import space.jetbrains.api.runtime.types.TD_MemberProfile
import space.jetbrains.api.runtime.types.TD_ProfileName

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

    suspend fun getCurrentUser(): SpaceProfile =
        withSession { session ->
            getCurrentUser(session)
        }

    suspend fun listProjects(
        term: String?,
        limit: Int,
        offset: Int,
    ): BatchResponse<ProjectSummary> =
        withSession { session ->
            listProjectsViaSdk(session, term, limit, offset)
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
            listReviewsViaSdk(session, projectKey, repository, state, type, text, author, reviewer, from, to, sort, limit, offset)
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
                    listProjectsViaSdk(
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
                        listReviewsViaSdk(
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
        includeChanges: Boolean,
        includeComments: Boolean,
        changeLimit: Int,
        changeOffset: Int,
        discussionReplyLimit: Int,
        feedBatchSize: Int,
        feedBatchLimit: Int,
    ): ReviewDetailsBundle =
        withSession { session ->
            val review = fetchReviewSummary(session, projectKey, reviewRef)
            ReviewDetailsBundle(
                review = review,
                commits = if (includeCommits) fetchReviewCommits(session, projectKey, reviewRef) else emptyList(),
                changes =
                    if (includeChanges) {
                        listReviewChanges(
                            session = session,
                            projectKey = projectKey,
                            reviewRef = reviewRef,
                            review = review,
                            limit = changeLimit,
                            offset = changeOffset,
                        )
                    } else {
                        null
                    },
                comments =
                    if (includeComments) {
                        loadComments(
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

    suspend fun listReviewChanges(
        projectKey: String,
        reviewRef: String,
        limit: Int,
        offset: Int,
    ): ReviewChangesResponse =
        withSession { session ->
            val review = fetchReviewSummary(session, projectKey, reviewRef)
            val page =
                listReviewChanges(
                    session = session,
                    projectKey = projectKey,
                    reviewRef = reviewRef,
                    review = review,
                    limit = limit,
                    offset = offset,
                )
            ReviewChangesResponse(
                review = review,
                scope = page.scope,
                count = page.count,
                totalCount = page.totalCount,
                hasMore = page.hasMore,
                next = page.next,
                changes = page.changes,
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
                loadComments(
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
            sendChannelMessage(
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
            createCodeDiscussion(
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
            sendChannelMessage(
                session = session,
                channelId = channelId,
                text = text,
                pending = pending,
            )
        }

    private suspend fun listReviewsViaSdk(
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
        session.sdkClient.projects.codeReviews
            .getAllCodeReviews(
                project = ProjectIdentifier.Key(projectKey),
                state = state.toCodeReviewStateFilter(),
                text = text,
                author = author.toProfileIdentifier(),
                from = from.toLocalDate(),
                to = to.toLocalDate(),
                sort = sort.toReviewSorting(),
                reviewer = reviewer.toProfileIdentifier(),
                type = type.toReviewType(),
                repository = repository,
                batchInfo = BatchInfo(offset = offset.toString(), batchSize = limit),
            ) {
                review {
                    summaryFields()
                }
            }.toReviewBatchResponse()

    private suspend fun fetchReviewSummary(
        session: SpaceSession,
        projectKey: String,
        reviewRef: String,
    ): ReviewSummary = getReviewSummary(session, projectKey, reviewRef)

    private suspend fun fetchReviewCommits(
        session: SpaceSession,
        projectKey: String,
        reviewRef: String,
    ): List<ReviewCommitInReview> = getReviewCommits(session, projectKey, reviewRef)

    private suspend fun getCurrentUser(session: SpaceSession): SpaceProfile =
        session.sdkClient.teamDirectory.profiles
            .getProfile(ProfileIdentifier.Me) {
                id()
                username()
                name {
                    firstName()
                    lastName()
                }
            }.toSpaceProfile()

    private suspend fun listProjectsViaSdk(
        session: SpaceSession,
        term: String?,
        limit: Int,
        offset: Int,
    ): BatchResponse<ProjectSummary> =
        session.sdkClient.projects
            .getAllProjects(
                term = term,
                batchInfo = BatchInfo(offset = offset.toString(), batchSize = limit),
            ) {
                id()
                key { key() }
                name()
                private()
            }.toProjectBatchResponse()

    private suspend fun getReviewSummary(
        session: SpaceSession,
        projectKey: String,
        reviewRef: String,
    ): ReviewSummary =
        session.sdkClient.projects.codeReviews
            .getCodeReview(
                project = ProjectIdentifier.Key(projectKey),
                reviewId = reviewRef.toReviewIdentifier(),
            ) {
                summaryFields()
            }?.toReviewSummary()
            ?.normalized()
            ?: throw IllegalStateException("Review $reviewRef was not found in project $projectKey.")

    private suspend fun getReviewCommits(
        session: SpaceSession,
        projectKey: String,
        reviewRef: String,
    ): List<ReviewCommitInReview> =
        session.sdkClient.projects.codeReviews
            .getReviewDetails(
                project = ProjectIdentifier.Key(projectKey),
                reviewId = reviewRef.toReviewIdentifier(),
            ) {
                shortInfo {
                    id()
                }
                commits {
                    repository {
                        name()
                        deleted()
                    }
                    commits {
                        repositoryName()
                        commit {
                            id()
                            message()
                            authorDate()
                            commitDate()
                            author {
                                name()
                                email()
                            }
                        }
                    }
                }
            }.commits
            .toReviewCommits()

    private suspend fun listReviewChanges(
        session: SpaceSession,
        projectKey: String,
        reviewRef: String,
        review: ReviewSummary,
        limit: Int,
        offset: Int,
    ): ReviewChangesPage {
        val batchInfo = BatchInfo(offset = offset.toString(), batchSize = limit)
        return when (review.className) {
            "MergeRequestRecord" ->
                session.sdkClient.projects.codeReviews
                    .getTheMergeRequestFiles(
                        project = ProjectIdentifier.Key(projectKey),
                        reviewId = reviewRef.toReviewIdentifier(),
                        batchInfo = batchInfo,
                    ) {
                        name()
                        oldName()
                        baseId()
                        sourceId()
                        targetId()
                        diffSize {
                            added()
                            deleted()
                        }
                        entryType()
                        conflicting()
                        properties {
                            lfs()
                            executable()
                        }
                    }.toMergeRequestChangesPage(review)

            else ->
                session.sdkClient.projects.codeReviews
                    .getTheModifiedFilesInCodeReview(
                        project = ProjectIdentifier.Key(projectKey),
                        reviewId = reviewRef.toReviewIdentifier(),
                        batchInfo = batchInfo,
                    ) {
                        repository()
                        change {
                            changeType()
                            revision()
                            diffSize {
                                added()
                                deleted()
                            }
                            path()
                            detached()
                            constituentCommits()
                            old {
                                path()
                                blob()
                                type()
                                properties {
                                    lfs()
                                    executable()
                                }
                            }
                            new {
                                path()
                                blob()
                                type()
                                properties {
                                    lfs()
                                    executable()
                                }
                            }
                        }
                        read()
                    }.toCodeReviewChangesPage()
        }
    }

    private suspend fun loadComments(
        session: SpaceSession,
        feedChannelId: String,
        discussionReplyLimit: Int,
        feedBatchSize: Int,
        feedBatchLimit: Int,
    ): ReviewCommentBundle {
        val feedMessages = fetchFeedMessages(session, feedChannelId, feedBatchSize, feedBatchLimit)
        val grouped = linkedMapOf<String, MutableList<FeedMessage>>()

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
                        fetchChannelMessages(session, channelId, discussionReplyLimit)
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
            feedMessages = feedMessages,
            codeDiscussions = threads,
            entries = buildCommentEntries(feedMessages, threads),
        )
    }

    private suspend fun sendChannelMessage(
        session: SpaceSession,
        channelId: String,
        text: String,
        pending: Boolean,
    ): SentMessageResult {
        val message =
            session.sdkClient.chats.messages.sendMessage(
                channel = ChannelIdentifier.Id(channelId),
                content = ChatMessage.Text(text),
                pending = pending,
            ) {
                id()
            }

        return SentMessageResult(
            id = message.id,
            channelId = channelId,
        )
    }

    private suspend fun createCodeDiscussion(
        session: SpaceSession,
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
        session.sdkClient.projects.codeReviews.codeDiscussions
            .createCodeDiscussion(
                project = ProjectIdentifier.Key(projectKey),
                text = text,
                repository = repository,
                revision = revision,
                filename = filename,
                line = line,
                oldLine = oldLine,
                anchor = LocalCodeDiscussionAnchorIn(revision, filename, line, oldLine),
                endAnchor = endLine?.let { LocalCodeDiscussionAnchorIn(revision, filename, it, endOldLine) },
                pending = pending,
                reviewId = reviewRef.toReviewIdentifier(),
            ) {
                id()
                channel { id() }
                pending()
                feedItemId()
                anchor {
                    filename()
                    line()
                    oldLine()
                    revision()
                }
            }.toDiscussionCreationResult()

    private suspend fun fetchReviewFeedChannelId(
        session: SpaceSession,
        projectKey: String,
        reviewRef: String,
    ): String = requireFeedChannelId(fetchReviewSummary(session, projectKey, reviewRef))

    private suspend fun fetchFeedMessages(
        session: SpaceSession,
        channelId: String,
        batchSize: Int,
        batchLimit: Int,
    ): List<FeedMessage> {
        val messages = mutableListOf<FeedMessage>()
        var etag = "0"

        repeat(batchLimit.coerceAtLeast(1)) {
            val batch =
                session.sdkClient.chats.messages.syncBatch.getSyncBatch(
                    batchInfo = SyncBatchInfo.SinceEtag(etag = etag, batchSize = batchSize),
                    channel = ChannelIdentifier.Id(channelId),
                ) {
                    chatMessage {
                        id()
                        text()
                        created()
                        author {
                            name()
                            details {
                                user {
                                    id()
                                }
                            }
                        }
                        details {
                            markdown()
                            codeDiscussion {
                                id()
                                channel { id() }
                                anchor {
                                    filename()
                                    line()
                                    oldLine()
                                    revision()
                                }
                            }
                        }
                    }
                }

            messages += batch.data.map { it.chatMessage.toFeedMessage() }
            if (!batch.hasMore || batch.etag.isBlank()) {
                return messages
            }
            etag = batch.etag
        }

        return messages
    }

    private suspend fun fetchChannelMessages(
        session: SpaceSession,
        channelId: String,
        batchSize: Int,
    ): List<ChannelMessage> =
        session.sdkClient.chats.messages
            .getChannelMessages(
                channel = ChannelIdentifier.Id(channelId),
                sorting = MessagesSorting.FromOldestToNewest,
                batchSize = batchSize,
            ) {
                messages {
                    id()
                    text()
                    created()
                    author {
                        name()
                        details {
                            user {
                                id()
                            }
                        }
                    }
                }
            }.messages
            .map { it.toChannelMessage() }

    private fun requireFeedChannelId(review: ReviewSummary): String =
        review.feedChannelId
            ?: throw IllegalStateException("Review ${review.number} has no feed channel id.")

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

    private fun String?.toCodeReviewStateFilter(): CodeReviewStateFilter? = parseEnum(this)

    private fun String?.toReviewType(): ReviewType? = parseEnum(this)

    private fun String.toReviewSorting(): ReviewSorting =
        parseEnum(this) ?: throw IllegalArgumentException("Unsupported review sort: $this")

    private fun String?.toLocalDate(): LocalDate? = this?.trim()?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)

    private fun String?.toProfileIdentifier(): ProfileIdentifier? {
        val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            value.equals("me", ignoreCase = true) -> ProfileIdentifier.Me
            value.startsWith("id:") -> ProfileIdentifier.Id(value.removePrefix("id:"))
            value.startsWith("username:") -> ProfileIdentifier.Username(value.removePrefix("username:"))
            else -> ProfileIdentifier.Username(value)
        }
    }

    private fun String.toReviewIdentifier(): ReviewIdentifier {
        val trimmed = trim()
        return when {
            trimmed.startsWith("id:") -> ReviewIdentifier.Id(trimmed.removePrefix("id:"))
            trimmed.startsWith("key:") -> ReviewIdentifier.Key(trimmed.removePrefix("key:"))
            trimmed.startsWith("number:") -> ReviewIdentifier.Number(trimmed.removePrefix("number:").toInt())
            trimmed.toIntOrNull() != null -> ReviewIdentifier.Number(trimmed.toInt())
            else -> ReviewIdentifier.Id(trimmed)
        }
    }

    private inline fun <reified T : Enum<T>> parseEnum(value: String?): T? {
        val normalized = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return enumValues<T>().firstOrNull { it.name.equals(normalized, ignoreCase = true) }
            ?: throw IllegalArgumentException("Unsupported value '$normalized' for ${T::class.java.simpleName}.")
    }

    private fun space.jetbrains.api.runtime.types.partials.CodeReviewRecordPartial.summaryFields() {
        id()
        project { key() }
        number()
        title()
        state()
        createdAt()
        timestamp()
        feedChannelId()
        createdBy {
            id()
            username()
            name {
                firstName()
                lastName()
            }
        }
        participants {
            role()
            user {
                id()
                username()
                name {
                    firstName()
                    lastName()
                }
            }
        }
        branchPairs {
            repository()
            sourceBranch()
            targetBranch()
            sourceBranchRef()
            sourceBranchInfo {
                displayName()
                ref()
                deleted()
                head()
            }
            targetBranchInfo {
                displayName()
                ref()
                deleted()
                head()
            }
            isMerged()
            isStale()
        }
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

internal class SpaceSession(
    val credentials: StoredCredentials,
    val httpClient: HttpClient,
    val sdkClient: SpaceClient,
) : AutoCloseable {
    override fun close() {
        sdkClient.close()
        httpClient.close()
    }
}

internal class PersistingSpaceAuth(
    private var currentCredentials: StoredCredentials,
    private val credentialStore: SpaceCredentialStore,
) : SpaceAuth {
    override suspend fun token(
        client: HttpClient,
        appInstance: SpaceAppInstance,
    ): SpaceTokenInfo {
        val currentToken = currentCredentials.toSpaceTokenInfo()
        if (currentToken != null && !currentToken.isExpired()) {
            return currentToken
        }

        val refreshToken =
            currentCredentials.refreshToken
                ?: throw AuthorizationRequiredException(
                    "Space access token has expired and no refresh token is stored. Run the space_authorize tool again or set SPACE_ACCESS_TOKEN.",
                )

        val refreshed =
            SpaceAuth
                .RefreshToken(
                    refreshToken = refreshToken,
                    scope = PermissionScope.fromString(currentCredentials.scope),
                ).token(client, appInstance)

        val updated =
            currentCredentials.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken ?: refreshToken,
                expiresAtEpochSeconds = refreshed.expires?.epochSeconds,
            )
        currentCredentials = updated
        credentialStore.save(updated)
        return refreshed.copy(refreshToken = updated.refreshToken)
    }

    private fun StoredCredentials.toSpaceTokenInfo(): SpaceTokenInfo? {
        val accessToken = accessToken ?: return null
        val expires = expiresAtEpochSeconds?.let(Instant::fromEpochSeconds)
        val refreshToken = refreshToken
        return SpaceTokenInfo(
            accessToken = accessToken,
            expires = expires,
            refreshToken = refreshToken,
        )
    }

    private fun SpaceTokenInfo.isExpired(gapMillis: Long = 5_000): Boolean {
        val expires = expires ?: return false
        return expires.epochMilliseconds() <= Clock.System.now().epochMilliseconds() + gapMillis
    }

    private fun Instant.epochMilliseconds(): Long = epochSeconds * 1_000 + nanosecondsOfSecond / 1_000_000
}

private fun Batch<PR_Project>.toProjectBatchResponse(): BatchResponse<ProjectSummary> =
    BatchResponse(
        data = data.map { it.toProjectSummary() },
        totalCount = totalCount,
        next = next.takeIf { it.isNotBlank() },
    )

private fun Batch<CodeReviewWithCount>.toReviewBatchResponse(): BatchResponse<ReviewListItemResponse> =
    BatchResponse(
        data = data.map { ReviewListItemResponse(review = it.review.toReviewSummary()) },
        totalCount = totalCount,
        next = next.takeIf { it.isNotBlank() },
    )

private fun PR_Project.toProjectSummary(): ProjectSummary =
    ProjectSummary(
        id = id,
        key = key.key,
        name = name,
        private = private,
    )

private fun CodeReviewRecord.toReviewSummary(): ReviewSummary {
    val values =
        when (this) {
            is MergeRequestRecord ->
                ReviewSummaryValues(
                    projectKey = project.key,
                    number = number,
                    title = title,
                    state = state.name,
                    createdAt = createdAt,
                    timestamp = timestamp,
                    feedChannelId = feedChannelId,
                    createdBy = createdBy?.toSpaceProfile(),
                    participants = participants.orEmpty().map { it.toReviewParticipant() },
                    reviewers = participants.orEmpty().toReviewReviewers(),
                    branchPair = branchPairs.firstOrNull()?.toBranchPair(),
                )

            is CommitSetReviewRecord ->
                ReviewSummaryValues(
                    projectKey = project.key,
                    number = number,
                    title = title,
                    state = state.name,
                    createdAt = createdAt,
                    timestamp = timestamp,
                    feedChannelId = feedChannelId,
                    createdBy = createdBy?.toSpaceProfile(),
                    participants = participants.orEmpty().map { it.toReviewParticipant() },
                    reviewers = participants.orEmpty().toReviewReviewers(),
                    branchPair = null,
                )

            else ->
                throw IllegalStateException("Unsupported code review type: ${this::class.qualifiedName}")
        }

    val resolvedAuthor =
        values.createdBy
            ?: values.participants.firstOrNull { it.role.equals("Author", ignoreCase = true) }?.profile

    return ReviewSummary(
        className = this::class.java.simpleName,
        id = id,
        key = values.projectKey,
        number = values.number,
        title = values.title,
        state = values.state,
        createdAt = values.createdAt,
        timestamp = values.timestamp,
        feedChannelId = values.feedChannelId,
        branchPair = values.branchPair,
        author = resolvedAuthor,
        createdBy = values.createdBy,
        participants = values.participants,
        reviewers = values.reviewers,
        resolvedAuthor = resolvedAuthor,
    )
}

private fun List<RevisionsInReview>.toReviewCommits(): List<ReviewCommitInReview> =
    map { group ->
        val commits = group.commits.map { it.commit.toReviewCommit() }
        ReviewCommitInReview(
            repositoryInReview = RepositoryInReview(name = group.repository.name),
            revisions = commits,
            commits = commits,
        )
    }

private fun Batch<GitMergedFile>.toMergeRequestChangesPage(review: ReviewSummary): ReviewChangesPage =
    ReviewChangesPage(
        scope = "merge-request-files",
        count = data.size,
        totalCount = totalCount,
        hasMore = !next.isNullOrBlank(),
        next = next.takeIf { it.isNotBlank() },
        changes = data.map { it.toReviewChangeEntry(review) },
    )

private fun Batch<ChangeInReview>.toCodeReviewChangesPage(): ReviewChangesPage =
    ReviewChangesPage(
        scope = "code-review-files",
        count = data.size,
        totalCount = totalCount,
        hasMore = !next.isNullOrBlank(),
        next = next.takeIf { it.isNotBlank() },
        changes = data.map { it.toReviewChangeEntry() },
    )

private fun ChannelItemRecord.toFeedMessage(): FeedMessage =
    FeedMessage(
        id = id,
        text = text,
        created = created.toTimestamp(),
        author = author.toChatAuthor(),
        details = details.toMessageDetails(),
    )

private fun ChannelItemRecord.toChannelMessage(): ChannelMessage =
    ChannelMessage(
        id = id,
        text = text,
        author = author.toChatAuthor(),
        created = created.toTimestamp(),
    )

private fun CodeDiscussionRecord.toDiscussionCreationResult(): DiscussionCreationResult =
    DiscussionCreationResult(
        id = id,
        channelId = channel.id,
        pending = pending,
        feedItemId = feedItemId,
        anchor = anchor.toDiscussionAnchor(),
    )

private fun TD_MemberProfile.toSpaceProfile(): SpaceProfile =
    SpaceProfile(
        id = id,
        username = username,
        name = name.toProfileName(),
    )

private fun TD_ProfileName.toProfileName(): ProfileName =
    ProfileName(
        firstName = firstName,
        lastName = lastName,
    )

private fun CodeReviewParticipant.toReviewParticipant(): ReviewParticipant =
    ReviewParticipant(
        role = role.name,
        profile = user.toSpaceProfile(),
    )

private fun List<CodeReviewParticipant>.toReviewReviewers(): List<ReviewReviewer> =
    mapNotNull { participant ->
        if (participant.role.name.contains("review", ignoreCase = true)) {
            ReviewReviewer(reviewer = participant.user.toSpaceProfile())
        } else {
            null
        }
    }

private fun MergeRequestBranchPair.toBranchPair(): team.jetbrains.mcp.space.MergeRequestBranchPair =
    team.jetbrains.mcp.space.MergeRequestBranchPair(
        repository = readOrNull { repository },
        sourceBranchInfo = readOrNull { sourceBranchInfo }?.toBranch(),
        targetBranchInfo = readOrNull { targetBranchInfo }?.toBranch(),
        sourceBranchRef = readOrNull { sourceBranchRef },
        isMerged = readOrNull { isMerged },
        isStale = readOrNull { isStale },
    )

private fun MergeRequestBranch.toBranch(): team.jetbrains.mcp.space.MergeRequestBranch =
    team.jetbrains.mcp.space.MergeRequestBranch(
        displayName = readOrNull { displayName },
        ref = readOrNull { ref },
        deleted = readOrNull { deleted },
        head = readOrNull { head },
    )

private fun GitCommitInfo.toReviewCommit(): ReviewCommit =
    ReviewCommit(
        id = id,
        message = message,
        author = author.toReviewCommitAuthor(),
        timestamp = commitDate,
    )

private fun GitAuthorInfo.toReviewCommitAuthor(): ReviewCommitAuthor =
    ReviewCommitAuthor(
        name = name,
        email = email,
    )

private fun GitMergedFile.toReviewChangeEntry(review: ReviewSummary): ReviewChangeEntry =
    ReviewChangeEntry(
        kind = "merge-request-file",
        repository = review.branchPair?.repository.orEmpty(),
        path = name,
        oldPath = oldName,
        revision = review.branchPair?.sourceBranchInfo?.head,
        changeType = inferMergeRequestChangeType(),
        entryType = entryType.name,
        conflicting = conflicting,
        read = null,
        detached = null,
        constituentCommits = emptyList(),
        baseId = baseId,
        sourceId = sourceId,
        targetId = targetId,
        oldBlobId = targetId ?: baseId,
        newBlobId = sourceId ?: baseId,
        diffSize = diffSize.toReviewDiffSize(),
        executable = properties?.executable,
        lfs = properties?.lfs,
    )

private fun ChangeInReview.toReviewChangeEntry(): ReviewChangeEntry =
    ReviewChangeEntry(
        kind = "code-review-file",
        repository = repository,
        path = change.currentPath(),
        oldPath = change.previousPath(),
        revision = change.revision,
        changeType = change.changeType.name,
        entryType = change.currentType()?.name ?: change.previousType()?.name,
        conflicting = null,
        read = read,
        detached = change.detached,
        constituentCommits = change.constituentCommits.orEmpty(),
        baseId = null,
        sourceId = change.new?.blob,
        targetId = change.old?.blob,
        oldBlobId = change.old?.blob,
        newBlobId = change.new?.blob,
        diffSize = change.diffSize.toReviewDiffSize(),
        executable = change.currentProperties()?.executable ?: change.previousProperties()?.executable,
        lfs = change.currentProperties()?.lfs ?: change.previousProperties()?.lfs,
    )

private fun Instant.toTimestamp(): Timestamp =
    Timestamp(
        iso = toString(),
        timestamp = epochMilliseconds(),
    )

private fun Instant.epochMilliseconds(): Long = epochSeconds * 1_000 + nanosecondsOfSecond / 1_000_000

private fun M2ItemContentDetails?.toMessageDetails(): MessageDetails? =
    this?.let { details ->
        val codeDiscussion =
            runCatching {
                details.javaClass
                    .methods
                    .firstOrNull { it.name == "getCodeDiscussion" && it.parameterCount == 0 }
                    ?.invoke(details) as? CodeDiscussionRecord
            }.getOrNull()

        MessageDetails(
            className = details::class.java.simpleName,
            codeDiscussion = codeDiscussion?.toDiscussionReference(),
        )
    }

private fun CodeDiscussionRecord.toDiscussionReference(): DiscussionReference =
    DiscussionReference(
        id = id,
        anchor = anchor.toDiscussionAnchor(),
        channel = ChannelReference(id = channel.id),
    )

private data class ReviewSummaryValues(
    val projectKey: String,
    val number: Int,
    val title: String,
    val state: String?,
    val createdAt: Long?,
    val timestamp: Long?,
    val feedChannelId: String?,
    val createdBy: SpaceProfile?,
    val participants: List<ReviewParticipant>,
    val reviewers: List<ReviewReviewer>,
    val branchPair: team.jetbrains.mcp.space.MergeRequestBranchPair?,
)

private fun CodeDiscussionAnchor.toDiscussionAnchor(): DiscussionAnchor =
    DiscussionAnchor(
        filename = filename,
        line = line,
        oldLine = oldLine,
        revision = revision,
    )

private fun CPrincipal.toChatAuthor(): ChatAuthor =
    ChatAuthor(
        name = name,
        details = details.toAuthorDetails(),
    )

private fun CPrincipalDetails?.toAuthorDetails(): AuthorDetails? =
    when (this) {
        null -> null
        is CUserPrincipalDetails ->
            AuthorDetails(
                className = this::class.java.simpleName,
                user = UserReference(id = user.id),
            )
        is CUserWithEmailPrincipalDetails ->
            AuthorDetails(
                className = this::class.java.simpleName,
                user = null,
            )
        else ->
            AuthorDetails(
                className = this::class.java.simpleName,
                user = null,
            )
    }

private fun GitMergedFile.inferMergeRequestChangeType(): String =
    when {
        oldName != null && oldName != name -> "RENAMED"
        targetId == null && sourceId != null -> "ADDED"
        sourceId == null && targetId != null -> "DELETED"
        else -> "MODIFIED"
    }

private fun GitDiffSize?.toReviewDiffSize(): ReviewDiffSize? =
    this?.let {
        ReviewDiffSize(
            added = added,
            deleted = deleted,
        )
    }

private fun GitCommitChange.currentPath(): String = new?.path ?: path ?: old?.path ?: "<unknown>"

private fun GitCommitChange.previousPath(): String? = old?.path?.takeIf { oldPath -> oldPath != currentPath() }

private fun GitCommitChange.currentType() = new?.type

private fun GitCommitChange.previousType() = old?.type

private fun GitCommitChange.currentProperties(): GitFileProperties? = new?.properties

private fun GitCommitChange.previousProperties(): GitFileProperties? = old?.properties

private inline fun <T> readOrNull(block: () -> T): T? = runCatching(block).getOrNull()
