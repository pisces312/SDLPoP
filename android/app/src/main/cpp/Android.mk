# SDLPoP (Prince of Persia) - Android native build.
#
# The game sources are referenced directly from the repository's src/ directory
# (no copies), the same way sdlpal references its own tree. Upstream sources are
# therefore never modified.
#
# Layout assumption: this file lives at <repo>/android/app/src/main/cpp

NDK_LOCAL_PATH := $(call my-dir)
SDLPOP_PATH := $(NDK_LOCAL_PATH)/../../../../..
SDL_PATH := $(SDLPOP_PATH)/3rd/SDL

# Defines the SDL2 shared library module (and imports android/cpufeatures).
include $(SDL_PATH)/Android.mk

include $(CLEAR_VARS)
LOCAL_PATH := $(NDK_LOCAL_PATH)

LOCAL_MODULE := main

# $(LOCAL_PATH) comes first: it holds the SDL2/ forwarding headers and the
# SDL_image shim, which must take precedence over anything else on the path.
LOCAL_C_INCLUDES := $(LOCAL_PATH) $(SDLPOP_PATH)/src $(SDL_PATH)/include

LOCAL_SRC_FILES := \
	$(wildcard $(SDLPOP_PATH)/src/*.c) \
	$(wildcard $(LOCAL_PATH)/*.c)

LOCAL_CFLAGS += -std=gnu99 -D_GNU_SOURCE=1 -Wall
LOCAL_CFLAGS += -Wno-unused-parameter -Wno-sign-compare -Wno-unused-variable
LOCAL_CFLAGS += -Wno-missing-prototypes -Wno-strict-prototypes
LOCAL_CFLAGS += -Wno-unused-but-set-variable -Wno-int-conversion

LOCAL_SHARED_LIBRARIES := SDL2

LOCAL_LDLIBS := -lGLESv1_CM -lGLESv2 -lOpenSLES -llog -landroid

include $(BUILD_SHARED_LIBRARY)
