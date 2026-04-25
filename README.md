# Pocket Builder

Pocket Builder is an Android app focused on one simple flow:

1. **Select Source**
2. **Build App**
3. **Install App**

The product goal is a dual-backend builder:

- **APK payloads** → internal
- **flat raw-source Android projects** → internal when supported
- **Gradle / heavy / out-of-contract projects** → fallback backend

This repository starts from a clean repo-based scaffold instead of the older Code Studio-shaped survival builds.

## Current repo state

This scaffold intentionally focuses on structure, documentation, and a clean Android app shell.

Included now:
- Android app skeleton (`app/`)
- Gradle Kotlin DSL root files
- docs for architecture and roadmap
- a minimal launcher activity with the Pocket Builder shell UI direction

Not included yet:
- Gradle wrapper binaries
- internal raw-source toolchain
- fallback backend implementation
- source import/build/install engine

## Principles

- keep the visible flow simple
- keep backend complexity hidden behind a clean coordinator
- prefer internal build paths when possible
- only use an external backend when the project actually needs it

## Next milestones

- backend clean rebuild
- readiness system
- internal raw-source backend
- fallback backend for Gradle/out-of-contract cases
