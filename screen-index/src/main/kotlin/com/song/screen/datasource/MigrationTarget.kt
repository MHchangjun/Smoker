package com.song.screen.datasource

/**
 * Resolved migration target for a single [UiDataSourceViolation].
 *
 * Computed by [MigrationTargetResolver] inside a smart-mode `ReadAction` right
 * before the prompt is built, so it reflects the current PSI state. The prompt
 * builder renders this into a "Target ViewModel" section so the LLM doesn't
 * have to discover the destination on its own.
 *
 * Three shapes the resolver can produce:
 *
 *  - **Non-Composable UI (Activity / Fragment / View / Adapter / ViewHolder):**
 *    [existingViewModels] is populated with whatever the UI class already
 *    references (`by viewModels()`, `ViewModelProvider(...)`, typed
 *    properties). Empty list = no existing ViewModel; the agent must create
 *    one following project convention.
 *
 *  - **Composable:** [composableHosts] enumerates every call site that
 *    transitively places this `@Composable` inside a `ViewModelStoreOwner`
 *    (Activity / Fragment / NavBackStackEntry / Dialog destination). The LLM
 *    picks how to scope the ViewModel — one shared, or per-host. [composableHosts]
 *    being empty means we could not pin down a host (e.g. library-only Composable)
 *    and the agent must fall back to its own search.
 *
 *  - **TYPE_DECL (field declaration on a UI class):** same as non-Composable —
 *    [existingViewModels] applies, [composableHosts] is empty.
 */
data class MigrationTarget(
    val existingViewModels: List<ExistingViewModel>,
    val composableHosts: List<HostCandidate>,
) {
    val hasExistingViewModel: Boolean get() = existingViewModels.isNotEmpty()

    companion object {
        val NONE: MigrationTarget = MigrationTarget(emptyList(), emptyList())
    }
}

/**
 * A ViewModel reference discovered on the UI class itself.
 *
 * @param fqn fully-qualified class name of the ViewModel, when statically determinable.
 *   Null if the property type was inferred or fully erased (e.g. `by viewModels()` with no generic).
 * @param accessor source code snippet showing how the UI obtains the ViewModel
 *   (e.g. `private val viewModel: HomeViewModel by viewModels()`).
 * @param filePath absolute path to the ViewModel's source file, if found in project sources.
 * @param fileLine 1-based line in [filePath] where the ViewModel class is declared, if known.
 */
data class ExistingViewModel(
    val fqn: String?,
    val accessor: String,
    val filePath: String?,
    val fileLine: Int?,
)

/**
 * A site that owns a `ViewModelStoreOwner` and transitively renders the
 * Composable that violates the rule. Either a class that hosts the Composable
 * (Activity / Fragment with a `ComposeView` / `setContent`) or a Navigation
 * destination (where the `NavBackStackEntry` itself is the owner).
 *
 * The [callChain] explains *why* this host counts — the list of intermediate
 * `@Composable` functions traversed from the violating Composable up to this
 * host, oldest call first. For a direct host the chain is empty.
 */
sealed class HostCandidate {
    abstract val callChain: List<ComposableCallHop>

    /**
     * Activity / Fragment / View whose body (or `setContent { ... }`)
     * eventually reaches the violating Composable.
     */
    data class ClassOwner(
        val classFqn: String,
        /** "Activity" / "Fragment" / "View" / "ComponentActivity" — best-effort label. */
        val classKind: String,
        val filePath: String,
        val line: Int,
        /** True when the Composable is rendered via `setContent { ... }` (Activity/Fragment ComposeView). */
        val viaSetContent: Boolean,
        override val callChain: List<ComposableCallHop>,
    ) : HostCandidate()

    /**
     * Navigation destination: `NavGraphBuilder.composable(route) { Xxx(...) }`.
     * `NavBackStackEntry` is the `ViewModelStoreOwner`; inside the destination
     * use `hiltViewModel()` / `viewModel()` to obtain a destination-scoped VM.
     */
    data class NavDestination(
        /** Best-effort route string ("home", "settings/{id}"), or a class FQN for typed routes. Null if unparseable. */
        val route: String?,
        /** Builder function used: `composable`, `dialog`, `bottomSheet`, … */
        val builderFn: String,
        val filePath: String,
        val line: Int,
        override val callChain: List<ComposableCallHop>,
    ) : HostCandidate()

    /**
     * A call site we could classify as a `@Composable` caller but whose
     * enclosing owner we could not pin down (e.g. defined in a library jar, or
     * the BFS hit the depth cap). Surface it so the LLM still sees "this
     * composable is used here" instead of silently dropping the call site.
     */
    data class Unresolved(
        val filePath: String,
        val line: Int,
        val reason: String,
        override val callChain: List<ComposableCallHop>,
    ) : HostCandidate()
}

/**
 * One hop in the chain of `@Composable` functions traversed from the violating
 * Composable up to a [HostCandidate]. Helps the LLM understand the rendering
 * path when picking VM scope.
 */
data class ComposableCallHop(
    val functionName: String,
    val filePath: String?,
    val line: Int,
)
