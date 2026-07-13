pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "InsightTheRoom"
include(":core")
include(":persistence")
include(":export-core")
include(":floorplan")
include(":export-android")
