package team.jetbrains.space.mcp.space

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

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
}
