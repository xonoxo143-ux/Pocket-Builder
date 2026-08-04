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
- versioned toolchain pack models
- trusted HTTPS host policy
- quick and full installed-pack audits
- SHA-256 verification and atomic toolchain promotion
- staged downloader with progress, cancellation, size checks and redirect validation
- structured build stages and job models
- direct child-process supervision with separate stdout/stderr, cancellation and timeout
- documented Termux-independent execution model

## Not implemented yet

- persistent project database and workspace recovery UI
- signed remote toolchain catalog
- Android foreground download/build service
- concrete Godot export pack URLs and pinned digests
- Godot export template installation and project packaging
- APK signing, verification and installer handoff
- generic Gradle project inspection and execution

## Validation

The pure Kotlin source classifier, archive extractor, toolchain policy/store/installer, and process supervisor have been compiled and exercised locally. Full Android compilation is blocked in the current chat container because the Android SDK and Gradle/Maven dependency set are not installed and shell DNS cannot reach the official repositories.
