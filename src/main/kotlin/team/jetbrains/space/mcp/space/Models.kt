package team.jetbrains.space.mcp.space

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

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
    val key: String? = null,
    val number: Int,
    val title: String,
    val state: String? = null,
    val createdAt: Long? = null,
    val timestamp: Long? = null,
    val feedChannelId: String? = null,
    val branchPair: MergeRequestBranchPair? = null,
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
data class ReviewDetailsResponse(
    val shortInfo: ReviewSummary,
    val commits: List<ReviewCommitInReview> = emptyList(),
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
