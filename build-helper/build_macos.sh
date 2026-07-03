#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

app_path="$repo_root/dist/ClipFlow.app"
notary_zip="$repo_root/dist/ClipFlow-notary.zip"

detect_developer_id_identity() {
    security find-identity -p codesigning -v 2>/dev/null | awk '/Developer ID Application/ { print $2; exit }'
}

detect_notary_profile() {
    local profile="${CLIPFLOW_DEFAULT_NOTARY_PROFILE:-seinel-notary}"
    if xcrun notarytool history --keychain-profile "$profile" --output-format json >/dev/null 2>&1; then
        echo "$profile"
    fi
}

require_sparkle_inputs() {
    local configured=0
    [[ -n "${CLIPFLOW_SPARKLE_FRAMEWORK:-}" ]] && configured=1
    [[ -n "${CLIPFLOW_SPARKLE_FEED_URL:-}" ]] && configured=1
    [[ -n "${CLIPFLOW_SPARKLE_PUBLIC_ED_KEY:-}" ]] && configured=1
    if [[ "$configured" -eq 0 ]]; then
        return 1
    fi
    if [[ -z "${CLIPFLOW_SPARKLE_FRAMEWORK:-}" || -z "${CLIPFLOW_SPARKLE_FEED_URL:-}" || -z "${CLIPFLOW_SPARKLE_PUBLIC_ED_KEY:-}" ]]; then
        echo "Sparkle builds require CLIPFLOW_SPARKLE_FRAMEWORK, CLIPFLOW_SPARKLE_FEED_URL, and CLIPFLOW_SPARKLE_PUBLIC_ED_KEY." >&2
        exit 2
    fi
    return 0
}

copy_sparkle_framework() {
    local source_framework="$CLIPFLOW_SPARKLE_FRAMEWORK"
    if [[ ! -d "$source_framework" ]]; then
        echo "Sparkle.framework not found: $source_framework" >&2
        exit 2
    fi
    mkdir -p "$app_path/Contents/Frameworks"
    rm -rf "$app_path/Contents/Frameworks/Sparkle.framework"
    ditto "$source_framework" "$app_path/Contents/Frameworks/Sparkle.framework"
}

sign_and_verify_app() {
    if [[ -z "${CLIPFLOW_CODESIGN_IDENTITY:-}" ]]; then
        echo "No Developer ID identity found; PyInstaller will leave an ad-hoc signed app."
        return
    fi
    codesign --force --deep --options runtime --timestamp --sign "$CLIPFLOW_CODESIGN_IDENTITY" "$app_path"
    codesign --verify --deep --strict --verbose=2 "$app_path"
}

notary_credentials_configured() {
    if [[ -n "${CLIPFLOW_NOTARY_PROFILE:-}" ]]; then
        return 0
    fi
    if [[ -n "${CLIPFLOW_NOTARY_KEY:-}" || -n "${CLIPFLOW_NOTARY_KEY_ID:-}" || -n "${CLIPFLOW_NOTARY_ISSUER:-}" ]]; then
        if [[ -z "${CLIPFLOW_NOTARY_KEY:-}" || -z "${CLIPFLOW_NOTARY_KEY_ID:-}" ]]; then
            echo "API-key notarization requires CLIPFLOW_NOTARY_KEY and CLIPFLOW_NOTARY_KEY_ID." >&2
            exit 2
        fi
        return 0
    fi
    if [[ -n "${CLIPFLOW_NOTARY_APPLE_ID:-}" || -n "${CLIPFLOW_NOTARY_PASSWORD:-}" || -n "${CLIPFLOW_NOTARY_TEAM_ID:-}" ]]; then
        if [[ -z "${CLIPFLOW_NOTARY_APPLE_ID:-}" || -z "${CLIPFLOW_NOTARY_PASSWORD:-}" || -z "${CLIPFLOW_NOTARY_TEAM_ID:-}" ]]; then
            echo "Apple ID notarization requires CLIPFLOW_NOTARY_APPLE_ID, CLIPFLOW_NOTARY_PASSWORD, and CLIPFLOW_NOTARY_TEAM_ID." >&2
            exit 2
        fi
        return 0
    fi
    return 1
}

