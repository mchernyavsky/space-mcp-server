plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    application
    id("com.gradleup.shadow") version "9.4.1"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "team.jetbrains.mcp"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/space-sdk/maven")
}

val ktorVersion = "3.3.3"
val coroutinesVersion = "1.10.2"
val serializationVersion = "1.11.0"
val mcpVersion = "0.11.1"
val spaceSdkVersion = "168099"
val kotlinLoggingVersion = "8.0.01"
val slf4jVersion = "2.0.17"

kotlin {
    jvmToolchain(21)
}

application {
    mainClass = "team.jetbrains.mcp.MainKt"
}

dependencies {
    implementation("io.modelcontextprotocol:kotlin-sdk-jvm:$mcpVersion")
    implementation("io.modelcontextprotocol:kotlin-sdk-server-jvm:$mcpVersion")
    implementation("org.jetbrains:space-sdk-jvm:$spaceSdkVersion")
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("io.github.oshai:kotlin-logging-jvm:$kotlinLoggingVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    implementation("org.slf4j:slf4j-simple:$slf4jVersion")
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("all")
}
