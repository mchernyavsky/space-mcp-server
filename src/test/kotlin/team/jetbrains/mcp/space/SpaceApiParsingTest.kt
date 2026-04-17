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
                  "key": { "key": "ALDERAAN" },
                  "name": "Alderaan Defense Grid",
                  "private": false
                },
                {
                  "id": "1tCDp10O9Jum",
                  "key": { "key": "GOTHAM" },
                  "name": "Gotham City Watch",
                  "private": false
                }
              ]
            }
            """.trimIndent()

        val response = json.decodeFromString<BatchResponse<ProjectSummary>>(payload)

        assertEquals(listOf("ALDERAAN", "GOTHAM"), response.data.map { it.key })
        assertEquals(2, response.totalCount)
    }

    @Test
    fun `review summary resolves author from createdBy`() {
        val payload =
            """
            {
              "className": "MergeRequestRecord",
              "id": "30meQG4eKA1u",
              "number": 1138,
              "title": "[falcon] Tune hyperdrive ignition timing",
              "createdBy": {
                "id": "hero-001",
                "username": "Leia.Organa",
                "name": {
                  "firstName": "Leia",
                  "lastName": "Organa"
                }
              }
            }
            """.trimIndent()

        val response = json.decodeFromString<ReviewSummary>(payload).normalized()

        assertEquals("Leia.Organa", response.resolvedAuthor?.username)
        assertEquals("hero-001", response.resolvedAuthor?.id)
    }
}
