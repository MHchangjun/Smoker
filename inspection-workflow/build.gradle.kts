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

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}
