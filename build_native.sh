#!/usr/bin/env bash
# native 安全层编译脚本。
#
# Android 官方 NDK 的 ndk-build / cmake 依赖 x86_64 host 的构建工具，
# 在 aarch64 机器上跑不起来，所以这里直接用 clang 交叉编译。
#
# 需要：Android NDK，通过 ANDROID_NDK_HOME 指定，或放在 $ANDROID_HOME/ndk/ 下。
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"

latest_ndk_under() {
    ls -d "$1"/ndk/* 2>/dev/null | sort -V | tail -1
}

if [ -z "$NDK" ]; then
    if [ -n "$ANDROID_NDK_HOME" ]; then
        NDK="$ANDROID_NDK_HOME"
    elif [ -n "$ANDROID_NDK_ROOT" ]; then
        NDK="$ANDROID_NDK_ROOT"
    elif [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/ndk" ]; then
        NDK="$(latest_ndk_under "$ANDROID_HOME")"
    fi
fi

if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
    echo "找不到 Android NDK，请设置 ANDROID_NDK_HOME" >&2
    exit 1
fi

# NDK r23 之后只有 64 位 host 包，prebuilt 目录就叫 linux-x86_64。
SYSROOT="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
if [ ! -d "$SYSROOT" ]; then
    echo "找不到 NDK sysroot：$SYSROOT" >&2
    exit 1
fi

SRC=app/src/main/cpp/security.c
OUT=app/src/main/jniLibs

build() {
    local abi="$1" target="$2"
    mkdir -p "$OUT/$abi"
    clang --target="$target" --sysroot="$SYSROOT" \
        -shared -fPIC -nostdlib \
        -o "$OUT/$abi/libmbpu_security.so" \
        "$SRC" -lc -llog
    echo "$abi: $(ls -l "$OUT/$abi/libmbpu_security.so" | awk '{print $5}') bytes"
}

build arm64-v8a   aarch64-linux-android29
build armeabi-v7a armv7a-linux-androideabi29
build x86_64      x86_64-linux-android29
