plugins { kotlin("jvm") }
dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test)
}
tasks.test { useJUnitPlatform() }
