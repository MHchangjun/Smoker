package com.song.screen

import kotlinx.serialization.Serializable

@Serializable
enum class UnitType {
    HOST_ACTIVITY,
    LEAF_ACTIVITY,
    HOST_FRAGMENT,
    LEAF_FRAGMENT,
    DIALOG,
    OTHER_ACTIVITY,
}

@Serializable
enum class HostVia {
    LAYOUT_FRAGMENT_TAG,
    LAYOUT_FRAGMENT_CONTAINER,
    LAYOUT_VIEW_CLASS,
    LAYOUT_CUSTOM_TAG,
    NAV_GRAPH_DEST,
    NAV_HOST_TRANSPARENT,
}

@Serializable
data class HostEdge(
    val parentFqn: String,
    val childFqn: String,
    val via: HostVia,
    val sourceFile: String? = null,
)

@Serializable
data class LayerNode(
    val fqn: String,
    val displayName: String,
    val type: UnitType,
    val depth: Int,
    val filePath: String? = null,
    val parents: List<String> = emptyList(),
    val hosts: List<String> = emptyList(),
    val truncatedReason: String? = null,
)

@Serializable
data class ActivityLayerTree(
    val rootFqn: String,
    val displayName: String,
    val manifestPath: String? = null,
    val nodes: List<LayerNode>,
    val edges: List<HostEdge>,
    val truncated: Boolean = false,
    val truncationReason: String? = null,
)

@Serializable
data class ScreenIndex(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val generatedAtEpochMs: Long,
    val projectRoot: String,
    val screens: List<ActivityLayerTree>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
    }
}
