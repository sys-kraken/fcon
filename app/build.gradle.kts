import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: keystore.properties (git-ignored) holds storeFile, storePassword, keyAlias, keyPassword.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.fcon"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.fcon"
        minSdk = 29
        targetSdk = 34
        // Pass -PappVersion=1.2.3 to build another version. versionCode grows with it, which Android needs for updates.
        val appVersion = (findProperty("appVersion") as String?) ?: "1.1.0"
        val parts = appVersion.split(".").map { it.toInt() }
        versionName = appVersion
        versionCode = parts[0] * 10000 + parts[1] * 100 + parts[2]
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    // One APK per CPU type keeps each download small (python + ffmpeg are bundled).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    // yt-dlp's python/ffmpeg are native executables, so they must be extracted to disk at install time.
    packaging {
        jniLibs { useLegacyPackaging = true }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.1")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.1")
}
