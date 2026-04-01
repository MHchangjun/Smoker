package com.song.agent.subgraph

import ai.koog.agents.core.dsl.builder.AIAgentSubgraphBuilderBase
import ai.koog.agents.core.dsl.builder.forwardTo
import ai.koog.agents.core.dsl.extension.*
import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ActivityInput(
    val projectRoot: String,
    val activity: String,
)

@Serializable
@SerialName("ActivityScreenResult")
@LLMDescription("Result of analyzing a single Activity to discover what screens it hosts and how it navigates between them.")
data class ActivityScreenResult(
    @property:LLMDescription("Fully qualified class name of the analyzed Activity")
    val activity: String = "",
    @property:LLMDescription("How this Activity hosts screens: nav_host, nav_host_dynamic, compose_nav, fragment_transaction, self, or custom")
    val mechanism: String = "",
    @property:LLMDescription("Path to the navigation source file (nav_graph XML or Compose nav file). Null if mechanism is self or custom.")
    val source: String? = null,
    @property:LLMDescription("Additional explanation when mechanism is custom or when something unusual was found")
    val note: String? = null,
    @property:LLMDescription("All screens hosted by this Activity. Excludes dialogs and bottom sheets.")
    val screens: List<DiscoveredScreen> = emptyList(),
)

@Serializable
@SerialName("DiscoveredScreen")
@LLMDescription("A single user-facing screen found inside an Activity")
data class DiscoveredScreen(
    @property:LLMDescription("Simple class name or composable function name (e.g. HomeFragment, ContentDetailScreen)")
    val name: String = "",

    @SerialName("class")
    @property:LLMDescription("Fully qualified class name. Null if composable function without a class.")
    val clazz: String? = null,

    @property:LLMDescription("Screen type: fragment, composable, or activity")
    val type: String = "",

    @property:LLMDescription("Additional note when screen class could not be fully resolved")
    val note: String? = null
)

internal fun AIAgentSubgraphBuilderBase<ActivityInput, ActivityScreenResult>.activitySubgraph() =
    subgraph<ActivityInput, ActivityScreenResult>(
        name = "module_discovery_subgraph"
    ) {
        val buildUserPrompt by node<ActivityInput, String>("build_user_prompt") { input ->
            buildPrompt(input)
        }

        val nodeCallLLM by nodeLLMRequest("call_llm")
        val nodeExecuteTool by nodeExecuteTool("execute_tool")
        val nodeSendToolResult by nodeLLMSendToolResult("send_tool_result")
        val nodeRequestStructured by nodeLLMRequestStructured<ActivityScreenResult>(
            name = "request_structured_output"
        )

        edge(nodeStart forwardTo buildUserPrompt)
        edge(buildUserPrompt forwardTo nodeCallLLM)

        edge(nodeCallLLM forwardTo nodeExecuteTool onToolCall { true })
        edge(
            nodeCallLLM forwardTo nodeRequestStructured
                    onAssistantMessage { true }
                    transformed {
                "Based on all previous tool results, return the final module discovery in the required schema."
            }
        )

        edge(nodeExecuteTool forwardTo nodeSendToolResult)
        edge(nodeSendToolResult forwardTo nodeExecuteTool onToolCall { true })
        edge(
            nodeSendToolResult forwardTo nodeRequestStructured
                    onAssistantMessage { true }
                    transformed {
                "Based on all previous tool results, return the final module discovery in the required schema."
            }
        )

        edge(
            nodeRequestStructured forwardTo nodeFinish
                    transformed { result ->
                result.getOrElse { throwable ->
                    throw IllegalStateException("Failed to create structured module discovery output", throwable)
                }.data
            }
        )
    }

