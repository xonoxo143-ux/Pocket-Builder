#!/usr/bin/env bash
set -euo pipefail

ROOT=${1:-PocketHost-Kotlin-Gradle-Test}
REPO_ROOT=$(pwd)
rm -rf "$ROOT"
mkdir -p "$ROOT/app/src/main/java/com/libreseed/kotlinbuildtest"
mkdir -p "$ROOT/app/src/main/res/layout" "$ROOT/app/src/main/res/values" "$ROOT/app/src/main/res/drawable"
mkdir -p "$ROOT/gradle/wrapper"

cat > "$ROOT/settings.gradle.kts" <<'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PocketHostKotlinGradleTest"
include(":app")
EOF

cat > "$ROOT/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application") version "9.1.1" apply false
}
EOF

cat > "$ROOT/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8
org.gradle.daemon=false
org.gradle.parallel=false
android.useAndroidX=false
kotlin.code.style=official
EOF

cat > "$ROOT/app/build.gradle.kts" <<'EOF'
plugins {
    id("com.android.application")
}

android {
    namespace = "com.libreseed.kotlinbuildtest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.libreseed.kotlinbuildtest"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "TEST_ID", "\"POCKETHOST-KOTLIN-GRADLE-V1\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}
EOF

cat > "$ROOT/app/proguard-rules.pro" <<'EOF'
# No custom shrinking rules are required for this test app.
EOF

cat > "$ROOT/app/src/main/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="true"
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@drawable/ic_launcher"
        android:supportsRtl="true"
        android:theme="@style/Theme.KotlinBuildTest">
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
EOF

cat > "$ROOT/app/src/main/java/com/libreseed/kotlinbuildtest/MainActivity.kt" <<'EOF'
package com.libreseed.kotlinbuildtest

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private var runCount: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        runCount = savedInstanceState?.getInt(KEY_RUN_COUNT) ?: 0

        val status = findViewById<TextView>(R.id.statusText)
        val details = findViewById<TextView>(R.id.detailsText)
        val runButton = findViewById<Button>(R.id.runTestButton)
        val resetButton = findViewById<Button>(R.id.resetButton)

        status.text = getString(R.string.initial_status)
        details.text = buildDetails()

        runButton.setOnClickListener {
            runCount += 1
            val checks = listOf(
                "Kotlin bytecode executed",
                "XML layout inflated",
                "Android resources resolved",
                "Click listener invoked",
                "Generated BuildConfig loaded",
            )
            status.text = checks.mapIndexed { index, check ->
                "${index + 1}. ✓ $check"
            }.joinToString(separator = "\n")
            details.text = buildDetails()
            Toast.makeText(this, getString(R.string.pass_toast, runCount), Toast.LENGTH_SHORT).show()
        }

        resetButton.setOnClickListener {
            runCount = 0
            status.text = getString(R.string.initial_status)
            details.text = buildDetails()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_RUN_COUNT, runCount)
        super.onSaveInstanceState(outState)
    }

    private fun buildDetails(): String {
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"
        return getString(
            R.string.build_details,
            BuildConfig.TEST_ID,
            BuildConfig.VERSION_NAME,
            BuildConfig.BUILD_TYPE,
            Build.VERSION.SDK_INT,
            primaryAbi,
            runCount,
        )
    }

    companion object {
        private const val KEY_RUN_COUNT = "run_count"
    }
}
EOF

cat > "$ROOT/app/src/main/res/layout/activity_main.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:fillViewport="true"
    android:background="@color/page_background">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="24dp">

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="@string/title"
            android:textColor="@color/text_primary"
            android:textSize="30sp"
            android:textStyle="bold" />

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="@string/subtitle"
            android:textColor="@color/text_secondary"
            android:textSize="16sp" />

        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:background="@drawable/card_background"
            android:orientation="vertical"
            android:padding="20dp">

            <TextView
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="@string/runtime_status_label"
                android:textColor="@color/accent"
                android:textSize="13sp"
                android:textStyle="bold" />

            <TextView
                android:id="@+id/statusText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="10dp"
                android:lineSpacingExtra="4dp"
                android:textColor="@color/text_primary"
                android:textSize="17sp" />

            <TextView
                android:id="@+id/detailsText"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="18dp"
                android:fontFamily="monospace"
                android:textColor="@color/text_secondary"
                android:textIsSelectable="true"
                android:textSize="13sp" />
        </LinearLayout>

        <Button
            android:id="@+id/runTestButton"
            android:layout_width="match_parent"
            android:layout_height="56dp"
            android:layout_marginTop="24dp"
            android:text="@string/run_test"
            android:textAllCaps="false"
            android:textSize="17sp" />

        <Button
            android:id="@+id/resetButton"
            style="?android:attr/buttonStyle"
            android:layout_width="match_parent"
            android:layout_height="52dp"
            android:layout_marginTop="10dp"
            android:text="@string/reset"
            android:textAllCaps="false" />

        <TextView
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginTop="24dp"
            android:text="@string/success_explanation"
            android:textColor="@color/text_secondary"
            android:textSize="14sp" />
    </LinearLayout>
