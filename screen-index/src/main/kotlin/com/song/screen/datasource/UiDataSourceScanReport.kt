package com.song.screen.datasource

import kotlinx.serialization.Serializable

@Serializable
enum class LeafMatchKind {
    /** UI class (method body) references a leaf type. */
    TYPE,
    /** UI class (method body) calls a leaf method (`file.readText()`, …). */
    METHOD,
    /** Top-level `@Composable` function references a leaf type. */
    COMPOSABLE_TYPE,
    /** Top-level `@Composable` function calls a leaf method. */
    COMPOSABLE_METHOD,
    /** UI class has a field / property declared with leaf type. */
    TYPE_DECL,
}

/**
 * One hop on the reachability chain from UI → leaf. Methods are shown as
 * `ClassName.methodName`; leaves are shown by short FQN (`TelephonyManager`
 * or `File#readText`).
 */
@Serializable
data class ViolationHop(
    val displayName: String,
    /** Class FQN for class/method hops; null for `TYPE_DECL` field hop or unresolved cases. */
    val classFqn: String?,
    /** Method name for method hops; null for class hops and leaf-type hops. */
    val methodName: String? = null,
    /** Source file for method/wrapper hops in project; null for SDK leaves and top-level. */
    val filePath: String?,
)

@Serializable
data class UiDataSourceViolation(
    /** Leaf identifier (`"android.content.SharedPreferences"` or `"java.io.File#readText"`). */
    val leaf: String,
    val matchKind: LeafMatchKind,
    val callerClassFqn: String?,
    /** Coarse classification: Activity / Fragment / View / Adapter / ViewHolder / Composable. */
    val callerKind: String,
    /** Source file of the UI hop. */
    val callerFile: String,
    /** Line in [callerFile] where the violation surfaces. */
    val callerLine: Int,
    val callerExcerpt: String,
    /**
     * Reachability chain — `[UI, intermediate methods…, leaf-touching method, leaf]`.
     * `hopDistance == 1` means UI method directly references the leaf.
     * Higher distances mean transitive reach through wrapper methods.
     */
    val chain: List<ViolationHop>,
    val hopDistance: Int,
)

@Serializable
data class UiDataSourceScanReport(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val generatedAtEpochMs: Long,
    val projectRoot: String,
    val violations: List<UiDataSourceViolation>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 3
    }
}
