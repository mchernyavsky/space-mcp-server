plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.serialization") version "2.2.21"
    application
    id("com.gradleup.shadow") version "8.3.9"
}

group = "team.jetbrains.space"
version = "0.1.0"

repositories {
    mavenCentral()
}

val ktorVersion = "3.1.1"
val coroutinesVersion = "1.9.0"
val serializationVersion = "1.9.0"
val mcpVersion = "0.8.4"
val slf4jVersion = "2.0.13"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "team.jetbrains.space.mcp.MainKt"
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
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveClassifier.set("all")
}