</ScrollView>
EOF

cat > "$ROOT/app/src/main/res/values/strings.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Kotlin Build Test</string>
    <string name="title">Kotlin / Gradle Test</string>
    <string name="subtitle">Built from a normal Android Gradle project. No Godot template or precompiled app payload.</string>
    <string name="runtime_status_label">RUNTIME STATUS</string>
    <string name="initial_status">✓ MainActivity.kt loaded successfully. Tap the test button to exercise Kotlin logic and Android resources.</string>
    <string name="run_test">Run Kotlin test</string>
    <string name="reset">Reset</string>
    <string name="pass_toast">Test pass #%1$d</string>
    <string name="success_explanation">If this screen installed and the test button works, PocketHost completed Gradle wrapper execution, Android resource packaging, Kotlin compilation, DEX generation, APK assembly and installation.</string>
    <string name="build_details">Test ID: %1$s\nVersion: %2$s\nVariant: %3$s\nAndroid API: %4$d\nPrimary ABI: %5$s\nTest runs: %6$d</string>
</resources>
EOF

cat > "$ROOT/app/src/main/res/values/colors.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="page_background">#101318</color>
    <color name="card_background">#1A2028</color>
    <color name="text_primary">#F2F5F7</color>
    <color name="text_secondary">#AAB5C0</color>
    <color name="accent">#63D6A4</color>
</resources>
EOF

cat > "$ROOT/app/src/main/res/values/themes.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.KotlinBuildTest" parent="android:style/Theme.Material.NoActionBar">
        <item name="android:fontFamily">sans</item>
        <item name="android:windowActionModeOverlay">true</item>
        <item name="android:navigationBarColor">@color/page_background</item>
        <item name="android:statusBarColor">@color/page_background</item>
        <item name="android:windowLightStatusBar">false</item>
        <item name="android:colorAccent">@color/accent</item>
    </style>
</resources>
EOF

cat > "$ROOT/app/src/main/res/drawable/card_background.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android" android:shape="rectangle">
    <solid android:color="@color/card_background" />
    <corners android:radius="18dp" />
    <stroke android:width="1dp" android:color="#2B3440" />
</shape>
EOF

cat > "$ROOT/app/src/main/res/drawable/ic_launcher.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path android:fillColor="#101318" android:pathData="M0,0h108v108h-108z" />
    <path android:fillColor="#63D6A4" android:pathData="M22,20h64v68h-64z" />
    <path android:fillColor="#101318" android:pathData="M34,32h40v9h-40zM34,50h25v9h-25zM34,68h40v9h-40z" />
</vector>
EOF

cat > "$ROOT/README.md" <<'EOF'
# PocketHost Kotlin / Gradle Test

A deliberately small conventional Android application used to validate PocketHost's on-device Gradle builder.

## What it exercises

- Gradle wrapper 9.3.1
- Android Gradle Plugin 9.1.1
- Kotlin source compilation through AGP's built-in Kotlin support
- Android XML resources and AAPT2
- BuildConfig generation
- D8/DEX generation
- APK assembly, signing and installation

## Test in PocketHost

1. Select the project ZIP.
2. Tap **Build APK**.
3. Allow the first-run toolchain downloads.
4. Install the generated APK.
5. Open **Kotlin Build Test** and tap **Run Kotlin test**.

A working button and five checkmarks mean the end-to-end Kotlin/Gradle path completed.
EOF

cat > "$ROOT/.gitignore" <<'EOF'
.gradle/
.idea/
local.properties
**/build/
*.iml
EOF

cp "$REPO_ROOT/gradle/wrapper/gradle-wrapper.jar" "$ROOT/gradle/wrapper/gradle-wrapper.jar"
cp "$REPO_ROOT/gradlew" "$ROOT/gradlew"
cp "$REPO_ROOT/gradlew.bat" "$ROOT/gradlew.bat"
chmod +x "$ROOT/gradlew"
cat > "$ROOT/gradle/wrapper/gradle-wrapper.properties" <<'EOF'
distributionBase=GRADLE_USER_HOME
distributionPath=wrapper/dists
distributionUrl=https\://services.gradle.org/distributions/gradle-9.3.1-bin.zip
networkTimeout=10000
validateDistributionUrl=true
zipStoreBase=GRADLE_USER_HOME
zipStorePath=wrapper/dists
EOF

printf 'Generated %s\n' "$ROOT"
find "$ROOT" -type f -printf '%P\n' | sort
