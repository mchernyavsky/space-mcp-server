package team.jetbrains.mcp.space

import io.ktor.client.HttpClient
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import space.jetbrains.api.runtime.BatchInfo
import space.jetbrains.api.runtime.PermissionScope
import space.jetbrains.api.runtime.SpaceAppInstance
import space.jetbrains.api.runtime.SpaceAuth
import space.jetbrains.api.runtime.SpaceClient
import space.jetbrains.api.runtime.SpaceTokenInfo
import space.jetbrains.api.runtime.resources.chats
import space.jetbrains.api.runtime.resources.projects
import space.jetbrains.api.runtime.resources.teamDirectory
import space.jetbrains.api.runtime.types.ChannelIdentifier
import space.jetbrains.api.runtime.types.ChatMessage
import space.jetbrains.api.runtime.types.CodeReviewStateFilter
import space.jetbrains.api.runtime.types.LocalCodeDiscussionAnchorIn
import space.jetbrains.api.runtime.types.MessagesSorting
import space.jetbrains.api.runtime.types.ProfileIdentifier
import space.jetbrains.api.runtime.types.ProjectIdentifier
import space.jetbrains.api.runtime.types.ReviewIdentifier
import space.jetbrains.api.runtime.types.ReviewSorting
import space.jetbrains.api.runtime.types.ReviewType
import space.jetbrains.api.runtime.types.SyncBatchInfo

internal class SpaceSdkFacade {
    suspend fun getCurrentUser(session: SpaceSession): SpaceProfile =
        session.sdkClient.teamDirectory.profiles
            .getProfile(ProfileIdentifier.Me) {
                id()
                username()
                name {
                    firstName()
                    lastName()
                }
            }.toSpaceProfile()

    suspend fun listProjects(
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

    suspend fun listReviews(
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

    suspend fun getReviewSummary(
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

    suspend fun getReviewCommits(
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

    suspend fun loadComments(
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

    suspend fun sendChannelMessage(
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

    suspend fun createCodeDiscussion(
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
}

internal class SpaceSession(
    val credentials: StoredCredentials,
    val httpClient: HttpClient,
    val sdkClient: SpaceClient,
) : AutoCloseable {
    suspend fun accessToken(): String = sdkClient.token().accessToken

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
