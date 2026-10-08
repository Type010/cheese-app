#!/usr/bin/env bash
# Cross-compile libextract.so for Android.
#
# Run inside Linux/WSL: the local NDK is the linux-x86_64 build, so Windows
# clang cannot use it and the cross build stays in WSL.
#
#   ANDROID_NDK_HOME=/path/to/android-ndk-r28 ./build-android.sh [out-dir]
#
# Output goes to app/src/main/jniLibs/<abi>/libextract.so by default:
# the lib*.so name is what makes the installer extract it as an executable.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
NDK="${ANDROID_NDK_HOME:-/mnt/d/workspace/iqoo11pro/ndk/android-ndk-r28}"
API="${ANDROID_API:-31}"
OUT_DIR="${1:-$here/../../app/src/main/jniLibs}"
HOST_TAG="${NDK_HOST_TAG:-linux-x86_64}"
BIN="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin"
CARGO="${CARGO:-$HOME/.cargo/bin/cargo}"

if [ ! -d "$BIN" ]; then
    echo "NDK toolchain not found: $BIN" >&2
    exit 2
fi

# Outside WSL the repo may live elsewhere; only the WSL default path falls back.
if [ ! -d "$BIN" ] && [ -d "/mnt/d/workspace/iqoo11pro/ndk/android-ndk-r28" ]; then
    BIN="/mnt/d/workspace/iqoo11pro/ndk/android-ndk-r28/toolchains/llvm/prebuilt/$HOST_TAG/bin"
fi

export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$HOME/target}"

build_one() {
    local target="$1" abi="$2"
    local arch="${target%%-*}"
    local linker="$BIN/${arch}-linux-android${API}-clang"
    local ar="$BIN/llvm-ar"
    local upper
    upper="$(echo "$target" | tr 'a-z-' 'A-Z_')"
    local under
    under="$(echo "$target" | tr '-' '_')"
    echo "==> $abi ($target)"
    # A few dependencies (payload-extract's C part) use a cc-rs build script,
    # which reads the underscore-lowercase CC_/AR_ variable names.
    env \
        "CARGO_TARGET_${upper}_LINKER=$linker" \
        "CC_${under}=$linker" \
        "AR_${under}=$ar" \
        "$CARGO" build --release --target "$target" --manifest-path "$here/Cargo.toml"
    mkdir -p "$OUT_DIR/$abi"
    cp "$CARGO_TARGET_DIR/$target/release/cheese-extract" "$OUT_DIR/$abi/libextract.so"
    chmod 755 "$OUT_DIR/$abi/libextract.so"
    ls -l "$OUT_DIR/$abi/libextract.so"
}

build_one aarch64-linux-android arm64-v8a

echo "done: $OUT_DIR"
