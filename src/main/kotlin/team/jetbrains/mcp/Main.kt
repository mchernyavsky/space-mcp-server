package team.jetbrains.mcp

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration

fun main() {
    disableKotlinLoggingStartupBanner()
    SpaceMcpServer().run()
}

private fun disableKotlinLoggingStartupBanner() {
    KotlinLoggingConfiguration.logStartupMessage = false
}
