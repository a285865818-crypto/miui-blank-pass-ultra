#!/usr/bin/env bash
# 构建 MIUI Blank Pass Ultra（空值权限）：Miuix Compose UI + libxposed modern 模块
#
# 默认出 release（R8 + 资源压缩）。debug 版没有优化，Compose 滚动会明显掉帧，
# 只有排查问题时才用 ./build.sh debug。
#
# 需要：JDK 17、Android SDK（compileSdk 37）。
# 找不到 gradle 时可以用 GRADLE_BIN=/path/to/gradle 指定。
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
VARIANT="${1:-release}"

find_gradle() {
    if [ -n "$GRADLE_BIN" ]; then
        echo "$GRADLE_BIN"
        return 0
    fi
    if command -v gradle >/dev/null 2>&1; then
        command -v gradle
        return 0
    fi
    # 用过 Gradle Wrapper 的话，发行版会缓存在这个目录下（路径带随机哈希，只能靠通配符找）
    local cached
    cached=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-*/[a-zA-Z0-9]*/gradle-*/bin/gradle 2>/dev/null | tail -1)
    if [ -n "$cached" ]; then
        echo "$cached"
        return 0
    fi
    return 1
}

GRADLE_BIN="$(find_gradle)" || {
    echo "找不到 gradle，请安装后重试，或用 GRADLE_BIN=/path/to/gradle 指定" >&2
    exit 1
}

# 少数 aarch64 环境中 SDK 自带的 aapt2 无法直接执行，需要用 qemu 包装过的版本。
# 通过 AAPT2_OVERRIDE 指向包装器即可，脚本会自动加 -P 参数，不用改 gradle.properties。
EXTRA_ARGS=""
if [ -n "$AAPT2_OVERRIDE" ] && [ -x "$AAPT2_OVERRIDE" ]; then
    EXTRA_ARGS="-Pandroid.aapt2FromMavenOverride=$AAPT2_OVERRIDE"
fi

cd "$ROOT"
if [ "$VARIANT" = "debug" ]; then
    # shellcheck disable=SC2086
    "$GRADLE_BIN" assembleDebug --no-daemon $EXTRA_ARGS
    APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
else
    # shellcheck disable=SC2086
    "$GRADLE_BIN" assembleRelease --no-daemon $EXTRA_ARGS
    APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
fi

echo
echo "APK: $APK"
ls -l "$APK"
