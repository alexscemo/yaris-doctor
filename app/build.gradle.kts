plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yarisdoctor"
    compileSdk = 36 // Android 16 Live Update APIs; targetSdk stays 35
    defaultConfig {
        applicationId = "com.yarisdoctor"
        minSdk = 31
        targetSdk = 35
        // CI sets these from the run number and the git tag; Obtainium compares versionName to the release tag
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "dev"
    }
    // Release signing only exists in CI (keystore comes from repo secrets); every release must use the same key
    System.getenv("KEYSTORE_PATH")?.let { path ->
        signingConfigs.create("release") {
            storeFile = file(path)
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = "yaris"
            keyPassword = System.getenv("KEYSTORE_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    // Db's tests run against real SQLite on the JVM (Robolectric), so the naming and query logic is
    // covered in CI instead of only on a phone.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":domain"))
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.6.1")
}
