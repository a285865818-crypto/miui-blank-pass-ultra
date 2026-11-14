# 注意：Gradle 构建走 build.sh + jniLibs，不使用 ndk-build。此文件仅供手动
# ndk-build 时参考，产物会落到 cpp/libs/ 而非 jniLibs（L31）。
APP_ABI := arm64-v8a armeabi-v7a x86_64
APP_PLATFORM := android-29
APP_STL := none
