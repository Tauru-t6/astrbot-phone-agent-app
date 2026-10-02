plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.tauru.astrbotphoneagent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tauru.astrbotphoneagent"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Release signing credentials are supplied by the build environment only.
    val releaseKey = System.getenv("PHONE_AGENT_KEYSTORE")
    if (!releaseKey.isNullOrBlank()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseKey)
                storePassword = System.getenv("PHONE_AGENT_STORE_PASSWORD")
                keyAlias = System.getenv("PHONE_AGENT_KEY_ALIAS") ?: "phone-agent"
                keyPassword = System.getenv("PHONE_AGENT_KEY_PASSWORD")
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    // 2024.12.01 = compose ui 1.7.6: accumulates the 1.7.x IME composition
    // fixes (OEM keyboards such as Honor/MagicOS regressed on 1.7.0).
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")

    // Logic layer: Shizuku for privileged phone control, kotlinx-serialization
    // for the app<->plugin JSON contract, OkHttp for the plugin/OpenAPI transport.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
