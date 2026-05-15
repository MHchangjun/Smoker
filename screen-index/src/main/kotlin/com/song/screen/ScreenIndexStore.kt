package com.song.screen

import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

class ScreenIndexStore {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun pathFor(projectRoot: File): File = File(projectRoot, ".smoker/screen-index.json")

    fun save(projectRoot: File, index: ScreenIndex): File {
        val target = pathFor(projectRoot)
        target.parentFile?.mkdirs()
        target.writeText(json.encodeToString(index))
        return target
    }

    fun load(projectRoot: File): ScreenIndex? {
        val target = pathFor(projectRoot)
        if (!target.exists()) return null
        return runCatching { json.decodeFromString<ScreenIndex>(target.readText()) }
            .getOrNull()
            ?.takeIf { it.schemaVersion == ScreenIndex.CURRENT_SCHEMA_VERSION }
    }
}

fun refreshProjectRootInVfs(projectRoot: File) {
    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(projectRoot)
}
