plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
    application
    id("com.gradleup.shadow") version "9.4.1"
}

group = "team.jetbrains.space"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.4.2"
val coroutinesVersion = "1.10.2"
val serializationVersion = "1.11.0"
val mcpVersion = "0.11.1"
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
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
    implementation("org.slf4j:slf4j-simple:$slf4jVersion")
    testImplementation(kotlin("test"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("all")
}
