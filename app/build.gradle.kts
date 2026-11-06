plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.metavitae.marcohunt"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.metavitae.marcohunt"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
    }

    // Test-only key (not a release key) kept in the repo so every CI build can
    // update-install over the previous one on the test phones.
    signingConfigs {
        getByName("debug") {
            storeFile = file("marcohunt-debug.jks")
            storePassword = "android"
            keyAlias = "marcohunt"
            keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("debug") }
    }
    // The rules engine is plain Java, shared with the simulation and the tests.
    sourceSets["main"].java.srcDirs("src/main/java", "../engine/src/main/java")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
