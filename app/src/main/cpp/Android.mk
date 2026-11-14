# 注意：Gradle 构建走 build.sh + jniLibs，不使用 ndk-build。此文件仅供手动
# ndk-build 时参考，产物会落到 cpp/libs/ 而非 jniLibs（L31）。
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := mbpu_security
LOCAL_SRC_FILES := security.c
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)
