package team.jetbrains.mcp.space

import kotlinx.serialization.KSerializer
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class StoredCredentials(
    val serverUrl: String = SpaceAuthService.DEFAULT_SERVER_URL,
    val apiBaseUrl: String = SpaceAuthService.defaultApiBaseUrl(SpaceAuthService.DEFAULT_SERVER_URL),
    val clientId: String? = null,
    val clientSecret: String? = null,
    val scope: String = SpaceAuthService.DEFAULT_SCOPE,
    val redirectUri: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAtEpochSeconds: Long? = null,
)

@Serializable
data class OAuthAuthorizationResult(
    val serverUrl: String,
    val apiBaseUrl: String,
    val redirectUri: String,
    val scope: String,
    val clientId: String,
    val hasClientSecret: Boolean,
    val expiresAtEpochSeconds: Long?,
)

@Serializable
data class AuthStatus(
    val configured: Boolean,
    val authenticated: Boolean,
    val serverUrl: String?,
    val apiBaseUrl: String?,
    val clientId: String?,
    val scope: String?,
    val expiresAtEpochSeconds: Long?,
    val currentUser: SpaceProfile? = null,
)

@Serializable
data class SpaceProfile(
    val id: String,
    val username: String? = null,
    val name: ProfileName? = null,
)

@Serializable
data class ProfileName(
    val firstName: String? = null,
    val lastName: String? = null,
)

@Serializable
data class BatchResponse<T>(
    val data: List<T> = emptyList(),
    val totalCount: Int? = null,
    val hasMore: Boolean? = null,
    val next: String? = null,
)

@Serializable
data class ProjectSummary(
    val id: String,
    @Serializable(with = SpaceKeyStringSerializer::class)
    val key: String,
    val name: String,
    val private: Boolean? = null,
)

@Serializable
data class ReviewListItemResponse(
    val review: ReviewSummary,
)

@Serializable
data class ReviewSummary(
    @SerialName("className")
    val className: String? = null,
    val id: String,
    @Serializable(with = NullableSpaceKeyStringSerializer::class)
    val key: String? = null,
    val number: Int,
    val title: String,
    val state: String? = null,
    val createdAt: Long? = null,
    val timestamp: Long? = null,
    val feedChannelId: String? = null,
    val branchPair: MergeRequestBranchPair? = null,
    val author: SpaceProfile? = null,
    val createdBy: SpaceProfile? = null,
    val participants: List<ReviewParticipant> = emptyList(),
    val reviewers: List<ReviewReviewer> = emptyList(),
    val resolvedAuthor: SpaceProfile? = null,
)

@Serializable
data class ReviewParticipant(
    val role: String? = null,
    val profile: SpaceProfile? = null,
)

@Serializable
data class ReviewReviewer(
    val reviewer: SpaceProfile? = null,
)

@Serializable
data class MergeRequestBranchPair(
    val repository: String? = null,
    val sourceBranchInfo: MergeRequestBranch? = null,
    val targetBranchInfo: MergeRequestBranch? = null,
    val sourceBranchRef: String? = null,
    val isMerged: Boolean? = null,
    val isStale: Boolean? = null,
)

@Serializable
data class MergeRequestBranch(
    val displayName: String? = null,
    val ref: String? = null,
    val deleted: Boolean? = null,
    val head: String? = null,
)

@Serializable
data class ReviewDetailsBundle(
    val review: ReviewSummary,
    val commits: List<ReviewCommitInReview> = emptyList(),
    val comments: ReviewCommentBundle? = null,
)

@Serializable
data class ReviewCommentsResponse(
    val review: ReviewSummary,
    val feedChannelId: String,
    val authorFilter: String? = null,
    val count: Int,
    val comments: List<ReviewCommentEntry> = emptyList(),
)

@Serializable
data class MyReviewsResponse(
    val reviews: List<CrossProjectReview> = emptyList(),
    val scannedProjects: Int,
    val projectScanTruncated: Boolean = false,
    val requestedLimit: Int,
)

