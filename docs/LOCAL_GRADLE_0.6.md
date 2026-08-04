# PocketHost 0.6 local Android builds

PocketHost 0.6 adds an ARM64 on-device build path for standard Android Gradle projects.

## Build flow

1. Import an Android project folder or ZIP containing the Gradle wrapper.
2. Inspect the wrapper distribution and requested Android SDK platform.
3. Download and verify the ARM64 OpenJDK 17 runtime and its package dependencies.
4. Download the pinned Android-compatible ARM64 Build Tools archive.
5. Download missing Android SDK platforms from Google's repository metadata.
6. Run the project's Gradle wrapper with a private Gradle and Maven cache.
7. Locate, verify, and when needed sign the generated APK.
8. Hand the APK to Android's package installer.

## Current boundary

The initial implementation targets ordinary Kotlin, Java, XML, and Jetpack Compose projects. It requires an ARM64 device and a checked-in Gradle wrapper. NDK, Flutter, RenderScript, and projects that require desktop-only native Gradle plugins are outside the first validated path.

CI validates application compilation, unit tests, lint, APK signing, package identity, JDK package checksums, the Android Build Tools checksum, and ARM64 executable formats. A physical Android build remains the final runtime validation step.
