# PocketBuild v1 rewrite status

## Implemented

- Kotlin and Jetpack Compose application shell
- responsive phone and large-screen navigation
- Android `VIEW` and `SEND` source handling
- `content://` display-name and size inspection
- Godot, Gradle, ZIP and PocketBuild recipe classification
- bounded ZIP inspection and zip-slip rejection
- one-time folder grants for files whose sibling project files are hidden by Android
- private per-project workspaces
- bounded archive and folder import
- project marker validation
- atomic staging-to-workspace promotion
- unit tests for source classification and archive extraction
- documented Termux-independent execution model

## Not implemented yet

- persistent project database and workspace recovery UI
- signed toolchain catalog and downloader
- builder process service and process supervisor
- Godot export template installation
- APK signing, verification and installer handoff
- generic Gradle execution

## Validation

The pure Kotlin source classifier and archive extractor have been compiled and exercised locally. Full Android compilation is blocked in the current chat container because the Android SDK and Gradle/Maven dependency set are not installed and shell DNS cannot reach the official repositories.
