package com.song.inspection

import com.intellij.codeInspection.ex.EntryPointsManagerBase
import com.intellij.openapi.project.Project

object InspectionEntryPoints {
    val ANNOTATIONS: List<String> = listOf(
        // Dagger / Hilt
        "dagger.Module",
        "dagger.Provides",
        "dagger.Binds",
        "dagger.BindsInstance",
        "dagger.BindsOptionalOf",
        "dagger.multibindings.IntoSet",
        "dagger.multibindings.IntoMap",
        "dagger.hilt.InstallIn",
        "dagger.hilt.EntryPoint",
        "dagger.hilt.android.AndroidEntryPoint",
        "dagger.hilt.android.HiltAndroidApp",
        "dagger.hilt.android.lifecycle.HiltViewModel",
        // javax.inject
        "javax.inject.Inject",
        "javax.inject.Qualifier",
        "javax.inject.Scope",
        "javax.inject.Singleton",
        // kotlinx.serialization — Response/DTO classes populated by reflection
        "kotlinx.serialization.Serializable",
        "kotlinx.serialization.SerialName",
        "kotlinx.serialization.Serializer",
        "kotlinx.serialization.UseSerializers",
        // Moshi
        "com.squareup.moshi.JsonClass",
        "com.squareup.moshi.Json",
        // Gson
        "com.google.gson.annotations.SerializedName",
        "com.google.gson.annotations.Expose",
        // Jackson
        "com.fasterxml.jackson.annotation.JsonProperty",
        "com.fasterxml.jackson.annotation.JsonCreator",
        // Room
        "androidx.room.Database",
        "androidx.room.Entity",
        "androidx.room.Dao",
        "androidx.room.TypeConverter",
        "androidx.room.TypeConverters",
        // Compose preview / runtime
        "androidx.compose.ui.tooling.preview.Preview",
        // Android WebView — methods invoked from JS
        "android.webkit.JavascriptInterface",
    )

    fun register(project: Project) {
        val mgr = EntryPointsManagerBase.getInstance(project)
        val current = mgr.ADDITIONAL_ANNOTATIONS
        val toAdd = ANNOTATIONS.filter { it !in current }
        if (toAdd.isEmpty()) {
            println("[entry-points] already registered")
            return
        }
        toAdd.forEach { current.add(it) }
        println("[entry-points] registered ${toAdd.size}: $toAdd")
    }
}
