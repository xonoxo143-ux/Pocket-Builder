# Architecture

## Boundaries

PocketBuild is organized around six responsibilities:

1. **Source intake** — Android intents, Storage Access Framework, metadata, safe archive inspection, workspace import.
2. **Project model** — project type, root, requested outputs, compatibility and security findings.
3. **Toolchain manager** — signed manifests, trusted downloads, verification, installation, repair and removal.
4. **Build coordinator** — recipe selection, readiness checks, job staging, cancellation and progress events.
5. **Execution runtime** — private workspace, controlled environment, child process supervision and structured logs.
6. **Output manager** — signing, APK verification, history, sharing and Android package-installer handoff.

The UI observes models and build events. It does not parse command output to infer state.

## Initial package map

- `model/` — stable UI-independent data models
- `source/` — pure source classification and Android URI inspection
- `ui/` — responsive Compose screens
- `PocketBuildViewModel` — temporary coordinator facade for the first slice

As execution work begins, these boundaries become separate Gradle modules.

## Builder recipes

A builder recipe declares:

- markers used to recognize a project
- compatible project and toolchain versions
- required packs
- safety capabilities
- build stages
- expected outputs
- post-build verification

The first complete recipe will be a basic Godot Android APK export using a matching precompiled export template. Generic Gradle execution comes after the constrained Godot path works.
