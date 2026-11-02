#!/bin/sh
# Rebuilds the remote into src/main/resources/remote-app when its sources changed, and records what it was built from.
# Without the remote project next to this one (or at JAMC_REMOTE_DIR) there is nothing to build.

repo=$(git rev-parse --show-toplevel) || exit 1
remote=${JAMC_REMOTE_DIR:-$repo/../jamc-worship-remote}
stamp=$repo/.remote-app-source

if [ ! -f "$remote/scripts/build-presenter.mjs" ]; then
    echo "Remote project not found at $remote: using the remote-app already here."
    exit 0
fi

hash=$(cd "$remote" && find src public scripts index.html package.json package-lock.json vite.config.ts tsconfig*.json -type f \
    | LC_ALL=C sort | xargs -d '\n' sha256sum | sha256sum | cut -d' ' -f1) || exit 1

if [ -f "$stamp" ] && [ "$(tr -d '\r' < "$stamp")" = "$hash" ]; then
    exit 0
fi

echo "The remote changed: running npm run build:presenter..."
(cd "$remote" && JAMC_PRESENTER="$repo" npm run build:presenter) || {
    echo "The remote did not build. Fix it, then try again." >&2
    exit 1
}
echo "$hash" > "$stamp"
