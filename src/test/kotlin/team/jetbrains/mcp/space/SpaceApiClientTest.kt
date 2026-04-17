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
                              "key": { "key": "ALDERAAN" },
                              "name": "Alderaan Defense Grid",
                              "private": false
                            }
                          ]
                        }
                        """.trimIndent(),
                    )
                }

            val response = client.listProjects(term = null, limit = 50, offset = 0)

            assertEquals(listOf("ALDERAAN"), response.data.map { it.key })
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
                        "/api/http/projects/key%3AALDERAAN/code-reviews/number%3A1138" ->
                            respondJson(
                                """
                                {
                                  "className": "MergeRequestRecord",
                                  "id": "review-1",
                                  "project": {
                                    "key": "ALDERAAN"
                                  },
                                  "number": 1138,
                                  "title": "[falcon] Tune hyperdrive ignition timing",
                                  "state": "Opened",
                                  "createdAt": 1,
                                  "timestamp": 2,
                                  "feedChannelId": "feed-1",
                                  "participants": [],
                                  "branchPairs": [],
                                  "createdBy": {
                                    "id": "author-1",
                                    "username": "Han.Solo",
                                    "name": {
                                      "firstName": "Han",
                                      "lastName": "Solo"
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
                                          "name": "Leia.Organa",
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
                    projectKey = "ALDERAAN",
                    reviewRef = "number:1138",
                    author = "Leia.Organa",
                    discussionReplyLimit = 20,
                    feedBatchSize = 100,
                    feedBatchLimit = 3,
                )

            assertEquals(1, response.count)
            assertEquals(listOf("review-feed"), response.comments.map { it.kind })
            assertTrue(requests.all { it.headers[HttpHeaders.Authorization] == "Bearer test-token" })
        }

    @Test
    fun `listReviewChanges returns normalized merge request files`() =
        runBlocking {
            val client =
                testClient { request ->
                    when (request.url.encodedPath) {
                        "/api/http/projects/key%3AALDERAAN/code-reviews/number%3A1138" ->
                            respondJson(
                                """
                                {
                                  "className": "MergeRequestRecord",
                                  "id": "review-1",
                                  "project": {
                                    "key": "ALDERAAN"
                                  },
                                  "number": 1138,
                                  "title": "[falcon] Tune hyperdrive ignition timing",
                                  "state": "Opened",
                                  "createdAt": 1,
                                  "timestamp": 2,
                                  "feedChannelId": "feed-1",
                                  "participants": [],
                                  "branchPairs": [
                                    {
                                      "repository": "millennium-falcon",
                                      "sourceBranchInfo": {
                                        "head": "source-head"
                                      }
                                    }
                                  ],
                                  "createdBy": {
                                    "id": "author-1",
                                    "username": "Han.Solo",
                                    "name": {
                                      "firstName": "Han",
                                      "lastName": "Solo"
                                    }
                                  }
                                }
                                """.trimIndent(),
                            )

                        "/api/http/projects/key%3AALDERAAN/code-reviews/number%3A1138/merge-files" ->
                            respondJson(
                                """
                                {
                                  "next": "",
                                  "totalCount": 1,
                                  "data": [
                                    {
                                      "name": "src/Hyperdrive.kt",
                                      "oldName": "src/NavComputer.kt",
                                      "baseId": "base-1",
                                      "sourceId": "source-blob",
                                      "targetId": "target-blob",
                                      "diffSize": {
                                        "added": 12,
                                        "deleted": 4
                                      },
                                      "entryType": "FILE",
                                      "conflicting": false,
                                      "properties": {
                                        "lfs": false,
                                        "executable": true
                                      }
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            )

                        else -> error("Unexpected request path: ${request.url.encodedPath}")
                    }
                }

            val response =
                client.listReviewChanges(
                    projectKey = "ALDERAAN",
                    reviewRef = "number:1138",
                    limit = 100,
                    offset = 0,
                )
            val change = response.changes.single()

            assertEquals("merge-request-files", response.scope)
            assertEquals(1, response.count)
            assertEquals("millennium-falcon", change.repository)
            assertEquals("src/Hyperdrive.kt", change.path)
            assertEquals("src/NavComputer.kt", change.oldPath)
            assertEquals("source-head", change.revision)
            assertEquals("RENAMED", change.changeType)
            assertEquals(12, change.diffSize?.added)
        }

    @Test
    fun `listReviewChanges returns normalized code review files`() =
        runBlocking {
            val client =
                testClient { request ->
                    when (request.url.encodedPath) {
                        "/api/http/projects/key%3AALDERAAN/code-reviews/number%3A1138" ->
                            respondJson(
                                """
                                {
                                  "className": "CommitSetReviewRecord",
                                  "id": "review-1",
                                  "project": {
                                    "key": "ALDERAAN"
                                  },
                                  "number": 1138,
                                  "title": "[falcon] Tune hyperdrive ignition timing",
                                  "state": "Opened",
                                  "createdAt": 1,
                                  "timestamp": 2,
                                  "feedChannelId": "feed-1",
                                  "participants": [],
                                  "createdBy": {
                                    "id": "author-1",
                                    "username": "Han.Solo",
                                    "name": {
                                      "firstName": "Han",
                                      "lastName": "Solo"
                                    }
                                  }
                                }
                                """.trimIndent(),
                            )

                        "/api/http/projects/key%3AALDERAAN/code-reviews/number%3A1138/files" ->
                            respondJson(
                                """
                                {
                                  "next": "",
                                  "totalCount": 1,
                                  "data": [
                                    {
                                      "repository": "millennium-falcon",
                                      "change": {
                                        "changeType": "MODIFIED",
                                        "revision": "revision-1",
                                        "diffSize": {
                                          "added": 7,
                                          "deleted": 3
                                        },
                                        "path": "src/Hyperdrive.kt",
                                        "detached": false,
                                        "constituentCommits": ["c1", "c2"],
                                        "old": {
                                          "path": "src/NavComputer.kt",
                                          "blob": "old-blob",
                                          "type": "FILE",
                                          "properties": {
                                            "lfs": false,
                                            "executable": false
                                          }
                                        },
                                        "new": {
                                          "path": "src/Hyperdrive.kt",
                                          "blob": "new-blob",
                                          "type": "FILE",
                                          "properties": {
                                            "lfs": false,
                                            "executable": true
                                          }
                                        }
                                      },
                                      "read": true
                                    }
                                  ]
                                }
                                """.trimIndent(),
                            )

                        else -> error("Unexpected request path: ${request.url.encodedPath}")
                    }
                }

            val response =
                client.listReviewChanges(
                    projectKey = "ALDERAAN",
                    reviewRef = "number:1138",
                    limit = 100,
                    offset = 0,
                )
            val change = response.changes.single()

            assertEquals("code-review-files", response.scope)
            assertEquals(1, response.count)
            assertEquals("millennium-falcon", change.repository)
            assertEquals("src/Hyperdrive.kt", change.path)
            assertEquals("src/NavComputer.kt", change.oldPath)
            assertEquals("revision-1", change.revision)
            assertEquals("MODIFIED", change.changeType)
            assertEquals(listOf("c1", "c2"), change.constituentCommits)
            assertTrue(change.read == true)
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
