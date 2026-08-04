#!/usr/bin/env sh
set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
WRAPPER_DIR="$APP_HOME/gradle/wrapper"
WRAPPER_JAR="$WRAPPER_DIR/gradle-wrapper.jar"
WRAPPER_URL="https://services.gradle.org/distributions/gradle-9.3.1-wrapper.jar"
WRAPPER_SHA256="b3a875ddc1f044746e1b1a55f645584505f4a10438c1afea9f15e92a7c42ec13"

mkdir -p "$WRAPPER_DIR"

if [ ! -f "$WRAPPER_JAR" ]; then
    TMP="$WRAPPER_JAR.part"
    rm -f "$TMP"
    if command -v curl >/dev/null 2>&1; then
        curl --fail --location --proto '=https' --tlsv1.2 "$WRAPPER_URL" --output "$TMP"
    elif command -v wget >/dev/null 2>&1; then
        wget --https-only --output-document="$TMP" "$WRAPPER_URL"
    else
        echo "PocketBuild needs curl or wget to fetch the official Gradle wrapper JAR." >&2
        exit 1
    fi

    ACTUAL=$(sha256sum "$TMP" | awk '{print $1}')
    if [ "$ACTUAL" != "$WRAPPER_SHA256" ]; then
        rm -f "$TMP"
        echo "Gradle wrapper checksum mismatch." >&2
        exit 1
    fi
    mv "$TMP" "$WRAPPER_JAR"
fi

exec java -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
