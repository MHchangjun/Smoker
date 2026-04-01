plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
}

group = "com.song"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("ai.koog:koog-agents:0.6.0")
    implementation("io.insert-koin:koin-core:4.0.0")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

application {
    applicationName = "Smoker"
    mainClass.set("com.song.MainKt")
}
