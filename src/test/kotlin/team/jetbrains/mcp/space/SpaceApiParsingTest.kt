package team.jetbrains.mcp.space

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SpaceApiParsingTest {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `project list accepts key objects`() {
        val payload = """
            {
              "next": "50",
              "totalCount": 2,
              "data": [
                {
                  "id": "1WphxW057gQL",
                  "key": { "key": "FLEET" },
                  "name": "Fleet",
                  "private": false
                },
                {
                  "id": "1tCDp10O9Jum",
                  "key": { "key": "GRAZI" },
                  "name": "JetBrains AI",
                  "private": false
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<BatchResponse<ProjectSummary>>(payload)

        assertEquals(listOf("FLEET", "GRAZI"), response.data.map { it.key })
        assertEquals(2, response.totalCount)
    }

    @Test
    fun `review details accept partial short info and nested commits`() {
        val payload = """
            {
              "shortInfo": {
                "className": "MergeRequestRecord",
                "id": "4fA7DN2NCbgt"
              },
              "commits": [
                {
                  "repository": {
                    "name": "grazie-rules",
                    "deleted": false
                  },
                  "commits": [
                    {
                      "repositoryName": "grazie-rules",
                      "commit": {
                        "id": "9013469ed495a90305cb7b886b1b18c8548b09f7",
                        "message": "en very abuse: improve suggestions, cover more adjectives",
                        "authorDate": 1696539011000,
                        "author": {
                          "name": "Anna Lander",
                          "email": "Anna.Lander@jetbrains.com"
                        }
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val response = json.decodeFromString<RawReviewDetailsResponse>(payload)
        val commits = response.normalizedCommits()

        assertFalse(commits.isEmpty())
        assertEquals("grazie-rules", commits.single().repositoryInReview?.name)

        val commit = commits.single().commits.single()
        assertEquals("9013469ed495a90305cb7b886b1b18c8548b09f7", commit.id)
        assertEquals("en very abuse: improve suggestions, cover more adjectives", commit.message)
        assertNotNull(commit.author)
        assertEquals("Anna Lander", commit.author.name)
    }

    @Test
    fun `review summary resolves author from createdBy`() {
        val payload = """
            {
              "className": "MergeRequestRecord",
              "id": "30meQG4eKA1u",
              "number": 200545,
              "title": "AIR-4813",
              "createdBy": {
                "id": "3j5uoD3koYEa",
                "username": "Mikhail.Chernyavsky",
                "name": {
                  "firstName": "Mikhail",
                  "lastName": "Chernyavsky"
                }
              }
            }
        """.trimIndent()

        val response = json.decodeFromString<ReviewSummary>(payload).normalized()

        assertEquals("Mikhail.Chernyavsky", response.resolvedAuthor?.username)
        assertEquals("3j5uoD3koYEa", response.resolvedAuthor?.id)
    }

    @Test
    fun `feed sync batch accepts top level author`() {
        val payload = """
            {
              "etag": "1669848753922",
              "data": [
                {
                  "chatMessage": {
                    "id": "Esgo60Vpp3W",
                    "text": "test",
                    "author": {
                      "name": "Mikhail.Chernyavsky",
                      "details": {
                        "className": "CUserPrincipalDetails",
                        "user": {
                          "id": "3j5uoD3koYEa"
                        }
                      }
                    },
                    "details": {
                      "className": "M2TextItemContent"
                    },
                    "created": {
                      "iso": "2026-04-16T18:32:13.110Z",
                      "timestamp": 1776364333110
                    }
                  }
                }
              ],
              "hasMore": false
            }
        """.trimIndent()

        val response = json.decodeFromString<SyncBatchResponse>(payload)
        val message = response.data.single().chatMessage

        assertNotNull(message)
        assertEquals("test", message.text)
        assertEquals("Mikhail.Chernyavsky", message.author?.name)
        assertTrue(message.author?.matches("3j5uoD3koYEa") == true)
    }
}
