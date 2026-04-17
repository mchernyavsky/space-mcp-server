package team.jetbrains.mcp.space

import kotlinx.datetime.Instant
import space.jetbrains.api.runtime.Batch
import space.jetbrains.api.runtime.types.CPrincipal
import space.jetbrains.api.runtime.types.CPrincipalDetails
import space.jetbrains.api.runtime.types.CUserPrincipalDetails
import space.jetbrains.api.runtime.types.CUserWithEmailPrincipalDetails
import space.jetbrains.api.runtime.types.ChannelItemRecord
import space.jetbrains.api.runtime.types.CodeDiscussionAnchor
import space.jetbrains.api.runtime.types.CodeDiscussionRecord
import space.jetbrains.api.runtime.types.CodeReviewParticipant
import space.jetbrains.api.runtime.types.CodeReviewRecord
import space.jetbrains.api.runtime.types.CodeReviewWithCount
import space.jetbrains.api.runtime.types.CommitSetReviewRecord
import space.jetbrains.api.runtime.types.GitAuthorInfo
import space.jetbrains.api.runtime.types.GitCommitInfo
import space.jetbrains.api.runtime.types.M2ItemContentDetails
import space.jetbrains.api.runtime.types.MergeRequestBranch
import space.jetbrains.api.runtime.types.MergeRequestBranchPair
import space.jetbrains.api.runtime.types.MergeRequestRecord
import space.jetbrains.api.runtime.types.PR_Project
import space.jetbrains.api.runtime.types.RevisionsInReview
import space.jetbrains.api.runtime.types.TD_MemberProfile
import space.jetbrains.api.runtime.types.TD_ProfileName

internal fun Batch<PR_Project>.toProjectBatchResponse(): BatchResponse<ProjectSummary> =
    BatchResponse(
        data = data.map { it.toProjectSummary() },
        totalCount = totalCount,
        next = next.takeIf { it.isNotBlank() },
    )

internal fun Batch<CodeReviewWithCount>.toReviewBatchResponse(): BatchResponse<ReviewListItemResponse> =
    BatchResponse(
        data = data.map { ReviewListItemResponse(review = it.review.toReviewSummary()) },
        totalCount = totalCount,
        next = next.takeIf { it.isNotBlank() },
    )

internal fun PR_Project.toProjectSummary(): ProjectSummary =
    ProjectSummary(
        id = id,
        key = key.key,
        name = name,
        private = private,
    )

internal fun CodeReviewRecord.toReviewSummary(): ReviewSummary {
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

internal fun List<RevisionsInReview>.toReviewCommits(): List<ReviewCommitInReview> =
    map { group ->
        val commits = group.commits.map { it.commit.toReviewCommit() }
        ReviewCommitInReview(
            repositoryInReview = RepositoryInReview(name = group.repository.name),
            revisions = commits,
            commits = commits,
        )
    }

internal fun ChannelItemRecord.toFeedMessage(): FeedMessage =
    FeedMessage(
        id = id,
        text = text,
        created = created.toTimestamp(),
        author = author.toChatAuthor(),
        details = details.toMessageDetails(),
    )

internal fun ChannelItemRecord.toChannelMessage(): ChannelMessage =
    ChannelMessage(
        id = id,
        text = text,
        author = author.toChatAuthor(),
        created = created.toTimestamp(),
    )

internal fun CodeDiscussionRecord.toDiscussionCreationResult(): DiscussionCreationResult =
    DiscussionCreationResult(
        id = id,
        channelId = channel.id,
        pending = pending,
        feedItemId = feedItemId,
        anchor = anchor.toDiscussionAnchor(),
    )

internal fun TD_MemberProfile.toSpaceProfile(): SpaceProfile =
    SpaceProfile(
        id = id,
        username = username,
        name = name.toProfileName(),
    )

internal fun TD_ProfileName.toProfileName(): ProfileName =
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
        repository = repository,
        sourceBranchInfo = sourceBranchInfo?.toBranch(),
        targetBranchInfo = targetBranchInfo?.toBranch(),
        sourceBranchRef = sourceBranchRef,
        isMerged = isMerged,
        isStale = isStale,
    )

private fun MergeRequestBranch.toBranch(): team.jetbrains.mcp.space.MergeRequestBranch =
    team.jetbrains.mcp.space.MergeRequestBranch(
        displayName = displayName,
        ref = ref,
        deleted = deleted,
        head = head,
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
