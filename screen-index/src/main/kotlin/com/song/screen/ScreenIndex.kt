package com.song.screen

import kotlinx.serialization.Serializable

@Serializable
enum class UnitType {
    ACTIVITY,
    FRAGMENT,
    DIALOG,
}

@Serializable
data class ScreenUnit(
    val fqn: String,
    val type: UnitType,
    val displayName: String,
    val filePath: String? = null,
    val manifestPath: String? = null,
    val isAbstract: Boolean = false,
)

@Serializable
data class ScreenIndex(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val generatedAtEpochMs: Long,
    val projectRoot: String,
    val units: List<ScreenUnit>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 5
    }
}
