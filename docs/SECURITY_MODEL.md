# Security model

Projects and build scripts are untrusted input.

## Non-negotiable controls

- inspect archives before extraction
- reject absolute paths and `..` traversal
- cap entry count and expanded size
- use private per-build workspaces
- do not expose signing keys to project-controlled build code
- disable unapproved repositories and network access for untrusted recipes
- show requested plugins, repositories, native code and process execution before running Gradle
- verify every official toolchain artifact against a pinned digest or signed manifest
- write outputs atomically and verify APK signatures before install/share
- keep complete structured build logs

Opening a `.gradle` file never executes it. It opens an inspection and project-root resolution flow.
