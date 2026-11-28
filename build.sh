#!/usr/bin/env bash
# 构建 MIUI Blank Pass Ultra（空值权限）：Miuix Compose UI + libxposed modern 模块
#
# PR 版 release 不开混淆加固（见 app/build.gradle.kts 注释），构建快；发行版再开
# R8 + 资源压缩。debug 版仅排查问题时用 ./build.sh debug。
#
# 需要：JDK 17、Android SDK（compileSdk 37）。
# 找不到 gradle 时可以用 GRADLE_BIN=/path/to/gradle 指定。
set -e

ROOT="$(cd "$(dirname "$0")" && pwd)"
VARIANT="${1:-release}"

# 只认 debug/release，拼错直接报错而不是静默走 release（L28）。
case "$VARIANT" in
  debug|release) ;;
  *) echo "ERROR: VARIANT=$VARIANT 无效（只能 debug/release）" >&2; exit 1 ;;
esac

find_gradle() {
    if [ -n "$GRADLE_BIN" ]; then
        echo "$GRADLE_BIN"
        return 0
    fi
    if command -v gradle >/dev/null 2>&1; then
        command -v gradle
        return 0
    fi
    # 用过 Gradle Wrapper 的话，发行版会缓存在这个目录下（路径带随机哈希，只能靠通配符找）。
    # sort -V 按版本号排，gradle-10 才不会排到 gradle-9 前面（与 build_native.sh 一致，L29）。
    local cached
    cached=$(ls -d "$HOME"/.gradle/wrapper/dists/gradle-*/[a-zA-Z0-9]*/gradle-*/bin/gradle 2>/dev/null | sort -V | tail -1)
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

cd "$ROOT"

# ---- EXPECTED_SIG_SHA256 格式预检（H10）----
# keytool 输出是大写 + 冒号分隔的 96 字符，直接填进来会让两层校验以不同规则失效。
# 这里在构建前就给出可读的告警，而不是让用户对着闪退猜。
if [ -f local.properties ]; then
    # A9 fix: Java Properties tolerates leading whitespace before the key, so the old
    # exact-prefix match could be bypassed. Normalize the same way as the runtime does.
    _sig=$(sed -n 's/^[[:space:]]*EXPECTED_SIG_SHA256[[:space:]]*=//p' local.properties | head -1 | tr -d '[:space:]' | tr -d ':' | tr 'A-Z' 'a-z')
    # R10：长度与字符集都校验。64 位但含非 hex 字符的笔误以前不告警，
    # 运行时必然校验失败（UI 杀进程、hook 全拒载），极难排查。
    if [ -n "$_sig" ] && { [ "${#_sig}" -ne 64 ] || [ -n "$(printf '%s' "$_sig" | tr -d '0-9a-f')" ]; }; then
        echo "ERROR: local.properties 的 EXPECTED_SIG_SHA256 不是合法的 64 位小写 hex。" >&2
        echo "       非法非空值会让运行时签名校验直接拒绝运行，不能静默降级。" >&2
        echo "       取值请去掉冒号并转小写，例如：" >&2
        echo "       keytool ... | grep SHA256 | sed 's/.*: //;s/://g' | tr 'A-Z' 'a-z'" >&2
        # A9 fix: release 构建遇到非法非空值必须中止，debug 只警告（方便开源调试）。
        if [ "$VARIANT" = "release" ]; then
            exit 1
        fi
    fi
    unset _sig
fi

# ---- 修复版应用内更新日志一致性预检 ----
# 关于页正文来自各语言的 about_changelog_body，并不会自动读取 CHANGELOG 文件。
# 修复版若只更新版本号和 CHANGELOG，旧功能文案会留在关于页（v1.4.1 实测踩坑）。
if [ "$VARIANT" = "release" ]; then
    _version=$(sed -n 's/^[[:space:]]*versionName = "\(.*\)"/\1/p' app/build.gradle.kts | head -1)
    _changelog="$ROOT/CHANGELOG-v${_version}.md"
    if [ -f "$_changelog" ] && [ "$(cat "$_changelog")" = "解决了一些已知问题。" ]; then
        _default_body=$(sed -n 's/.*<string name="about_changelog_body">\(.*\)<\/string>.*/\1/p' app/src/main/res/values/strings.xml)
        if [ "$_default_body" != "解决了一些已知问题。" ]; then
            echo "ERROR: 修复版默认 about_changelog_body 必须同步为「解决了一些已知问题。」；已中止打包。" >&2
            exit 1
        fi
        for _strings in app/src/main/res/values*/strings.xml; do
            if grep '<string name="about_changelog_body">' "$_strings" | grep -qF '\n'; then
                echo "ERROR: $_strings 的修复版更新日志仍含多行旧功能文案；已中止打包。" >&2
                exit 1
            fi
        done
        unset _strings
    fi
    unset _version _changelog _default_body
fi

# ---- native 产物预检（M23）----
# Gradle 不编 native，只打包 jniLibs。改了 security.c 忘记重跑 build_native.sh
# 会把旧 so 静默打进 APK，校验逻辑白改。这里在打包前把两件事都拦下来。
for abi in arm64-v8a armeabi-v7a x86_64; do
    SO="$ROOT/app/src/main/jniLibs/$abi/libmbpu_security.so"
    if [ ! -s "$SO" ]; then
        echo "ERROR: native 产物 $SO 不存在或为空，请先成功运行 build_native.sh；已中止打包。" >&2
        exit 1
    fi
    if [ "$ROOT/app/src/main/cpp/security.c" -nt "$SO" ]; then
        echo "ERROR: native 产物 $SO 已过期（security.c 更新），请重跑 build_native.sh；已中止打包。" >&2
        exit 1
    fi
done

# 少数 aarch64 环境中 SDK 自带的 aapt2 无法直接执行，需要用 qemu 包装过的版本。
# 通过 AAPT2_OVERRIDE 指向包装器即可，脚本会自动加 -P 参数，不用改 gradle.properties。
# 校验在 cd "$ROOT" 之后做，相对路径与 gradle 的解析基准一致（L30）；
# 非空但不可执行直接报错，不静默退回 SDK 的 aapt2（M22）。
# R11：用 bash 数组而不是字符串拼接——路径含空格时未加引号的 $EXTRA_ARGS
# 会把一个参数拆成多个，Gradle 收到未知参数。
EXTRA_ARGS=()
if [ -n "$AAPT2_OVERRIDE" ] && [ ! -x "$AAPT2_OVERRIDE" ]; then
    echo "ERROR: AAPT2_OVERRIDE=$AAPT2_OVERRIDE 不存在或不可执行" >&2; exit 1
fi
if [ -n "$AAPT2_OVERRIDE" ]; then
    EXTRA_ARGS+=("-Pandroid.aapt2FromMavenOverride=$AAPT2_OVERRIDE")
fi

if [ "$VARIANT" = "debug" ]; then
    "$GRADLE_BIN" assembleDebug --no-daemon "${EXTRA_ARGS[@]}"
    APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
else
    "$GRADLE_BIN" assembleRelease --no-daemon "${EXTRA_ARGS[@]}"
    APK="$ROOT/app/build/outputs/apk/release/app-release.apk"
fi

echo
echo "APK: $APK"
ls -l "$APK"
