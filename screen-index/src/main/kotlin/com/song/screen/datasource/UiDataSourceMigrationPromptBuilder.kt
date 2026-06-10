package com.song.screen.datasource

/**
 * Builds the prompt fed to [com.song.agent.UiDataSourceMigrationAgent].
 *
 * The service drives one violation at a time, so this builder is typically called with a
 * single-element `violations` list. The list signature is kept so a caller can opt back
 * into batching a UI class's violations without changing the builder.
 *
 * Pass a [MigrationTarget] resolved by [MigrationTargetResolver] so the LLM doesn't
 * have to rediscover the destination — existing ViewModel handles for the UI class,
 * or every `ViewModelStoreOwner` host site for a `@Composable`.
 */
class UiDataSourceMigrationPromptBuilder {

    fun build(
        uiClassFqn: String?,
        callerFile: String,
        violations: List<UiDataSourceViolation>,
        target: MigrationTarget = MigrationTarget.NONE,
    ): String {
        val displayCaller = uiClassFqn ?: callerFile
        val kind = violations.firstOrNull()?.callerKind ?: "UI"
        val single = violations.size == 1
        val isComposable = kind.equals("Composable", ignoreCase = true)

        return buildString {
            val noun = if (single) "data-source access" else "data-source accesses"
            appendLine("Migrate the $noun listed below out of the UI class")
            appendLine("`$displayCaller` (a $kind) and into its ViewModel.")
            appendLine()
            appendLine("**Source file:** $callerFile")
            appendLine()
            appendLine(if (single) "## Violation" else "## Violations (${violations.size})")
            appendLine()
            violations
                .sortedBy { it.callerLine }
                .forEachIndexed { i, v ->
                    val header = if (single)
                        "line ${v.callerLine} — leaf `${v.leaf}` (${v.hopDistance}-hop, ${v.matchKind})"
                    else
                        "### ${i + 1}. line ${v.callerLine} — leaf `${v.leaf}` (${v.hopDistance}-hop, ${v.matchKind})"
                    appendLine(header)
                    appendLine()
                    appendLine("```kotlin")
                    appendLine(v.callerExcerpt)
                    appendLine("```")
                    appendLine()
                    appendLine("Reachability chain:")
                    appendLine()
                    appendLine("`${renderChain(v)}`")
                    appendLine()
                }

            appendTargetSection(this, target, isComposable, displayCaller)

            appendLine("## What to do")
            appendLine()
            appendLine(
                if (single)
                    "- Migrate this access following the workflow defined in the system prompt."
                else
                    "- For each violation above, follow the workflow defined in the system prompt.",
            )
            if (isComposable) {
                appendLine("- Place the ViewModel inside the host that owns the `ViewModelStoreOwner`")
                appendLine("  (see the **Target host** section). Inside a Composable destination obtain it")
                appendLine("  via `hiltViewModel()` / `viewModel()`; for an Activity/Fragment host pass it in as")
                appendLine("  a parameter or obtain it at the call site.")
                appendLine("- If several hosts are listed, prefer one shared ViewModel scoped to the nearest")
                appendLine("  common owner; only split per host if the state genuinely differs.")
            } else {
                appendLine("- Land the change in the ViewModel for `$displayCaller`. Reuse the ViewModel listed")
                appendLine("  in the **Target ViewModel** section if any; otherwise create one matching the")
                appendLine("  project's convention.")
            }
            appendLine("- The ViewModel calls the same wrapper / util / object the UI used to call")
            appendLine("  (e.g. `PrefUtil.getBoolean`, `NetworkUtil.isOnline`, `DataProvider<X>(...).request()`).")
            appendLine("  Do NOT introduce a Repository, interface, or any new abstraction layer.")
            appendLine("- Leaves accessed inside `attachBaseContext` are exempt — skip them per Rule 5.")
            appendLine()
            appendLine("Finish with a single-line summary in this format:")
            appendLine()
            appendLine("`migrate(ui-datasource): $displayCaller → <ViewModel>  (<N> leaf accesses moved)`")
        }.trimEnd()
    }