@Serializable
data class CrossProjectReview(
    val projectKey: String,
    val projectName: String? = null,
    val matchedRoles: List<String> = emptyList(),
    val review: ReviewSummary,
)

@Serializable
data class RawReviewDetailsResponse(
    val shortInfo: ReviewSummaryReference? = null,
    val commits: List<RawReviewCommitGroup> = emptyList(),
)

@Serializable
data class ReviewSummaryReference(
    @SerialName("className")
    val className: String? = null,
    val id: String,
    @Serializable(with = NullableSpaceKeyStringSerializer::class)
    val key: String? = null,
    val number: Int? = null,
    val title: String? = null,
)

@Serializable
data class RawReviewCommitGroup(
    @SerialName("repository")
    val repository: RepositoryInReview? = null,
    val repositoryInReview: RepositoryInReview? = null,
    val revisions: List<RawReviewCommitEntry> = emptyList(),
    val commits: List<RawReviewCommitEntry> = emptyList(),
    val commitWithGraph: RawCommitWithGraph? = null,
)

@Serializable
data class RawReviewCommitEntry(
    val repositoryName: String? = null,
    val commit: ReviewCommit? = null,
    val id: String? = null,
    val message: String? = null,
    val author: ReviewCommitAuthor? = null,
    val timestamp: Long? = null,
    val authorDate: Long? = null,
    val commitDate: Long? = null,
)

@Serializable
data class RawCommitWithGraph(
    val commit: ReviewCommit? = null,
)

@Serializable
data class ReviewCommitInReview(
    val repositoryInReview: RepositoryInReview? = null,
    val revisions: List<ReviewCommit> = emptyList(),
    val commits: List<ReviewCommit> = emptyList(),
    val commitWithGraph: CommitWithGraph? = null,
)

@Serializable
data class RepositoryInReview(
    val name: String? = null,
)

@Serializable
data class ReviewCommit(
    val id: String? = null,
    val message: String? = null,
    val author: ReviewCommitAuthor? = null,
    val timestamp: Long? = null,
)

@Serializable
data class ReviewCommitAuthor(
    val name: String? = null,
    val email: String? = null,
)

@Serializable
data class CommitWithGraph(
    val commit: ReviewCommit? = null,
)

@Serializable
data class ReviewCommentBundle(
    val feedChannelId: String,
    val feedMessages: List<FeedMessage> = emptyList(),
    val codeDiscussions: List<CodeDiscussionThread> = emptyList(),
    val entries: List<ReviewCommentEntry> = emptyList(),
)

@Serializable
data class SyncBatchResponse(
    val data: List<SyncBatchItem> = emptyList(),
    val etag: String? = null,
    val hasMore: Boolean? = null,
)

@Serializable
data class SyncBatchItem(
    val chatMessage: SyncChatMessage? = null,
)

@Serializable
data class SyncChatMessage(
    val id: String,
    val text: String? = null,
    val created: Timestamp? = null,
    val details: MessageDetails? = null,
    val author: ChatAuthor? = null,
    val projectedItem: ProjectedItem? = null,
)

@Serializable
data class ProjectedItem(
    val author: ChatAuthor? = null,
)

@Serializable
data class Timestamp(
    val iso: String? = null,
    val timestamp: Long? = null,
)

@Serializable
data class MessageDetails(
    val className: String? = null,
    val codeDiscussion: DiscussionReference? = null,
)

@Serializable
data class DiscussionReference(
    val id: String,
    val anchor: DiscussionAnchor? = null,
    val channel: ChannelReference? = null,
)

@Serializable
data class DiscussionAnchor(
    val filename: String? = null,
    val line: Int? = null,
    val oldLine: Int? = null,
    val revision: String? = null,
)

@Serializable
data class ChannelReference(
    val id: String,
)

@Serializable
data class FeedMessage(
    val id: String,
    val text: String? = null,
    val created: Timestamp? = null,
    val author: ChatAuthor? = null,
    val details: MessageDetails? = null,
)

@Serializable
data class CodeDiscussionThread(
    val id: String,
    val channelId: String? = null,
    val anchor: DiscussionAnchor? = null,
    val feedMessageId: String? = null,
    val messages: List<ChannelMessage> = emptyList(),
)

