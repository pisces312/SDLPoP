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
 * Multi-touch is supported, so "Up + Left" (running jump) works naturally by
 * holding two fingers down at once.
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
        final float cxFraction;   // centre x, fraction of view width
        final float cyFraction;   // centre y, fraction of view height
        int cx;                   // centre x in pixels
        int cy;
        int radius;

        PadButton(int keyCode, String label, float cxFraction, float cyFraction) {
            this.keyCode = keyCode;
            this.label = label;
            this.cxFraction = cxFraction;
            this.cyFraction = cyFraction;
        }
    }

    private final PadButton[] buttons = {
            new PadButton(KeyEvent.KEYCODE_DPAD_UP,    "\u25B2", 0.13f, 0.66f),
            new PadButton(KeyEvent.KEYCODE_DPAD_DOWN,  "\u25BC", 0.13f, 0.66f),
            new PadButton(KeyEvent.KEYCODE_DPAD_LEFT,  "\u25C0", 0.13f, 0.66f),
            new PadButton(KeyEvent.KEYCODE_DPAD_RIGHT, "\u25B6", 0.13f, 0.66f),
            new PadButton(KeyEvent.KEYCODE_SHIFT_LEFT, "Shift",  0.90f, 0.55f),
            new PadButton(KeyEvent.KEYCODE_SPACE,      "\u21B5", 0.78f, 0.78f),
            new PadButton(KeyEvent.KEYCODE_ESCAPE,     "Esc",    0.91f, 0.82f),
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

        int radius = (int) (Math.min(w, h) * 0.10f);
        int arm = (int) (radius * 1.15f);

        // D-pad: buttons 0..3 share one centre and are offset by `arm`.
        int dx = (int) (w * buttons[0].cxFraction);
        int dy = (int) (h * buttons[0].cyFraction);
        buttons[0].cx = dx;
        buttons[0].cy = dy - arm;
        buttons[1].cx = dx;
        buttons[1].cy = dy + arm;
        buttons[2].cx = dx - arm;
        buttons[2].cy = dy;
        buttons[3].cx = dx + arm;
        buttons[3].cy = dy;

        for (int i = 0; i < buttons.length; i++) {
            buttons[i].radius = radius;
            if (i >= 4) {
                buttons[i].cx = (int) (w * buttons[i].cxFraction);
                buttons[i].cy = (int) (h * buttons[i].cyFraction);
            }
            textPaint.setTextSize(radius * (buttons[i].label.length() > 1 ? 0.42f : 0.8f));
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
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < event.getPointerCount(); i++) {
                    updatePointer(event.getPointerId(i), event.getX(i), event.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int index = event.getActionIndex();
                releasePointer(event.getPointerId(index));
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
        }
        return true;
    }

    private void updatePointer(int pointerId, float x, float y) {
        int next = hitTest(x, y);
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

    private void releaseAll() {
        for (int i = 0; i < holds.length; i++) {
            if (holds[i] > 0) {
                holds[i] = 0;
                sendKey(i, false);
            }
        }
        pointerButtons.clear();
        invalidate();
    }

    private void setButtonPressed(int index, boolean pressed) {
        if (pressed) {
            if (holds[index]++ == 0) {
                sendKey(index, true);
            }
        } else if (holds[index] > 0 && --holds[index] == 0) {
            sendKey(index, false);
        }
        invalidate();
    }

    private int hitTest(float x, float y) {
        for (int i = 0; i < buttons.length; i++) {
            PadButton b = buttons[i];
            float dx = x - b.cx;
            float dy = y - b.cy;
            if (dx * dx + dy * dy <= (float) b.radius * b.radius) {
                return i;
            }
        }
        return NO_BUTTON;
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
