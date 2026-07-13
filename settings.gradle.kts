pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "InsightTheRoom"
include(":core")
include(":core-arcore")
include(":persistence")
include(":export-core")
include(":floorplan")
include(":export-android")
include(":feature-scan")
