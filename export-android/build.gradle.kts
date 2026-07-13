plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}
android {
    namespace = "itr.export.android"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}
dependencies {
    implementation(project(":core")); implementation(project(":export-core"))
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit4); testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core); testImplementation(libs.kotlin.test)
}