@Serializable
data class MessagesResponse(
    val messages: List<ChannelMessage> = emptyList(),
)

@Serializable
data class ChannelMessage(
    val id: String,
    val text: String? = null,
    val author: ChatAuthor? = null,
    val created: Timestamp? = null,
)

@Serializable
data class ReviewCommentEntry(
    val id: String,
    val kind: String,
    val text: String? = null,
    val created: Timestamp? = null,
    val author: ChatAuthor? = null,
    val discussionId: String? = null,
    val channelId: String? = null,
    val anchor: DiscussionAnchor? = null,
    val feedMessageId: String? = null,
)

@Serializable
data class ChatAuthor(
    val name: String? = null,
    val details: AuthorDetails? = null,
)

@Serializable
data class AuthorDetails(
    val className: String? = null,
    val user: UserReference? = null,
)

@Serializable
data class UserReference(
    val id: String? = null,
)

@Serializable
data class DiscussionCreationResult(
    val id: String,
    val channelId: String,
    val pending: Boolean? = null,
    val feedItemId: String? = null,
    val anchor: DiscussionAnchor? = null,
)

@Serializable
data class SentMessageResult(
    val id: String,
    val channelId: String,
)

@Serializable
data class GenericApiRecord(
    val id: String,
    val channel: ChannelReference? = null,
    val pending: Boolean? = null,
    val feedItemId: String? = null,
    val anchor: DiscussionAnchor? = null,
)

@Serializable
data class GenericChatMessageRecord(
    val id: String,
)

@Serializable
data class SpaceError(
    val message: String,
    val details: JsonElement? = null,
)

internal fun RawReviewDetailsResponse.normalizedCommits(): List<ReviewCommitInReview> {
    return commits.map { group ->
        ReviewCommitInReview(
            repositoryInReview = group.repository ?: group.repositoryInReview,
            revisions = group.revisions.mapNotNull { it.normalizedCommit() },
            commits = group.commits.mapNotNull { it.normalizedCommit() },
            commitWithGraph = group.commitWithGraph?.let { CommitWithGraph(commit = it.commit) },
        )
    }
}

internal fun ReviewSummary.normalized(): ReviewSummary {
    val resolved = author
        ?: createdBy
        ?: participants.firstOrNull { it.role.equals("Author", ignoreCase = true) }?.profile
    return if (resolved == resolvedAuthor) this else copy(resolvedAuthor = resolved)
}

internal fun ChatAuthor.matches(filter: String): Boolean {
    val normalizedFilter = filter.trim()
    return normalizedFilter.equals(name, ignoreCase = true) ||
        normalizedFilter == details?.user?.id
}

internal fun ChatAuthor.isUser(): Boolean {
    return details?.user?.id != null || details?.className == "CUserPrincipalDetails"
}

private fun RawReviewCommitEntry.normalizedCommit(): ReviewCommit? {
    return commit ?: if (
        id != null ||
        message != null ||
        author != null ||
        timestamp != null ||
        authorDate != null ||
        commitDate != null
    ) {
        ReviewCommit(
            id = id,
            message = message,
            author = author,
            timestamp = timestamp ?: authorDate ?: commitDate,
        )
    } else {
        null
    }
}

object SpaceKeyStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("SpaceKeyString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        return decodeSpaceKey(decoder)
            ?: throw SerializationException("Space key value is missing.")
    }

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

object NullableSpaceKeyStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("NullableSpaceKeyString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? {
        return decodeSpaceKey(decoder)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value)
        }
    }
}

private fun decodeSpaceKey(decoder: Decoder): String? {
    val jsonDecoder = decoder as? JsonDecoder ?: return decoder.decodeString()
    return when (val element = jsonDecoder.decodeJsonElement()) {
        JsonNull -> null
        is JsonPrimitive -> element.content
        is JsonObject -> element["key"]?.jsonPrimitive?.content
            ?: throw SerializationException("Expected Space key object to contain 'key'.")
        else -> throw SerializationException("Unsupported Space key payload: $element")
    }
}
