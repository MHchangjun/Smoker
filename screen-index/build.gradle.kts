plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.intellij.platform.module")
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
        bundledPlugin("com.intellij.java")
    }

    api(project(":core"))
    api(project(":workflow-core"))

    implementation("io.insert-koin:koin-core:4.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

kotlin {
    jvmToolchain(21)
}
