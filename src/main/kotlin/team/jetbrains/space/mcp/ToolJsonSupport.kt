package team.jetbrains.space.mcp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
class ToolJsonSupport {
    @PublishedApi
    internal val jsonCodec = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    inline fun <reified T> encode(value: T): String {
        return when (value) {
            is String -> value
            else -> jsonCodec.encodeToString(value)
        }
    }
}
