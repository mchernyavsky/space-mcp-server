package team.jetbrains.mcp

fun main() {
    disableKotlinLoggingStartupBanner()
    SpaceMcpServer().run()
}

private fun disableKotlinLoggingStartupBanner() {
    runCatching {
        val configurationClass = Class.forName("io.github.oshai.kotlinlogging.KotlinLoggingConfiguration")
        val instance = configurationClass.getField("INSTANCE").get(null)
        configurationClass
            .getMethod("setLogStartupMessage", Boolean::class.javaPrimitiveType)
            .invoke(instance, false)
    }
}
