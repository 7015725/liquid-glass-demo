pluginManagement { repositories { mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { mavenCentral() } }
rootProject.name = "FlowglassEngineTests"
include(":engine")
project(":engine").projectDir = file("..")
// Do not share incremental compiler outputs with the Android root build.
val isolatedEngineBuild = file("build/engine")
gradle.beforeProject {
    if (path == ":engine") layout.buildDirectory.set(isolatedEngineBuild)
}
