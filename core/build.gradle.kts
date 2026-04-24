plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "com.song"
version = "0.1.0"

repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    api(project(":agent-tool"))

    api("ai.koog:koog-agents:0.8.0-SNAPSHOT")
    api("io.insert-koin:koin-core:4.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

kotlin {
    jvmToolchain(21)
}
