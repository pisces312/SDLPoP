/*
 * SDLPoP - minimal on-screen gamepad.
 *
 * SDLPoP has no touch support at all; its input layer is purely keyboard based
 * (SDL_PollEvent -> SDL_KEYDOWN/UP -> key_states[]). Rather than patch the game,
 * we inject Android key events through SDLActivity.onNativeKeyDown/Up(), which
 * SDL already exposes as static natives and already translates into SDL
 * scancodes (AKEYCODE_DPAD_* -> SDL_SCANCODE_*, AKEYCODE_SHIFT_LEFT ->
 * SDL_SCANCODE_LSHIFT, ...).
 *
 * Layout (see docs/SDLPoP-Input-Logic_2026-08-29.md for the game mechanics):
 *   Left thumb : LEFT / RIGHT  - held = run (the game has no walk speed;
 *                 precise single steps are done by holding Shift + direction,
 *                 i.e. the game's native safe_step).
 *   Right thumb: UP (▲) / DOWN (▼) column with Shift and Enter besides it;
 *                 Esc tucked into the top-right corner. The arrows match the
 *                 key semantics exactly: UP is jump/climb-up/menu-up, DOWN is
 *                 duck/hang/menu-down, so a directional glyph is the most
 *                 accurate label.
 *
 * Multi-touch is supported, so combos like "Shift + direction" (precise step)
 * or "direction + UP" (standing jump) work naturally by holding two fingers.
 */

package com.sdlpop.sdlpop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.SparseIntArray;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import org.libsdl.app.SDLActivity;

public class VirtualPad extends View {

    private static final int NO_BUTTON = -1;

    private static final int ALPHA_IDLE = 70;
    private static final int ALPHA_PRESSED = 150;

    private static final class PadButton {
        final int keyCode;
        final String label;
        final float cxFraction;      // centre x, fraction of view width
        final float cyFraction;      // centre y, fraction of view height
        final float radiusFraction;  // radius, fraction of min(width, height)
        int cx;                      // centre x in pixels
        int cy;
        int radius;

        PadButton(int keyCode, String label, float cxFraction, float cyFraction,
                  float radiusFraction) {
            this.keyCode = keyCode;
            this.label = label;
            this.cxFraction = cxFraction;
            this.cyFraction = cyFraction;
            this.radiusFraction = radiusFraction;
        }
    }

