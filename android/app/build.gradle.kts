plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "nl.vitaal.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "nl.vitaal.app"
        minSdk = 28            // Health Connect werkt vanaf Android 9
        targetSdk = 36
        // In GitHub Actions telt het buildnummer op, zodat elke APK over de vorige heen te installeren is
        versionCode = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 2
        versionName = "1.13.0"
    }

    // Release-ondertekening: GitHub Actions zet de sleutel klaar (vaste sleutel uit de geheimen,
    // of een tijdelijke als die ontbreekt). Lokaal zonder deze variabelen wordt release niet ondertekend.
    val keystore = System.getenv("VC_KEYSTORE_FILE")
    signingConfigs {
        create("release") {
            if (keystore != null) {
                storeFile = file(keystore)
                storeType = "PKCS12"
                storePassword = System.getenv("VC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VC_KEY_ALIAS")
                keyPassword = System.getenv("VC_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // R8: kleinere en snellere app. Regels voor de bibliotheken in proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystore != null) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

// APK heet VitalConnect-<versie>[-build<n>]-debug.apk
base {
    archivesName.set("VitalConnect-" + android.defaultConfig.versionName + (System.getenv("BUILD_NUMBER")?.let { "-build$it" } ?: ""))
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Inloggen met Google via Credential Manager
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
