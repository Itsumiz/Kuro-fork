#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

CACHE_DIR="$ROOT_DIR/.gradle/loom-cache"

if [[ ! -d "$CACHE_DIR" ]]; then
    CANDIDATES=(
        "/home/kaity/Documents/CLIclient/.gradle/loom-cache"
        "${HOME}/Documents/CLIclient/.gradle/loom-cache"
        "/home/kaity/Documents/Open-respect/.gradle/loom-cache"
        "${HOME}/Documents/Open-respect/.gradle/loom-cache"
        "/home/kaity/Documents/Respect-Bpp/.gradle/loom-cache"
        "${HOME}/Documents/Respect-Bpp/.gradle/loom-cache"
    )

    for cand in "${CANDIDATES[@]}"; do
        if [[ -d "$cand" ]]; then
            echo "Copying cached dependencies from $cand"
            mkdir -p "$ROOT_DIR/.gradle"
            cp -r "$cand" "$ROOT_DIR/.gradle/loom-cache"
            break
        fi
    done
fi

chmod +x gradlew

echo "Starting build..."
if ./gradlew --offline assemble; then
    echo "Offline build completed successfully."
else
    echo "Offline build failed, falling back to online build..."
    ./gradlew assemble
fi

echo "Build outputs in build/libs:"
ls -lh build/libs/*.jar build/libs/*.zip 2>/dev/null || ls -lh build/libs
