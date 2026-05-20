package com.song.screen.datasource

/**
 * Curated catalog of "data-source leaf" classes/methods — types whose use
 * directly from a UI class indicates the architecture boundary has been
 * crossed (the UI is poking IO/persistence APIs instead of going through a
 * Repository / UseCase / ViewModel).
 *
 * Two flavors:
 *  - [TYPE_LEAVES] — any reference to one of these classes from UI code
 *    counts (IO clients that have no business being held directly by UI).
 *  - [METHOD_LEAVES] — only specific methods on the type count. Used when
 *    the type itself is too broad to flag blanket — e.g. `java.io.File`
 *    legitimately appears in UI as a parameter / path holder, but
 *    `file.readText()` from a Fragment is a smell.
 *
 * Add to these lists to broaden the scan.
 */
object DataSourceLeafCatalog {

    val TYPE_LEAVES: List<String> = listOf(
        // Persistence — key/value
        "android.content.SharedPreferences",
        "android.content.SharedPreferences.Editor",
        "androidx.datastore.core.DataStore",
        "androidx.datastore.preferences.core.MutablePreferences",
        // Persistence — relational
        "androidx.room.RoomDatabase",
        "androidx.sqlite.db.SupportSQLiteDatabase",
        "android.database.sqlite.SQLiteDatabase",
        "android.database.sqlite.SQLiteOpenHelper",
        "android.database.Cursor",
        // Networking
        "retrofit2.Retrofit",
        "retrofit2.Call",
        "okhttp3.OkHttpClient",
        "okhttp3.Call",
        "okhttp3.Request",
        "okhttp3.Response",
        // System services frequently abused from UI
        "android.location.LocationManager",
        "android.net.ConnectivityManager",
        "android.telephony.TelephonyManager",
        "android.os.PowerManager",
        "android.accounts.AccountManager",
        "android.content.ContentResolver",
    )

    val METHOD_LEAVES: List<MethodLeaf> = listOf(
        MethodLeaf(
            typeFqn = "java.io.File",
            methodNames = listOf(
                "readBytes", "readText", "readLines",
                "writeBytes", "writeText", "appendBytes", "appendText",
                "inputStream", "outputStream", "bufferedReader", "bufferedWriter",
                "forEachLine", "useLines",
            ),
        ),
    )

    /**
     * If the containing class extends one of these, the reference counts as a
     * UI → data-source violation. First match wins for labeling.
     */
    val UI_SUPERTYPES: List<String> = listOf(
        "android.app.Activity",
        "androidx.fragment.app.Fragment",
        "android.app.Fragment",
        "androidx.leanback.app.Fragment",
        "android.view.View",
        "androidx.recyclerview.widget.RecyclerView.Adapter",
        "androidx.recyclerview.widget.RecyclerView.ViewHolder",
    )

    /**
     * Containing classes that *look* like UI by inheritance but should not be
     * counted. `ViewModel` legitimately holds repository / data-source refs as
     * part of MVVM — including it would drown signal in expected noise.
     */
    val UI_EXCLUDE_SUPERTYPES: List<String> = listOf(
        "androidx.lifecycle.ViewModel",
    )

    const val COMPOSABLE_ANNOTATION_SHORT_NAME = "Composable"

    data class MethodLeaf(val typeFqn: String, val methodNames: List<String>)
}
