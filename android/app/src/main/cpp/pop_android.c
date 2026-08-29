/*
 * SDLPoP - Android platform glue.
 *
 * Responsibilities:
 *   1. Hosts the SDL_stbimage.h implementation (the stb_image based decoder
 *      that our SDL_image.h shim forwards to).
 *   2. Points the process working directory and $HOME at the app's internal
 *      storage, so that upstream's fopen()-based resource and save-file
 *      lookups work on Android without touching a single upstream file.
 *   3. Disables SDL's "accelerometer as joystick" behaviour, which would
 *      otherwise hijack the game's input mode (see below).
 *
 * The upstream SDLPoP sources are not modified in any way.
 */

#include <jni.h>

#include <stdlib.h>
#include <sys/stat.h>
#include <unistd.h>

#include <android/log.h>

#include <SDL.h>

/* The stb_image based decoder implementation lives in this translation unit. */
#define SDL_STBIMAGE_IMPLEMENTATION
#include "SDL_stbimage.h"

#define LOG_TAG "SDLPoP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/*
 * Called from PoPActivity.onCreate() once the native libraries are loaded, but
 * before the SDL main thread (and therefore SDL_main) is started - SDL only
 * spawns that thread once the surface is ready and the activity is resumed.
 *
 * jDataDir is getFilesDir(); the Java side has already extracted the game data
 * to <jDataDir>/data.
 */
JNIEXPORT void JNICALL
Java_com_sdlpop_sdlpop_PoPActivity_nativeSetup(JNIEnv *env, jclass cls, jstring jDataDir)
{
    const char *dir;

    (void)cls;

    dir = (*env)->GetStringUTFChars(env, jDataDir, NULL);
    if (dir == NULL) {
        LOGE("nativeSetup: cannot read data directory string");
        return;
    }

    if (chdir(dir) != 0) {
        LOGE("nativeSetup: chdir('%s') failed", dir);
    } else {
        LOGI("nativeSetup: working directory = %s", dir);
    }

    /*
     * locate_save_file_() walks [$HOME/.SDLPoP, /usr/share/SDLPoP, exe_dir] and
     * uses the first entry that exists and is writable. On Android the exe dir
     * is bogus (SDL passes argv[0] = "app_process"), so $HOME/.SDLPoP must
     * exist - otherwise saves and the config file would have nowhere to go.
     */
    setenv("HOME", dir, 1);
    if (mkdir(".SDLPoP", 0777) == 0) {
        LOGI("nativeSetup: created .SDLPoP");
    }

    /*
     * By default SDL registers the accelerometer as a 3-axis joystick
     * (SDL_HINT_ACCELEROMETER_AS_JOYSTICK defaults to "1"). SDLPoP supports
     * gamepads and, with USE_AUTO_INPUT_MODE enabled, switches to joystick mode
     * as soon as any axis exceeds joystick_threshold (8000 out of 32767).
     * The accelerometer reports gravity in g, clamped to +-1.0 and scaled to
     * +-32767, so tilting the phone by only ~14 degrees is enough to cross that
     * threshold. Once joystick mode is active is_keyboard_mode becomes 0 and
     * the on-screen controls stop working - symptoms: the prince walks by
     * himself and menu entries scroll in a loop.
     *
     * The game is a 2D side-scroller and has no use for tilt input, so turn
     * the accelerometer into a non-input device. This must happen before
     * SDL_Init(SDL_INIT_JOYSTICK) runs, which is guaranteed here because
     * onCreate() calls nativeSetup() well before SDL starts its main thread.
     */
    if (SDL_SetHint(SDL_HINT_ACCELEROMETER_AS_JOYSTICK, "0") == SDL_TRUE) {
        LOGI("nativeSetup: accelerometer-as-joystick disabled");
    } else {
        LOGE("nativeSetup: failed to set SDL_HINT_ACCELEROMETER_AS_JOYSTICK");
    }

    (*env)->ReleaseStringUTFChars(env, jDataDir, dir);
}
