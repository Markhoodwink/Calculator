import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

val storeFilePath = keystoreProperties.getProperty("storeFile") ?: System.getenv("KEYSTORE_FILE")
val storePass = keystoreProperties.getProperty("storePassword") ?: System.getenv("KEYSTORE_PASSWORD")
val kAlias = keystoreProperties.getProperty("keyAlias") ?: System.getenv("KEY_ALIAS")
val kPass = keystoreProperties.getProperty("keyPassword") ?: System.getenv("KEY_PASSWORD")

android {
    namespace = "com.example.calculator"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.markhoodwink.mbcal"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "2.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("releaseConfig") {
            if (!storeFilePath.isNullOrBlank() && !storePass.isNullOrBlank() && !kAlias.isNullOrBlank() && !kPass.isNullOrBlank()) {
                storeFile = file(storeFilePath)
                storePassword = storePass
                keyAlias = kAlias
                keyPassword = kPass
            }
        }
    }

    flavorDimensions += "store"
    productFlavors {
        create("github") {
            dimension = "store"
            buildConfigField("boolean", "ENABLE_GITHUB_UPDATER", "true")
        }
        create("rustore") {
            dimension = "store"
            buildConfigField("boolean", "ENABLE_GITHUB_UPDATER", "false")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val relConfig = signingConfigs.findByName("releaseConfig")
            if (relConfig != null && relConfig.storeFile != null && relConfig.storeFile!!.exists()) {
                signingConfig = relConfig
            } else {
                signingConfig = signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation("androidx.camera:camera-core:1.4.1")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.32.0")
    testImplementation(libs.junit)
}
