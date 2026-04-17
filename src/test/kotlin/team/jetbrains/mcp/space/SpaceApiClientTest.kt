package team.jetbrains.mcp.space

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import space.jetbrains.api.runtime.configureKtorClientForSpace
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpaceApiClientTest {
    @Test
    fun `listProjects sends bearer auth and parses project key objects`() =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    respondJson(
                        """
                        {
                          "next": "",
                          "totalCount": 1,
                          "data": [
                            {
                              "id": "1WphxW057gQL",
                              "key": { "key": "FLEET" },
                              "name": "Fleet",
                              "private": false
                            }
                          ]
                        }
                        """.trimIndent(),
                    )
                }

            val response = client.listProjects(term = null, limit = 50, offset = 0)

            assertEquals(listOf("FLEET"), response.data.map { it.key })
            assertEquals("Bearer test-token", requests.single().headers[HttpHeaders.Authorization])
            assertEquals("/api/http/projects", requests.single().url.encodedPath)
        }

    @Test
    fun `listReviewComments fetches feed comments and filters by author`() =
        runBlocking {
            val requests = mutableListOf<HttpRequestData>()
            val client =
                testClient { request ->
                    requests += request
                    when (request.url.encodedPath) {
                        "/api/http/projects/key%3AFLEET/code-reviews/number%3A7705" ->
                            respondJson(
                                """
                                {
                                  "className": "MergeRequestRecord",
                                  "id": "review-1",
                                  "project": {
                                    "key": "FLEET"
                                  },
                                  "number": 7705,
                                  "title": "Test review",
                                  "state": "Opened",
                                  "createdAt": 1,
                                  "timestamp": 2,
                                  "feedChannelId": "feed-1",
                                  "participants": [],
                                  "branchPairs": [],
                                  "createdBy": {
                                    "id": "author-1",
                                    "username": "review.author",
                                    "name": {
                                      "firstName": "Review",
                                      "lastName": "Author"
                                    }
                                  }
                                }
                                """.trimIndent(),
                            )

                        "/api/http/chats/messages/sync-batch" ->
                            respondJson(
                                """
                                {
                                  "etag": "1",
                                  "data": [
                                    {
                                      "chatMessage": {
                                        "id": "feed-comment-1",
                                        "text": "Looks good",
                                        "created": { "timestamp": 1 },
                                        "author": {
                                          "name": "Mikhail.Chernyavsky",
                                          "details": {
                                            "className": "CUserPrincipalDetails",
                                            "user": { "id": "user-1" }
                                          }
                                        },
                                        "details": { "className": "M2TextItemContent" }
                                      }
                                    },
                                    {
                                      "chatMessage": {
                                        "id": "feed-comment-2",
                                        "text": "Automated status update",
                                        "created": { "timestamp": 2 },
                                        "author": {
                                          "name": "Space Team",
                                          "details": {
                                            "className": "CBotPrincipalDetails"
                                          }
                                        },
                                        "details": { "className": "M2TextItemContent" }
                                      }
                                    }
                                  ],
                                  "hasMore": false
                                }
                                """.trimIndent(),
                            )

                        else -> error("Unexpected request path: ${request.url.encodedPath}")
                    }
                }

            val response =
                client.listReviewComments(
                    projectKey = "FLEET",
                    reviewRef = "number:7705",
                    author = "Mikhail.Chernyavsky",
                    discussionReplyLimit = 20,
                    feedBatchSize = 100,
                    feedBatchLimit = 3,
                )

            assertEquals(1, response.count)
            assertEquals(listOf("review-feed"), response.comments.map { it.kind })
            assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer test-token" })
        }

    private fun testClient(handler: MockRequestHandler): SpaceApiClient {
        val credentialStore = SpaceCredentialStore(Files.createTempDirectory("space-mcp-server-test"))
        credentialStore.save(StoredCredentials(accessToken = "test-token"))

        return SpaceApiClient(credentialStore) { json ->
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) {
                    json(json)
                }
                configureKtorClientForSpace()
            }
        }
    }

    private fun MockRequestHandleScope.respondJson(body: String) =
        respond(
            content = body,
            status = HttpStatusCode.OK,
            headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
        )
}
