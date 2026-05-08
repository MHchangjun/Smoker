rootProject.name = "Smoker"

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

include("agent-tool")
include("core")
include("workflow-core")
include("detekt-workflow")
include("lint-workflow")
include("inspection-workflow")
include("plugin-poc")
