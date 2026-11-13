LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := mbpu_security
LOCAL_SRC_FILES := security.c
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)
