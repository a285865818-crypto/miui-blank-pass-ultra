#!/usr/bin/env bash
# native 安全层编译脚本。
#
# Android 官方 NDK 的 ndk-build / cmake 依赖 x86_64 host 的构建工具，
# 在 aarch64 机器上跑不起来，所以这里直接用 clang 交叉编译。
#
# 需要：Android NDK，通过 ANDROID_NDK_HOME 指定，或放在 $ANDROID_HOME/ndk/ 下。
#
# 工具链说明（L27）：clang + NDK sysroot + compiler-rt + lld 的组合。
# 版本不锁死，覆盖变量可固定：
#   export CLANG=/path/to/clang   指定编译器（默认 PATH 的 clang）
#   export LLD=/path/to/ld.lld    指定链接器（-fuse-ld=lld 解析不到时用）
# 注意 NDK prebuilt 可能是 x86_64 host 二进制，在 aarch64 机器上跑不动，
# 所以默认用宿主可执行的系统 clang，而不是 NDK 自带 clang。
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

# 工具链（L27）：clang + NDK sysroot + NDK compiler-rt + lld 的组合。
# 版本不锁死，覆盖变量可固定：export CLANG=/path/to/clang 指定编译器。
# -rtlib=compiler-rt 让 clang 不再传 -lgcc（NDK 没有 libgcc）；
# -resource-dir 指到 NDK 的 lib/clang/<ver>，内置库按
# lib/linux/libclang_rt.builtins-<arch>-android.a 解析——这是 NDK 的摆放位置，
# 三个 ABI 都能命中；系统 clang 默认 resource-dir 下 armv7a 会找错目录。
# NDK prebuilt 可能是 x86_64 host 二进制（aarch64 机器上跑不动），
# 所以默认用宿主可执行的系统 clang，而不是 NDK 自带 clang。
CC="${CLANG:-clang}"
if ! command -v "$CC" >/dev/null 2>&1 && [ ! -x "$CC" ]; then
    echo "clang 不可用：$CC" >&2
    exit 1
fi

# NDK r23 之后只有 64 位 host 包，prebuilt 目录就叫 linux-x86_64。
PREBUILT="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$PREBUILT/sysroot"
if [ ! -d "$SYSROOT" ]; then
    echo "找不到 NDK sysroot：$SYSROOT" >&2
    exit 1
fi

# NDK 的 compiler-rt 目录（lib/linux/libclang_rt.builtins-*.android.a 所在），
# 以及它的上一级（clang 的 -resource-dir 要的是这一级）。
# 系统 clang 默认 -resource-dir 指向宿主 /usr/lib/llvm-19，里面没有 Android 目标的
# builtins，armv7a 会按 <triple> 子目录找而报缺库；把 resource-dir 指到 NDK 的
# lib/clang/<ver> 后，clang 按 lib/linux/libclang_rt.builtins-<arch>-android.a 解析，
# 正好是 NDK 摆放的位置，三个 ABI 都能解析到。
RT_VERSION=$(ls "$PREBUILT/lib/clang" | sort -V | tail -1)
RT_RESOURCEDIR="$PREBUILT/lib/clang/$RT_VERSION"
RT_LIBDIR="$RT_RESOURCEDIR/lib/linux"
if [ ! -d "$RT_LIBDIR" ]; then
    echo "找不到 NDK compiler-rt：$RT_LIBDIR" >&2
    exit 1
fi

SRC=app/src/main/cpp/security.c
OUT=app/src/main/jniLibs

# A 项瘦身后处理：优先用宿主 aarch64 的 llvm-strip（可跨架构剥任意 ABI），
# NDK 自带 llvm-strip 是 x86_64 host 二进制跑不动。找不到就靠 -s 链接期剥。
STRIP_TOOL=""
for cand in llvm-strip llvm-strip-19 llvm-strip-18 llvm-strip-17; do
    if command -v "$cand" >/dev/null 2>&1; then STRIP_TOOL="$(command -v "$cand")"; break; fi
done

# 清掉旧产物再编（M24）：三个 ABI 必须来自同一次编译，
# 否则部分失败会留下「一半新一半旧」的混合版本。
rm -rf "$OUT"

build() {
    local abi="$1" target="$2"
    mkdir -p "$OUT/$abi"
    # 不用 -nostdlib（L26）：保留默认链接，让 crtbegin_so.o/crtend_so.o 参与，
    # so 才有正常的 init/fini 数组；只需要 -shared -fPIC。
    # -fuse-ld=lld -rtlib=compiler-rt -resource-dir "$RT_RESOURCEDIR"：
    # NDK 无 libgcc，内置函数走 NDK 的 compiler-rt；resource-dir 指到 NDK 的
    # lib/clang/<ver>，clang 才能按 lib/linux/libclang_rt.builtins-<arch>-android.a
    # 解析到库（L27）。
    # A 项瘦身：
    #   -fvisibility=hidden  默认隐藏，导出符号只剩 JNIEXPORT（JNI_OnLoad）；
    #   -flto                链接期优化（编译+链接同一命令，LTO 全程生效）；
    #   -s                   链接期 --strip-all，去掉 .symtab（nm 报 no symbols）；
    #   -ffunction-sections/-fdata-sections + --gc-sections  死代码剔除；
    #   --exclude-libs,ALL   不把 compiler-rt 等静态库符号放进导出表。
    if ! "$CC" --target="$target" --sysroot="$SYSROOT" \
        -O2 -shared -fPIC -fuse-ld=lld -rtlib=compiler-rt ${LLD:+--ld-path="$LLD"} \
        -resource-dir "$RT_RESOURCEDIR" \
        -fvisibility=hidden -flto \
        -ffunction-sections -fdata-sections \
        -s -Wl,--gc-sections -Wl,--exclude-libs,ALL \
        -o "$OUT/$abi/libmbpu_security.so" \
        "$SRC" -lc -llog; then
        echo "ERROR: native 编译失败：$abi ($target)，已中止且不会使用旧 so。" >&2
        return 1
    fi
    if [ ! -s "$OUT/$abi/libmbpu_security.so" ]; then
        echo "ERROR: native 编译未生成有效产物：$OUT/$abi/libmbpu_security.so" >&2
        return 1
    fi
    # A 项：再剥一次保险（-s 已在链接期剥过，幂等）；llvm-strip 失败不阻塞。
    if [ -n "$STRIP_TOOL" ]; then
        "$STRIP_TOOL" --strip-all "$OUT/$abi/libmbpu_security.so" 2>/dev/null || true
    fi
    echo "$abi: $(ls -l "$OUT/$abi/libmbpu_security.so" | awk '{print $5}') bytes"
}

build arm64-v8a   aarch64-linux-android29
build armeabi-v7a armv7a-linux-androideabi29
build x86_64      x86_64-linux-android29

# 三个 ABI 都要齐（M24）：缺任何一个都算失败，别让 build.sh 打包出残缺 APK。
for abi in arm64-v8a armeabi-v7a x86_64; do
    if [ ! -s "$OUT/$abi/libmbpu_security.so" ]; then
        echo "ERROR: $OUT/$abi/libmbpu_security.so 缺失或为空，编译未完成，禁止打包。" >&2
        exit 1
    fi
done
echo "native build OK: 3 ABIs"
