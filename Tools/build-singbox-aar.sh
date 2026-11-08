#!/usr/bin/env bash
#
# Builds the sing-box Android library (libbox.aar) used by the Sing-box proxy type.
#
# Requirements: Go 1.23+, Android SDK, Android NDK r27 (set ANDROID_HOME / ANDROID_NDK_HOME).
# The pinned version is intentional: PlatformInterface changes between releases.
#
set -euo pipefail

SING_BOX_TAG="${SING_BOX_TAG:-v1.14.4}"
ABI="${ABI:-android/arm64}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/TMessagesProj/libs/libbox.aar"

if ! command -v go >/dev/null 2>&1; then
    echo "error: go is required (1.23+)" >&2
    exit 1
fi
if [ -z "${ANDROID_HOME:-}" ] || [ -z "${ANDROID_NDK_HOME:-}" ]; then
    echo "error: ANDROID_HOME and ANDROID_NDK_HOME must be set" >&2
    exit 1
fi

echo "==> installing gomobile"
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
export PATH="$(go env GOPATH)/bin:$PATH"
gomobile init

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "==> fetching sing-box $SING_BOX_TAG"
git clone --depth 1 --branch "$SING_BOX_TAG" https://github.com/SagerNet/sing-box.git "$WORK/sing-box"

cd "$WORK/sing-box"
mkdir -p "$(dirname "$OUT")"

# 16 KB page size alignment is required by Android 15 devices.
echo "==> binding libbox ($ABI)"
CGO_LDFLAGS="-Wl,-z,max-page-size=16384" gomobile bind \
    -target="$ABI" \
    -androidapi 23 \
    -tags "with_quic,with_wireguard,with_utls,with_gvisor" \
    -ldflags "-extldflags=-Wl,-z,max-page-size=16384" \
    -o "$OUT" \
    ./experimental/libbox

echo "$SING_BOX_TAG" > "$ROOT/TMessagesProj/libs/libbox.version"
echo "==> done: $OUT"