private fun buildPrompt(
    input: ActivityInput
): String = """
# Activity Screen Discovery

You are an Android codebase analyst. Your task is to analyze a single Activity and discover all user-facing screens it hosts.

## Input

- Project root: `${input.projectRoot}`
- Activity class: `${input.activity}`

## Background: How Android Activities host screens

An Activity is a container. It can host one screen or many screens. The key is to find **what mechanism** the Activity uses and **what screens** are registered through that mechanism.

### Pattern 1: XML Navigation (Navigation Component)

The Activity's layout contains a `NavHostFragment`, which points to a nav graph XML file. That XML file lists all screens.

**Activity layout example:**
```xml
<androidx.fragment.app.FragmentContainerView
    android:name="androidx.navigation.fragment.NavHostFragment"
    app:navGraph="@navigation/nav_main"
    app:defaultNavHost="true" />
```

**Nav graph XML example (`res/navigation/nav_main.xml`):**
```xml
<navigation xmlns:android="http://schemas.android.com/apk/res/android"
    app:startDestination="@id/homeFragment">

    <!-- These are screens -->
    <fragment
        android:id="@+id/homeFragment"
        android:name="com.example.HomeFragment" />
    <fragment
        android:id="@+id/searchFragment"
        android:name="com.example.SearchFragment" />
    <activity
        android:id="@+id/settingsActivity"
        android:name="com.example.SettingsActivity" />

    <!-- These are NOT screens (overlays on current screen) -->
    <dialog
        android:id="@+id/infoDialog"
        android:name="com.example.InfoDialog" />
</navigation>
```

Screens = `<fragment>` and `<activity>` tags. Exclude `<dialog>`.

A nav graph can include other nav graphs:
```xml
<include app:graph="@navigation/nav_settings" />
```
Follow these includes and collect screens from them too.

### Pattern 2: Jetpack Compose Navigation

The Activity calls `setContent { }` and somewhere inside a `NavHost` is defined. Each `composable()` call is a screen.

**Activity example:**
```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppTheme {
                AppNavHost()  // navigation setup might be in another file
            }
        }
    }
}
```

**NavHost example (could be in a separate file):**
```kotlin
@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    NavHost(navController = navController, startDestination = "home") {
        composable("home") { HomeScreen() }           // ← screen
        composable("search") { SearchScreen() }       // ← screen
        composable("detail/{id}") { DetailScreen() }  // ← screen

        dialog("confirm") { ConfirmDialog() }         // ← NOT a screen
        bottomSheet("filter") { FilterSheet() }       // ← NOT a screen
    }
}
```

Screens = `composable()` calls. Exclude `dialog()` and `bottomSheet()`.

The NavHost setup may not be directly in the Activity file. It could be in a separate Composable file called from `setContent`. Follow the call chain.

Also, `NavGraphBuilder` extension functions are common:
```kotlin
fun NavGraphBuilder.settingsGraph() {
    composable("settings") { SettingsScreen() }
    composable("account") { AccountScreen() }
}
```
These are called inside a `NavHost` block and their `composable()` entries are also screens.

### Pattern 3: Manual Fragment transactions

Older or simpler Activities manage Fragments manually. The layout has a `FrameLayout` or `FragmentContainerView` (without `navGraph`), and the Activity source uses `FragmentTransaction` to swap Fragments.

```kotlin
supportFragmentManager.beginTransaction()
    .replace(R.id.container, HomeFragment())
    .commit()
```

Search the Activity source for `replace(`, `add(`, `show(`, `FragmentTransaction` to find which Fragment classes are used. Each Fragment being `replace()`d into the container is a screen.

### Pattern 4: Single-screen Activity

The Activity has its own layout with actual UI (not just a container). No Fragment host, no Compose. The Activity itself is the only screen.

```kotlin
class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setContentView(R.layout.activity_login)
        // layout contains actual UI: EditTexts, Buttons, etc.
    }
}
```

### Pattern 5: Dynamic nav graph

Some Activities are generic hosts that receive the nav graph at runtime via Intent:

```kotlin
val graphId = intent.getIntExtra("graph", 0)
val navController = findNavController(R.id.nav_host)
navController.setGraph(graphId)
```

For these, search for where this Activity is started to find which graph IDs are passed.

### Pattern 6: Custom / project-specific

Some projects have their own navigation abstraction (enum-based routers, custom Navigator classes, etc.). If none of the above patterns match, read the Activity source carefully and describe what you find.

### What is NOT a screen

- `DialogFragment`, `BottomSheetDialogFragment` — overlays, not screens
- `dialog()`, `bottomSheet()` in Compose NavHost — overlays, not screens
- `<dialog>` in nav graph XML — overlays, not screens  
- ViewPager/ViewPager2 pages — tabs within one screen, not separate screens
- TabLayout tabs — same, part of one screen

## Task

1. Read the Activity source file
2. Determine which pattern (or combination) it uses
3. Follow the chain to find all screens
4. Return the result as JSON

## Output Schema

```json
{
  "activity": "com.example.app.MainActivity",
  "mechanism": "nav_host | nav_host_dynamic | compose_nav | fragment_transaction | self | custom",
  "source": "res/navigation/nav_main.xml",
  "note": null,
  "screens": [
    {
      "name": "HomeFragment",
      "class": "com.example.feature.home.HomeFragment",
      "type": "fragment | composable | activity"
    }
  ]
}
```

## Rules

- Only read files needed to follow the Activity → layout → navigation host → screens chain.
- Do NOT trace navigation edges between screens.
- Exclude all dialogs and bottom sheets.
- Use absolute paths in all tool calls.
- Output valid JSON only.
""".trimIndent()