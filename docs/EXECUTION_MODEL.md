# Execution model

PocketBuild will not launch or depend on the Termux application. It will implement a focused job runtime using the same broad operating-system primitives: private app storage, native processes, pipes, signals, environment variables, and architecture-specific toolchains.

## Runtime layout

```text
files/
  runtime/
    bin/
    lib/
    tmp/
  toolchains/
  workspaces/
  builds/
  cache/
```

Build sources are copied into private workspaces before execution. Public/shared storage is treated as an import/export boundary, never as an executable workspace.

## Process model

```text
Build request
  -> foreground BuildService
  -> BuildCoordinator
  -> ProcessSupervisor
  -> native/JVM tool
  -> structured stdout/stderr events
  -> UI and build log
```

The process supervisor must support cancellation, timeouts, exit status, signal escalation, bounded logs, and cleanup after process death.

## Toolchain delivery decision

Modern Android restricts execution of newly downloaded native binaries. PocketBuild will therefore separate:

- downloadable **data**: SDK platforms, Maven artifacts, Godot templates, source and metadata
- executable **runtime packs**: distributed as trusted APK/native-library payloads or bundled components

A legacy-target experimental flavor may be evaluated only as a sideloaded development build. It is not the default architecture.
