# PocketBuild

PocketBuild is a mobile-first Android build manager. It opens supported project files and archives, installs only the required verified toolchains, builds inside private workspaces, signs the result, and hands the APK to Android's installer.

## Product flow

1. Open a `project.godot`, Gradle file, project folder, or ZIP.
2. Inspect and classify the source before execution.
3. Install or repair the exact toolchain pack required by the project.
4. Build, sign, verify, install, or share the output.

PocketBuild is not a terminal wrapper and does not depend on Termux. Its runtime will supervise build jobs directly and expose structured progress through a native Android interface.

## Current rewrite status

The `rewrite/pocketbuild-v1` branch replaces the original scaffold with:

- Kotlin and Jetpack Compose application shell
- phone and foldable/tablet navigation layouts
- Android `VIEW` and `SEND` file handling
- `content://` metadata inspection
- Godot, Gradle, PocketBuild recipe, and ZIP source classification
- bounded ZIP inspection and zip-slip path rejection
- initial toolchain, workspace, build, and signing model
- unit tests for source classification and archive safety

The first source slice intentionally stops before executing downloaded toolchains. See `docs/EXECUTION_MODEL.md` and `docs/ROADMAP.md`.

## Modules

The first compiling slice remains one Android module while the core contracts settle. The code is already separated by package boundaries so it can be split into `core`, `runtime`, `builder-godot`, `builder-gradle`, and `signing` modules without rewriting behavior.
