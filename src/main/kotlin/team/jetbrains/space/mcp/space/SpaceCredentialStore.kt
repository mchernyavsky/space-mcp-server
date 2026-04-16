package team.jetbrains.space.mcp.space

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

class SpaceCredentialStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val configDirectory: Path = defaultConfigDirectory()
    private val configFile: Path = configDirectory.resolve("credentials.json")

    fun load(): StoredCredentials? {
        if (!Files.exists(configFile)) {
            return null
        }
        return json.decodeFromString(Files.readString(configFile))
    }

    fun save(credentials: StoredCredentials) {
        Files.createDirectories(configDirectory)
        Files.writeString(configFile, json.encodeToString(StoredCredentials.serializer(), credentials))
    }

    fun clear() {
        Files.deleteIfExists(configFile)
    }

    private fun defaultConfigDirectory(): Path {
        val xdgHome = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
        return if (xdgHome != null) {
            Paths.get(xdgHome, "space-mcp-server")
        } else {
            Paths.get(System.getProperty("user.home"), ".config", "space-mcp-server")
        }
    }
}
