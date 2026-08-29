/*
 * Forwarding header.
 *
 * SDLPoP's src/types.h includes <SDL2/SDL_image.h> on every non-MSVC compiler.
 * It resolves to the stb_image based shim in the parent directory - do NOT use
 * a quoted "SDL_image.h" here, that would resolve to this very file.
 */

#pragma once
#include "../SDL_image.h"
