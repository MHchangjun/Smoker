// Smoker plugin module — Android Studio tool window host.
// Bundles :core and :detekt-workflow into a single plugin zip.

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    // Pinned to last 2.x line that supports Gradle 8.x; bump alongside the wrapper.
    id("org.jetbrains.intellij.platform") version "2.11.0"
}

group = "com.song"
version = "0.1.0"

repositories {
    mavenLocal()
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// IntelliJ Platform (Kotlin plugin) already provides kotlin-stdlib at runtime.
// Bundling our own copy creates a split classpath: same kotlin.* classes loaded
// from two jars → NoSuchMethodError on internal APIs whose mangled names differ
// across versions. Exclude from user-facing classpaths only — `configurations.all`
// would also strip stdlib from the Kotlin compiler's own toolchain and break the
// build itself.
configurations.matching {
    it.name == "runtimeClasspath" ||
        it.name == "compileClasspath" ||
        it.name == "testRuntimeClasspath" ||
        it.name == "testCompileClasspath"
}.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
}

dependencies {
    intellijPlatform {
        local(file("/Users/nate/Applications/Android Studio.app/Contents"))
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("org.jetbrains.android")
    }

    implementation(project(":core"))
    implementation(project(":detekt-workflow"))
    implementation(project(":lint-workflow"))
    implementation(project(":inspection-workflow"))
    implementation(project(":screen-index"))

    testImplementation(kotlin("test"))
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = "253.*"
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

// The bundled Gemini plugin (com.google.tools.ij.aiplugin) calls
// AnalyticsSettings.getUserId() during early init. AnalyticsSettings is not
// initialized in sandbox runs (`idea.plugin.in.sandbox.mode=true`), so
// KbPrebuiltIndexerService throws and the IDE fails to start. Disable the
// plugin in the sandbox; production install on a real Android Studio leaves it.
val disabledPlugins = listOf(
    "com.google.tools.ij.aiplugin",
)
tasks.named("prepareSandbox") {
    doLast {
        val sandboxRoot = layout.buildDirectory.dir("idea-sandbox").get().asFile
        sandboxRoot.walkTopDown()
            .filter { it.isDirectory && it.name == "config" && it.parentFile?.name?.startsWith("AI-") == true }
            .forEach { configDir ->
                val disabledFile = configDir.resolve("disabled_plugins.txt")
                disabledFile.writeText(disabledPlugins.joinToString("\n"))
                logger.lifecycle("[smoker] wrote ${disabledFile.absolutePath}")
            }
    }
}

tasks.runIde {
    maxHeapSize = "8g"
    jvmArgs(
        "-XX:+UseG1GC",
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=${layout.buildDirectory.get()}/heap-dumps",
    )
}
