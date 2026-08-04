# Architecture decisions

## ADR-001: No Termux application dependency

PocketBuild owns its job runtime and interface. It does not launch or require Termux.

## ADR-002: Private build workspaces

Imported projects are copied or extracted into app-private storage before validation or execution.

## ADR-003: Recipe-based builds

PocketBuild supports recognized project types through explicit builder recipes. It does not guess how to compile arbitrary ZIP files.

## ADR-004: Modular toolchains

Large build systems are versioned packs installed only when needed. Pack metadata and integrity information are pinned.

## ADR-005: Inspection before execution

Opening a Gradle file or unknown project never immediately executes project-controlled code.

## ADR-006: Source-first CI initially

The repository begins with source and security validation. APK publication remains disabled until the build pipeline and signing policy are explicitly approved.
