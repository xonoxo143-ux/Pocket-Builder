# Product specification

PocketBuild is an Android build manager, not a terminal emulator.

## Primary workflow

1. Open a supported project file, project folder, or archive.
2. Inspect the source and show the detected project type and risks.
3. Import the complete project into a private workspace.
4. Install or repair only the required verified toolchain packs.
5. Build through a visible foreground job.
6. Sign and verify the result.
7. Install, share, or retain the output.

## Initial project types

- Godot projects identified by `project.godot`
- Android Gradle projects identified by `settings.gradle` or `settings.gradle.kts`
- explicit PocketBuild recipes identified by `pocketbuild.json`

## Product rules

- Tapping a build script never executes it immediately.
- Public storage is an import/export boundary, not an executable workspace.
- Unknown projects start with conservative permissions and network policy.
- Toolchains come from pinned trusted sources and are verified before promotion.
- The normal interface shows stages and useful errors; raw logs remain available but secondary.
- PocketBuild does not require or launch Termux.