notarize_and_staple_app() {
    if ! notary_credentials_configured; then
        if [[ "${CLIPFLOW_NOTARIZE:-0}" == "1" ]]; then
            echo "CLIPFLOW_NOTARIZE=1 requires CLIPFLOW_NOTARY_PROFILE, API-key variables, or Apple ID variables." >&2
            exit 2
        fi
        echo "No notarization credentials configured; skipping notarization."
        return
    fi

    rm -f "$notary_zip"
    ditto -c -k --keepParent "$app_path" "$notary_zip"

    local notary_args=()
    if [[ -n "${CLIPFLOW_NOTARY_PROFILE:-}" ]]; then
        notary_args+=(--keychain-profile "$CLIPFLOW_NOTARY_PROFILE")
    elif [[ -n "${CLIPFLOW_NOTARY_KEY:-}" ]]; then
        notary_args+=(--key "$CLIPFLOW_NOTARY_KEY" --key-id "$CLIPFLOW_NOTARY_KEY_ID")
        if [[ -n "${CLIPFLOW_NOTARY_ISSUER:-}" ]]; then
            notary_args+=(--issuer "$CLIPFLOW_NOTARY_ISSUER")
        fi
    else
        notary_args+=(
            --apple-id "$CLIPFLOW_NOTARY_APPLE_ID"
            --team-id "$CLIPFLOW_NOTARY_TEAM_ID"
            --password "$CLIPFLOW_NOTARY_PASSWORD"
        )
    fi

    xcrun notarytool submit "$notary_zip" --wait "${notary_args[@]}"
    xcrun stapler staple "$app_path"
    xcrun stapler validate "$app_path"
    spctl -a -t exec -vv "$app_path"
}

if [[ "$(uname -s)" == "Darwin" && -z "${CLIPFLOW_CODESIGN_IDENTITY:-}" ]]; then
    detected_identity="$(detect_developer_id_identity)"
    if [[ -n "$detected_identity" ]]; then
        export CLIPFLOW_CODESIGN_IDENTITY="$detected_identity"
        echo "Using code signing identity: $CLIPFLOW_CODESIGN_IDENTITY"
    fi
fi

if [[ "$(uname -s)" == "Darwin" && -z "${CLIPFLOW_NOTARY_PROFILE:-}" && -z "${CLIPFLOW_NOTARY_KEY:-}" && -z "${CLIPFLOW_NOTARY_APPLE_ID:-}" ]]; then
    detected_notary_profile="$(detect_notary_profile)"
    if [[ -n "$detected_notary_profile" ]]; then
        export CLIPFLOW_NOTARY_PROFILE="$detected_notary_profile"
        echo "Using notarization profile: $CLIPFLOW_NOTARY_PROFILE"
    fi
fi

sparkle_build=0
if require_sparkle_inputs; then
    sparkle_build=1
fi

notarization_build=0
if notary_credentials_configured; then
    notarization_build=1
elif [[ "${CLIPFLOW_NOTARIZE:-0}" == "1" ]]; then
    echo "CLIPFLOW_NOTARIZE=1 requires CLIPFLOW_NOTARY_PROFILE, API-key variables, or Apple ID variables." >&2
    exit 2
fi

python3 -m venv .venv
source .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt
python -m unittest discover -s test -p "test_*.py" -v
python -m PyInstaller build-helper/ClipFlow.spec --noconfirm

if [[ "$(uname -s)" == "Darwin" && -d "$app_path" ]]; then
    if [[ "$sparkle_build" -eq 1 ]]; then
        copy_sparkle_framework
    fi
    sign_and_verify_app
    if [[ "$notarization_build" -eq 1 ]]; then
        notarize_and_staple_app
    fi
fi
