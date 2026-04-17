package team.jetbrains.mcp.space

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class SpaceApiParsingTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `project list accepts key objects`() {
        val payload =
            """
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
    fun `review summary resolves author from createdBy`() {
        val payload =
            """
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
}
