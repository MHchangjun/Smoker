plugins {
    kotlin("jvm")
}

group = "com.song"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    api(project(":core"))

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.20.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

kotlin {
    jvmToolchain(21)
}
