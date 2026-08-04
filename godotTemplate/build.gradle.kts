plugins {
    id("com.android.application")
}

android {
    namespace = "com.libreseed.pocketbuild.template"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.libreseed.pocketbuild.game"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        debug {
            isDebuggable = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
        )
    }
}

dependencies {
    implementation(files("libs/godot-lib.aar"))
    implementation("androidx.fragment:fragment:1.8.9")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
}
