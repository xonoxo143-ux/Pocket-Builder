# Toolchain pack format

Toolchain packs are versioned, architecture-aware bundles described by a signed manifest.

```json
{
  "schema": 1,
  "id": "godot-4.7-export-android",
  "version": "4.7.0",
  "architectures": ["arm64-v8a"],
  "downloadHosts": ["github.com", "downloads.tuxfamily.org"],
  "files": [
    {
      "path": "templates/android_debug.apk",
      "sha256": "...",
      "size": 0,
      "mode": "data"
    }
  ],
  "requires": ["pocketbuild-core>=0.1.0"]
}
```

Installation is staged into a temporary directory, fully verified, then atomically promoted. Interrupted or corrupt installations never replace the last healthy pack.