    /**
     * Button layout (fractions of view size; radius is a fraction of
     * min(width, height) so buttons keep their relative size in landscape).
     *
     * Left side  : two large direction buttons (LEFT / RIGHT).
     * Right side : UP (▲) / DOWN (▼) column with Shift and Enter besides
     *              it; Esc tucked into the top-right corner.
     *
     * Centres are chosen so that no two buttons overlap:
     * e.g. up/down are 0.22h apart with a combined radius of 0.18h.
     */
    private final PadButton[] buttons = {
            // Left thumb: movement
            new PadButton(KeyEvent.KEYCODE_DPAD_LEFT,  "\u25C0", 0.115f, 0.78f, 0.105f),
            new PadButton(KeyEvent.KEYCODE_DPAD_RIGHT, "\u25B6", 0.265f, 0.78f, 0.105f),
            // Right thumb: actions
            new PadButton(KeyEvent.KEYCODE_DPAD_UP,    "\u25B2", 0.875f, 0.67f, 0.090f), // jump/climb-up/menu-up
            new PadButton(KeyEvent.KEYCODE_DPAD_DOWN,  "\u25BC", 0.875f, 0.89f, 0.090f), // duck/hang/menu-down
            new PadButton(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift",  0.775f, 0.78f, 0.090f), // step/climb/fight
            new PadButton(KeyEvent.KEYCODE_ENTER,      "\u23CE", 0.940f, 0.78f, 0.075f), // confirm
            new PadButton(KeyEvent.KEYCODE_ESCAPE,     "Esc",    0.940f, 0.10f, 0.060f), // menu
    };

    /** How many pointers currently press each button (indexed like buttons). */
    private final int[] holds = new int[buttons.length];

    /** pointerId -> button index (or NO_BUTTON). */
    private final SparseIntArray pointerButtons = new SparseIntArray();

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public VirtualPad(Context context) {
        super(context);
        setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        fillPaint.setColor(0xFFFFFFFF);
        textPaint.setColor(0xFFFFFFFF);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int minDim = Math.min(w, h);
        for (PadButton b : buttons) {
            b.cx = (int) (w * b.cxFraction);
            b.cy = (int) (h * b.cyFraction);
            b.radius = (int) (minDim * b.radiusFraction);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int i = 0; i < buttons.length; i++) {
            PadButton b = buttons[i];
            fillPaint.setAlpha(holds[i] > 0 ? ALPHA_PRESSED : ALPHA_IDLE);
            canvas.drawCircle(b.cx, b.cy, b.radius, fillPaint);

            textPaint.setTextSize(b.radius * (b.label.length() > 1 ? 0.42f : 0.8f));
            Paint.FontMetrics fm = textPaint.getFontMetrics();
            canvas.drawText(b.label, b.cx, b.cy - (fm.ascent + fm.descent) / 2, textPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int index = event.getActionIndex();
                updatePointer(event.getPointerId(index), event.getX(index), event.getY(index));
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                int count = event.getPointerCount();
                for (int i = 0; i < count; i++) {
                    updatePointer(event.getPointerId(i), event.getX(i), event.getY(i));
                }
                break;
            }
            case MotionEvent.ACTION_UP: {
                // Last pointer lifted.
                int index = event.getActionIndex();
                releasePointer(event.getPointerId(index));
                // Defensive: if somehow any button still reports as held after
                // the last finger has left the screen, force-release everything
                // so the game cannot get stuck with a key held down.
                releaseAllIfEmpty();
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                releasePointer(event.getPointerId(index));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    protected void onDetachedFromWindow() {
        // The view is going away (e.g. activity pauses / finishes). Release
        // everything so we don't leave keys "stuck down" in SDL's keyboard state.
        releaseAll();
        super.onDetachedFromWindow();
    }

    private void updatePointer(int pointerId, float x, float y) {
        // If the coordinate is outside the view (e.g. finger slid off-screen),
        // treat it as "no button" so the pointer releases whatever it was
        // holding. getX/getY can be negative or larger than width/height.
        int next;
        if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) {
            next = NO_BUTTON;
        } else {
            next = hitTest(x, y);
        }

        int previous = pointerButtons.get(pointerId, NO_BUTTON);
        if (next == previous) {
            return;
        }
        if (previous != NO_BUTTON) {
            setButtonPressed(previous, false);
        }
        if (next != NO_BUTTON) {
            setButtonPressed(next, true);
        }
        pointerButtons.put(pointerId, next);
    }

    private void releasePointer(int pointerId) {
        int previous = pointerButtons.get(pointerId, NO_BUTTON);
        if (previous != NO_BUTTON) {
            setButtonPressed(previous, false);
        }
        pointerButtons.delete(pointerId);
    }

    /** Release every button and clear all pointer state. */
    private void releaseAll() {
        boolean changed = false;
        for (int i = 0; i < holds.length; i++) {
            if (holds[i] > 0) {
                holds[i] = 0;
                sendKey(i, false);
                changed = true;
            }
        }
        pointerButtons.clear();
        if (changed) {
            invalidate();
        }
    }

    /**
     * Called after the last pointer lifts. If pointer tracking is empty but
     * some buttons are still reported as held (can happen if an UP event was
     * lost or the state machine got out of sync for any reason), force-release
     * everything so the game does not get stuck with a phantom key press.
     */
    private void releaseAllIfEmpty() {
        if (pointerButtons.size() != 0) {
            return;
        }
        boolean stuck = false;
        for (int hold : holds) {
            if (hold != 0) {
                stuck = true;
                break;
            }
        }
        if (stuck) {
            releaseAll();
        }
    }

    private void setButtonPressed(int index, boolean pressed) {
        if (pressed) {
            if (holds[index]++ == 0) {
                sendKey(index, true);
            }
        } else {
            if (holds[index] > 0 && --holds[index] == 0) {
                sendKey(index, false);
            }
        }
        invalidate();
    }

    /**
     * Returns the index of the button whose centre is closest to (x,y) AND
     * within its radius, or NO_BUTTON if none. Using "closest" rather than
     * "first hit" avoids ambiguity in the (now rare) case where two buttons
     * overlap or the touch point lands exactly on an edge.
     */
    private int hitTest(float x, float y) {
        int bestIndex = NO_BUTTON;
        float bestDistSq = Float.POSITIVE_INFINITY;

        for (int i = 0; i < buttons.length; i++) {
            PadButton b = buttons[i];
            float dx = x - b.cx;
            float dy = y - b.cy;
            float distSq = dx * dx + dy * dy;
            if (distSq <= (float) b.radius * b.radius && distSq < bestDistSq) {
                bestDistSq = distSq;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private void sendKey(int index, boolean down) {
        try {
            if (down) {
                SDLActivity.onNativeKeyDown(buttons[index].keyCode);
            } else {
                SDLActivity.onNativeKeyUp(buttons[index].keyCode);
            }
        } catch (UnsatisfiedLinkError e) {
            // Native library not ready - ignore, the pad simply does nothing.
        }
    }
}
