plugins {
    kotlin("jvm")
}
dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
tasks.test { useJUnitPlatform() }
