/*
 * Forwarding header.
 *
 * SDLPoP's src/types.h includes <SDL2/SDL.h> on every non-MSVC compiler, while
 * the SDL2 source tree places the header directly in its include/ directory.
 * Adding this shim lets the upstream include path work unmodified.
 */

#pragma once
#include <SDL.h>
