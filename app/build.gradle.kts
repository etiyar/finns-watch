plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val ciRunId = System.getenv("GITHUB_RUN_ID") ?: "yerel"
val ciRunAttempt = System.getenv("GITHUB_RUN_ATTEMPT") ?: "1"
val ciCommit = System.getenv("GITHUB_SHA")?.take(7) ?: "yerel"
val ciBranch = System.getenv("BUILD_SOURCE_BRANCH") ?: "yerel"
fun buildString(value: String) = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

android {
    namespace = "com.che.ls05"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.che.ls05"
        minSdk = 26
        targetSdk = 35
        versionCode = ciRunNumber?.let { 1000 + it } ?: 34
        versionName = "0.15.0-beta4"
        buildConfigField("String", "BUILD_NUMBER", buildString(ciRunNumber?.toString() ?: "yerel"))
        buildConfigField("String", "BUILD_RUN", buildString(if (ciRunNumber == null) "yerel" else "$ciRunId-$ciRunAttempt"))
        buildConfigField("String", "BUILD_COMMIT", buildString(ciCommit))
        buildConfigField("String", "BUILD_BRANCH", buildString(ciBranch))
    }

    buildFeatures { buildConfig = true }

    // Sabit imza: CI anahtarı .signing/ altında üretir ve önbellekte tutar (depoya girmez, .gitignore).
    // Dosya yoksa (ör. yerel Android Studio) varsayılan test anahtarı kullanılır.
    signingConfigs {
        getByName("debug") {
            val ks = rootProject.file(".signing/ls05-debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":protocol"))
}
