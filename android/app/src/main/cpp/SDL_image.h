/*
 * SDLPoP - minimal SDL2_image replacement for Android.
 *
 * Why this exists:
 *   Building the real SDL2_image for Android would drag in libpng + zlib as
 *   extra modules. On Android we only need *decoding* (SDLPoP loads its PNG
 *   artwork through IMG_Load / IMG_Load_RW), so we use the header-only
 *   SDL_stbimage.h (stb_image based) instead - exactly what sdlpal does.
 *
 *   Saving (IMG_SavePNG) is intentionally stubbed out: the system screenshot
 *   gesture covers that use case on a phone.
 *
 * The implementation of the STBIMG_* functions lives in pop_android.c.
 *
 * NOTE: this file is picked up *instead of* SDL2_image's own header because the
 * cpp/ directory precedes the SDL include dirs in LOCAL_C_INCLUDES.
 */

#ifndef SDLPOP_SDL_IMAGE_SHIM_H
#define SDLPOP_SDL_IMAGE_SHIM_H

#include <SDL.h>

#ifdef __cplusplus
extern "C" {
#endif

/* --- decoding (implemented by SDL_stbimage.h) ---------------------------- */

extern SDL_Surface *STBIMG_Load(const char *file);
extern SDL_Surface *STBIMG_LoadFromMemory(const unsigned char *buffer, int length);
extern SDL_Surface *STBIMG_Load_RW(SDL_RWops *src, int freesrc);

#define IMG_Load(file)                 STBIMG_Load(file)
#define IMG_Load_RW(src, freesrc)      STBIMG_Load_RW((src), (freesrc))
#define IMG_LoadTyped_RW(src, f, type) STBIMG_Load_RW((src), (f))

/* --- saving: not supported on Android ------------------------------------ */

#define IMG_SavePNG(surface, file)       (-1)
#define IMG_SavePNG_RW(surface, dst, fr) (-1)

/* --- misc API used by SDLPoP --------------------------------------------- */

#define IMG_GetError() SDL_GetError()
#define IMG_Init(flags) ((void)(flags), 0)
#define IMG_Quit()      ((void)0)

#ifdef __cplusplus
}
#endif

#endif /* SDLPOP_SDL_IMAGE_SHIM_H */
