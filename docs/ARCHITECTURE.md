# Architecture

Pocket Builder is organized around a simple product flow and a layered backend.

## User flow

1. **Select Source**
2. **Build App**
3. **Install App**

The UI should stay simple even when the backend is not.

## Target backend model

- **APK payloads** -> internal
- **flat raw-source Android projects** -> internal when supported
- **Gradle / heavy / out-of-contract projects** -> fallback backend

## Layer plan

### App/UI layer
Owns:
- main screen
- drawer/navigation
- status cards
- progress/error display
- install flow

### Source layer
Owns:
- pick zip/folder/apk
- import
- unzip
- normalize root
- classify source

### Backend selection layer
Owns:
- choose internal vs fallback backend
- explain why
- expose selected backend to UI

### Readiness layer
Owns:
- internal toolchain readiness
- fallback backend readiness
- permissions/setup checks
- probe checks
- blocked/warning/ready reporting

### Internal build layer
Owns:
- raw-source contract checking
- internal toolchain state
- internal raw-source executor
- APK staging/install support

### Fallback backend layer
Owns:
- external backend integration only
- probes
- dispatch
- callback/result parsing

## Guiding rules

- Keep the main flow tiny.
- Move complexity into the coordinator and backend layers.
- Prefer internal paths when the app can honestly support them.
- Only use the fallback backend when the project actually needs it.
