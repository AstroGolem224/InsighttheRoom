import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "itr.scan"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
    androidResources { noCompress += "tflite" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val verifyEfficientDetModel by tasks.registering {
    val model = layout.projectDirectory.file("src/main/assets/efficientdet_lite0.tflite")
    inputs.file(model)
    doLast {
        val expected = "40338edf5ec70d43e318b0a716a84d4564cd1802759a7a07170c7e43796dbf58"
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(model.asFile.readBytes())
            .joinToString("") { "%02x".format(it) }
        check(actual == expected) { "efficientdet_lite0.tflite SHA-256 mismatch: $actual" }
    }
}
tasks.named("preBuild") { dependsOn(verifyEfficientDetModel) }

dependencies {
    implementation(project(":core"))
    implementation(project(":core-arcore"))
    implementation(project(":persistence"))
    implementation(project(":floorplan"))
    implementation(project(":export-core"))
    implementation(project(":export-android"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.androidx.activity)
    implementation(libs.mediapipe.tasks.vision)
    implementation(libs.sceneview.arsceneview)
}
