#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

version="${1:?Usage: build-helper/release_macos.sh <version> [build-number]}"
build_number="${2:-$version}"
repo="${CLIPFLOW_GITHUB_REPO:-kwd421/ClipFlow}"
tag="${CLIPFLOW_RELEASE_TAG:-v$version}"
sparkle_account="${CLIPFLOW_SPARKLE_ACCOUNT:-ed25519}"
sparkle_bin="${CLIPFLOW_SPARKLE_BIN:-/Users/seinel/Projects/mac_mouse_cursor/.build/artifacts/sparkle/Sparkle/bin}"
sparkle_framework="${CLIPFLOW_SPARKLE_FRAMEWORK:-/Users/seinel/Projects/mac_mouse_cursor/.build/artifacts/sparkle/Sparkle/Sparkle.xcframework/macos-arm64_x86_64/Sparkle.framework}"
feed_url="${CLIPFLOW_SPARKLE_FEED_URL:-https://kwd421.github.io/ClipFlow/appcast.xml}"
release_zip="dist/ClipFlow-$version.zip"
updates_dir="dist/sparkle-updates"

public_key="$("$sparkle_bin/generate_keys" -p --account "$sparkle_account")"

CLIPFLOW_VERSION="$version" \
CLIPFLOW_BUILD_NUMBER="$build_number" \
CLIPFLOW_SPARKLE_FRAMEWORK="$sparkle_framework" \
CLIPFLOW_SPARKLE_FEED_URL="$feed_url" \
CLIPFLOW_SPARKLE_PUBLIC_ED_KEY="$public_key" \
CLIPFLOW_NOTARIZE="${CLIPFLOW_NOTARIZE:-1}" \
bash build-helper/build_macos.sh

rm -f "$release_zip"
rm -rf "$updates_dir"
mkdir -p "$updates_dir" docs
ditto -c -k --keepParent dist/ClipFlow.app "$release_zip"
cp "$release_zip" "$updates_dir/ClipFlow-$version.zip"
cat > "$updates_dir/ClipFlow-$version.md" <<EOF
# ClipFlow $version

Notarized ClipFlow release with Sparkle automatic update support.
EOF

"$sparkle_bin/generate_appcast" \
    --account "$sparkle_account" \
    --download-url-prefix "https://github.com/$repo/releases/download/$tag/" \
    --link "https://github.com/$repo" \
    "$updates_dir"

cp "$updates_dir/appcast.xml" docs/appcast.xml
cp "$updates_dir/ClipFlow-$version.md" "docs/ClipFlow-$version.md"
touch docs/.nojekyll

if gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
    gh release upload "$tag" "$release_zip" --repo "$repo" --clobber
else
    gh release create "$tag" "$release_zip" \
        --repo "$repo" \
        --title "ClipFlow $version" \
        --notes "Notarized ClipFlow release with Sparkle automatic update support."
fi

echo "Generated docs/appcast.xml and uploaded $release_zip to $repo release $tag."