    private fun appendTargetSection(
        sb: StringBuilder,
        target: MigrationTarget,
        isComposable: Boolean,
        displayCaller: String,
    ) {
        if (isComposable) {
            sb.appendLine("## Target host (ViewModelStoreOwner candidates)")
            sb.appendLine()
            if (target.composableHosts.isEmpty()) {
                sb.appendLine("- _Resolver could not pin down a host for `$displayCaller`._")
                sb.appendLine("  Use `${com.song.agent.tool.ToolNames.GREP}` to find every call site of this Composable,")
                sb.appendLine("  then walk up to the nearest Activity / Fragment / `NavGraphBuilder.composable { … }`")
                sb.appendLine("  destination.")
                sb.appendLine()
                return
            }
            sb.appendLine("The Composable is rendered from the host(s) below. Pick the ViewModel scope to match:")
            sb.appendLine()
            target.composableHosts.forEachIndexed { i, host ->
                sb.appendLine("${i + 1}. ${renderHost(host)}")
                if (host.callChain.isNotEmpty()) {
                    sb.appendLine("   composition path: ${renderCallChain(host.callChain)}")
                }
            }
            sb.appendLine()
            return
        }

        // Non-Composable UI: show ViewModel handle(s) we found on the UI class.
        sb.appendLine("## Target ViewModel")
        sb.appendLine()
        if (target.existingViewModels.isEmpty()) {
            sb.appendLine("- No existing ViewModel reference found on `$displayCaller`.")
            sb.appendLine("  Create one following the project's convention (Hilt vs Koin, package, base class).")
            sb.appendLine()
            return
        }
        if (target.existingViewModels.size == 1) {
            sb.appendLine("Use the ViewModel already wired to this UI class:")
        } else {
            sb.appendLine("This UI already references ${target.existingViewModels.size} ViewModels — pick the one")
            sb.appendLine("that semantically matches the data being moved:")
        }
        sb.appendLine()
        target.existingViewModels.forEachIndexed { i, vm ->
            sb.appendLine("${i + 1}. ${renderViewModel(vm)}")
        }
        sb.appendLine()
    }

    private fun renderHost(host: HostCandidate): String = when (host) {
        is HostCandidate.ClassOwner -> {
            val via = if (host.viaSetContent) " — rendered via `setContent { … }`" else ""
            "**${host.classKind}** `${host.classFqn}` at ${shortPath(host.filePath)}:${host.line}$via"
        }
        is HostCandidate.NavDestination -> {
            val route = host.route?.let { " route=`$it`" } ?: " (route unparseable)"
            "**Nav destination** (`${host.builderFn} { … }`) at " +
                "${shortPath(host.filePath)}:${host.line}$route — `NavBackStackEntry` is the owner; " +
                "use `hiltViewModel()` / `viewModel()` inside the destination."
        }
        is HostCandidate.Unresolved -> {
            "Call site at ${shortPath(host.filePath)}:${host.line} — could not classify (${host.reason})"
        }
    }

    private fun renderViewModel(vm: ExistingViewModel): String {
        val location = when {
            vm.filePath != null && vm.fileLine != null -> " — declared at ${shortPath(vm.filePath)}:${vm.fileLine}"
            vm.fqn != null -> ""
            else -> " — type erased (delegate without generic)"
        }
        val name = vm.fqn ?: "(unknown ViewModel type)"
        return "`$name`$location\n   accessor: `${vm.accessor}`"
    }

    private fun renderCallChain(chain: List<ComposableCallHop>): String =
        chain.joinToString(separator = " ← ") { hop ->
            val loc = if (hop.filePath != null && hop.line > 0) "@${shortPath(hop.filePath)}:${hop.line}" else ""
            "`${hop.functionName}`$loc"
        }

    private fun shortPath(path: String): String {
        val idx = path.lastIndexOf("/src/")
        return if (idx >= 0) path.substring(idx + 1) else path
    }

    private fun renderChain(v: UiDataSourceViolation): String =
        v.chain.joinToString(separator = "  →  ") { it.displayName }
}
