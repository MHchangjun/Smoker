// Phase 0 PoC plugin module.
// Goal: verify on the deployment server that
//   (a) ApplicationStarter loads inside Android Studio (headless or Xvfb), and
//   (b) the koog 0.8.0-SNAPSHOT classpath survives the IntelliJ plugin classloader.
// If (b) fails with NoSuchMethodError / ClassNotFoundException, this module is
// where shadowJar relocation gets prototyped before touching the real codebase.

plugins {
    kotlin("jvm")
    // 2.12.0+ requires Gradle 9.0; this project is on 8.14. Pinned to the
    // last 2.x line that supports Gradle 8.x. Bump together with the wrapper.
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.song"
version = "0.0.1"

repositories {
    mavenLocal()
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        local(file("/Users/nate/Applications/Android Studio.app/Contents"))
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("org.jetbrains.android")
    }

    // Smoke target: pull koog onto the plugin classpath so the starter can touch
    // a koog class at runtime. If this load fails, we know the plugin classloader
    // collides with IntelliJ's bundled ktor / kotlinx-serialization and we need
    // shadowJar relocation before continuing the migration.
    implementation("ai.koog:koog-agents:0.8.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = "253.*"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Pass `smoker-poc` as the first program arg so IntelliJ's main dispatches to
// SmokerPocStarter on `./gradlew :plugin-poc:runIde`. Without this the IDE
// boots GUI-style and the ApplicationStarter never fires.
tasks.named<JavaExec>("runIde") {
    args("smoker-poc")
}
