package team.jetbrains.mcp

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
class ToolJsonSupport {
    @PublishedApi
    internal val jsonCodec =
        Json {
            prettyPrint = true
            prettyPrintIndent = "  "
            encodeDefaults = true
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    inline fun <reified T> encode(value: T): String =
        when (value) {
            is String -> value
            else -> jsonCodec.encodeToString(value)
        }
}
