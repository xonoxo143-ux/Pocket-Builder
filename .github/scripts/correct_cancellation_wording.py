from pathlib import Path

replacements = {
    "app/src/main/java/com/libreseed/pocketbuild/PocketBuildViewModel.kt": [
        (
            "Cancellation requested. Waiting for the active process to stop safely…",
            "Cancellation requested. Waiting for the Gradle launcher to stop…",
        ),
    ],
    "app/src/main/java/com/libreseed/pocketbuild/ui/PocketBuildApp.kt": [
        (
            'Text(if (operation.cancellable) "Cancel safely" else "Cannot cancel this stage")',
            'Text(if (operation.cancellable) "Cancel build" else "Cannot cancel this stage")',
        ),
    ],
}

for filename, changes in replacements.items():
    path = Path(filename)
    text = path.read_text()
    for old, new in changes:
        count = text.count(old)
        if count != 1:
            raise SystemExit(f"{filename}: expected one match for {old!r}, found {count}")
        text = text.replace(old, new, 1)
    path.write_text(text)
