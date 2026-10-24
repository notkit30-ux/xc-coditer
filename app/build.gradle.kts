plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "app.xccoditer"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.xccoditer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Python 3.12 (Chaquopy) exists only for 64-bit CPUs; each CPU type adds roughly 10+ MB to the APK.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    // Release signing reads the key from environment variables only (CI secrets). Nothing secret lives in this repo.
    // Without them the release build is simply unsigned. See docs/RELEASE.md.
    val ksPath: String? = System.getenv("XC_KEYSTORE_PATH")
    signingConfigs {
        if (ksPath != null) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = System.getenv("XC_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("XC_KEY_ALIAS")
                keyPassword = System.getenv("XC_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (ksPath != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
                "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/AL2.0", "META-INF/LGPL2.1",
            )
        }
    }
}

chaquopy {
    defaultConfig { version = "3.12" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Git (JGit 5.13.x is the Java 8 / Android-compatible line). Network is used only when you run clone/push/pull.
    implementation("org.eclipse.jgit:org.eclipse.jgit:5.13.3.202401111512-r")
    implementation("org.slf4j:slf4j-nop:1.7.36")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("androidx.documentfile:documentfile:1.0.1")

    testImplementation("junit:junit:4.13.2")
}
