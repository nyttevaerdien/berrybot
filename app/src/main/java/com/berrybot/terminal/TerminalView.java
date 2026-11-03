package com.berrybot.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.text.InputType;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.berrybot.hardware.HapticHelper;

import de.mud.terminal.vt320;

public class TerminalView extends View {
    private static final String TAG = "BerryBot.TerminalView";

    private TerminalBridge bridge;

    // Sticky modifier states
    private boolean ctrlSticky = false;
    private boolean ctrlLocked = false;
    private boolean altSticky = false;
    private boolean altLocked = false;

    public interface ModifierChangeListener {
        void onModifierChanged(boolean ctrlActive, boolean ctrlLocked, boolean altActive, boolean altLocked);
    }

    private ModifierChangeListener modifierListener;

    public interface PasteListener {
        void onPasteRequested();
    }

    private PasteListener pasteListener;

    public void setPasteListener(PasteListener listener) {
        this.pasteListener = listener;
    }

    public interface PromptSubmitListener {
        void onPromptSubmitted();
    }

    private PromptSubmitListener promptSubmitListener;

    public void setPromptSubmitListener(PromptSubmitListener listener) {
        this.promptSubmitListener = listener;
    }

    public TerminalView(Context context) {
        super(context);
        init();
    }

    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TerminalView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private GestureDetector gestureDetector;
    private float scrollAccumulatorY = 0f;

    public interface OnTerminalLongClickListener {
        void onTerminalLongClick();
    }

    private OnTerminalLongClickListener longClickListener;

    public void setOnTerminalLongClickListener(OnTerminalLongClickListener listener) {
        this.longClickListener = listener;
    }

    private void init() {
        setFocusable(true);
        setFocusableInTouchMode(true);
        requestFocus();

        try {
            gestureDetector = new GestureDetector(getContext(), new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDown(MotionEvent e) {
                    return true;
                }

                @Override
                public void onLongPress(MotionEvent e) {
                    if (longClickListener != null) {
                        longClickListener.onTerminalLongClick();
                    }
                }

                @Override
                public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                    scrollAccumulatorY += distanceY;
                    int rowHeight = bridge != null ? bridge.getCharHeight() : 30;
                    if (rowHeight <= 0) rowHeight = 30;
                    while (scrollAccumulatorY < -rowHeight) {
                        sendPageUp();
                        scrollAccumulatorY += rowHeight;
                    }
                    while (scrollAccumulatorY > rowHeight) {
                        sendPageDown();
                        scrollAccumulatorY -= rowHeight;
                    }
                    return true;
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "GestureDetector initialization failed", t);
        }
    }

    public void setBridge(TerminalBridge bridge) {
        this.bridge = bridge;
        if (bridge != null) {
            bridge.setTerminalView(this);
        }
    }

    public void setModifierChangeListener(ModifierChangeListener listener) {
        this.modifierListener = listener;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (bridge != null && w > 0 && h > 0) {
            bridge.onDimensionsChanged(w, h);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        try {
            if (bridge != null) {
                bridge.draw(canvas, getWidth(), getHeight());
            }
        } catch (Throwable t) {
            Log.e(TAG, "Exception during onDraw", t);
        }
    }

    // Frame coalescing: throttle redraws during high-throughput output to ~30-60 FPS (~33ms)
    private static final long FRAME_INTERVAL_MS = 33L;
    private final android.os.Handler redrawHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable redrawRunnable = new Runnable() {
        @Override
        public void run() {
            redrawScheduled = false;
            lastRedrawTime = android.os.SystemClock.uptimeMillis();
            invalidate();
        }
    };
    private volatile boolean redrawScheduled = false;
    private volatile long lastRedrawTime = 0;

    public void requestCoalescedRedraw() {
        long now = android.os.SystemClock.uptimeMillis();
        long elapsed = now - lastRedrawTime;
        if (elapsed >= FRAME_INTERVAL_MS) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                if (redrawScheduled) {
                    redrawHandler.removeCallbacks(redrawRunnable);
                    redrawScheduled = false;
                }
                lastRedrawTime = now;
                invalidate();
            } else {
                if (!redrawScheduled) {
                    redrawScheduled = true;
                    redrawHandler.post(redrawRunnable);
                }
            }
        } else {
            if (!redrawScheduled) {
                redrawScheduled = true;
                redrawHandler.postDelayed(redrawRunnable, FRAME_INTERVAL_MS - elapsed);
            }
        }
    }

    public void postInvalidateImmediate() {
        redrawHandler.removeCallbacks(redrawRunnable);
        redrawScheduled = false;
        lastRedrawTime = android.os.SystemClock.uptimeMillis();
        super.postInvalidate();
    }

    // Touch bar actions
    public void toggleCtrl() {
        if (!ctrlSticky) {
            ctrlSticky = true;
            ctrlLocked = false;
        } else if (!ctrlLocked) {
            ctrlLocked = true;
        } else {
            ctrlSticky = false;
            ctrlLocked = false;
        }
        notifyModifiers();
    }

    public void toggleAlt() {
        if (!altSticky) {
            altSticky = true;
            altLocked = false;
        } else if (!altLocked) {
            altLocked = true;
        } else {
            altSticky = false;
            altLocked = false;
        }
        notifyModifiers();
    }

    public void sendEsc() {
        if (bridge != null) {
            bridge.sendEscape();
            postInvalidateImmediate();
        }
    }

    public void sendTab() {
        if (bridge != null) {
            bridge.sendTab();
            postInvalidateImmediate();
        }
    }

    public void sendArrowUp() {
        if (bridge != null) {
            bridge.sendKey(vt320.KEY_UP, ' ', 0);
            postInvalidateImmediate();
        }
    }

    public void sendArrowDown() {
        if (bridge != null) {
            bridge.sendKey(vt320.KEY_DOWN, ' ', 0);
            postInvalidateImmediate();
        }
    }

    public void sendArrowLeft() {
        if (bridge != null) {
            bridge.sendKey(vt320.KEY_LEFT, ' ', 0);
            postInvalidateImmediate();
        }
    }

    public void sendArrowRight() {
        if (bridge != null) {
            bridge.sendKey(vt320.KEY_RIGHT, ' ', 0);
            postInvalidateImmediate();
        }
    }

    public void sendPageUp() {
        if (bridge != null) {
            bridge.sendKey(vt320.KEY_PAGE_UP, ' ', 0);
            postInvalidateImmediate();
        }
    }

    public void sendPageDown() {
        if (bridge != null) {
            bridge.sendKey(vt320.KEY_PAGE_DOWN, ' ', 0);
            postInvalidateImmediate();
        }
    }

    public void sendBackspace() {
        if (bridge != null) {
            bridge.sendBackspace();
            postInvalidateImmediate();
        }
    }

    public void sendDelete() {
        if (bridge != null) {
            bridge.sendDelete();
            postInvalidateImmediate();
        }
    }

    /**
     * Testable key event dispatcher for keycode & shortcut mapping.
     */
    public boolean handleKeyEvent(int keyCode, boolean isAlt, boolean isCtrl, boolean isShift, int unicodeChar) {
        if (bridge == null) return false;

        // D-Pad / Arrow keys
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == 19) {
            if (isShift || isAlt) {
                sendPageUp();
            } else {
                sendArrowUp();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == 20) {
            if (isShift || isAlt) {
                sendPageDown();
            } else {
                sendArrowDown();
            }
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == 21) {
            sendArrowLeft();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT || keyCode == 22) {
            sendArrowRight();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == 23) {
            if (isShift || isAlt) {
                sendPageUp();
            } else {
                bridge.sendString("\r");
                HapticHelper.tick(getContext());
                if (promptSubmitListener != null) {
                    promptSubmitListener.onPromptSubmitted();
                }
            }
            return true;
        }

        // Hardware volume keys on the side: directly scroll history!
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == 24) {
            sendPageUp();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == 25) {
            sendPageDown();
            return true;
        }

        // Classic BlackBerry Shift+Space = Page Up
        if ((keyCode == KeyEvent.KEYCODE_SPACE || keyCode == 62) && isShift) {
            sendPageUp();
            return true;
        }

        // Standard terminal keys
        if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == 67) {
            if (isAlt || altSticky) {
                // Alt+Backspace sends ESC + DEL (\033\u007f - kill word backward)
                bridge.sendString("\033\u007f");
                HapticHelper.tick(getContext());
            } else if (isShift) {
                // Shift+Backspace sends Forward Delete (ESC [ 3 ~)
                bridge.sendDelete();
            } else {
                // Standard Backspace sends ASCII 127 (0x7F / DEL) for Linux readline/tmux
                bridge.sendBackspace();
            }
            consumeModifiersAfterKey();
            postInvalidateImmediate();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_FORWARD_DEL || keyCode == 112) {
            bridge.sendDelete();
            consumeModifiersAfterKey();
            postInvalidateImmediate();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == 66) {
            bridge.sendString("\r");
            HapticHelper.tick(getContext());
            if (promptSubmitListener != null) {
                promptSubmitListener.onPromptSubmitted();
            }
            consumeModifiersAfterKey();
            postInvalidateImmediate();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_TAB || keyCode == 61) {
            bridge.sendTab();
            consumeModifiersAfterKey();
            postInvalidateImmediate();
            return true;
        } else if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == 111) {
            bridge.sendEscape();
            consumeModifiersAfterKey();
            postInvalidateImmediate();
            return true;
        }

        char c = Character.toLowerCase((char) unicodeChar);

        // Chords (Alt+M, Ctrl+V, etc.)
        if (isAlt || isCtrl || altSticky || ctrlSticky) {
            boolean activeAlt = isAlt || altSticky;
            boolean activeCtrl = isCtrl || ctrlSticky;

            // Alt+C or Ctrl+C -> Ctrl+C (\003, SIGINT / abort generation)
            if (keyCode == KeyEvent.KEYCODE_C || keyCode == 31 || c == 'c') {
                bridge.sendString("\003");
                HapticHelper.tick(getContext());
                consumeModifiersAfterKey();
                postInvalidateImmediate();
                return true;
            }

            // Alt+U or Ctrl+U -> Ctrl+U (\025, Kill entire input line)
            if (keyCode == KeyEvent.KEYCODE_U || keyCode == 49 || c == 'u') {
                bridge.sendString("\025");
                HapticHelper.tick(getContext());
                consumeModifiersAfterKey();
                postInvalidateImmediate();
                return true;
            }

            // Alt+X or Alt+E -> Escape (\033, Cancel prompt in agy)
            if (keyCode == KeyEvent.KEYCODE_X || keyCode == 52 || c == 'x' ||
                keyCode == KeyEvent.KEYCODE_E || keyCode == 33 || c == 'e') {
                bridge.sendEscape();
                HapticHelper.tick(getContext());
                consumeModifiersAfterKey();
                postInvalidateImmediate();
                return true;
            }

            // Alt+L or Ctrl+L -> Ctrl+L (\014, Clear and redraw terminal screen)
            if (keyCode == KeyEvent.KEYCODE_L || keyCode == 40 || c == 'l') {
                bridge.sendString("\014");
                consumeModifiersAfterKey();
                postInvalidateImmediate();
                return true;
            }

            // Alt+D or Ctrl+D -> Ctrl+D (\004, EOF / exit)
            if (keyCode == KeyEvent.KEYCODE_D || keyCode == 32 || c == 'd') {
                bridge.sendString("\004");
                consumeModifiersAfterKey();
                postInvalidateImmediate();
                return true;
            }

            // Alt+B or Ctrl+B -> Ctrl+B (\002, Tmux prefix)
            if (keyCode == KeyEvent.KEYCODE_B || keyCode == 30 || c == 'b') {
                bridge.sendString("\002");
                consumeModifiersAfterKey();
                postInvalidateImmediate();
                return true;
            }

            // Alt+M or Ctrl+M -> Carriage Return (\r, Enter / submit)
            if (keyCode == KeyEvent.KEYCODE_M || keyCode == 41 || c == 'm') {
                bridge.sendString("\r");
                HapticHelper.tick(getContext());
                if (promptSubmitListener != null) {
                    promptSubmitListener.onPromptSubmitted();
                }
                consumeModifiersAfterKey();
                postInvalidateImmediate();
                return true;
            }

            // Ctrl+V -> Paste
            if (activeCtrl && (keyCode == KeyEvent.KEYCODE_V || keyCode == 50 || c == 'v')) {
                if (pasteListener != null) {
                    pasteListener.onPasteRequested();
                }
                consumeModifiersAfterKey();
                postInvalidateImmediate();
                return true;
            }
        }

        if (unicodeChar > 0) {
            if (c == 8 || c == 127) {
                bridge.sendBackspace();
                consumeModifiersAfterKey();
                postInvalidateImmediate();
                return true;
            }
            if (c == '\r' || c == '\n') {
                bridge.sendString("\r");
                HapticHelper.tick(getContext());
                if (promptSubmitListener != null) {
                    promptSubmitListener.onPromptSubmitted();
                }
                consumeModifiersAfterKey();
                postInvalidateImmediate();
                return true;
            }
            if (isCtrl || ctrlSticky) {
                bridge.sendCtrlChar((char) unicodeChar);
            } else if (isAlt || altSticky) {
                // Alt sends Escape prefix
                bridge.sendEscape();
                bridge.sendString(String.valueOf((char) unicodeChar));
            } else {
                bridge.sendString(String.valueOf((char) unicodeChar));
            }
            consumeModifiersAfterKey();
            postInvalidateImmediate();
            return true;
        }

        return false;
    }

    private boolean handleAltOrCtrlChord(int keyCode, KeyEvent event, boolean isAlt, boolean isCtrl) {
        int unicodeChar = 0;
        if (event != null) {
            try {
                unicodeChar = event.getUnicodeChar(event.getMetaState() & ~KeyEvent.META_CTRL_MASK & ~KeyEvent.META_ALT_MASK);
            } catch (Throwable ignored) {}
        }
        return handleKeyEvent(keyCode, isAlt, isCtrl, event != null && event.isShiftPressed(), unicodeChar);
    }

    public boolean isAltActive(KeyEvent event) {
        return altSticky || (event != null && (event.isAltPressed() || (event.getMetaState() & KeyEvent.META_ALT_ON) != 0));
    }

    public boolean isCtrlActive(KeyEvent event) {
        return ctrlSticky || (event != null && ((event.getMetaState() & KeyEvent.META_CTRL_ON) != 0));
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            boolean isAlt = isAltActive(event);
            boolean isCtrl = isCtrlActive(event);
            boolean isShift = event.isShiftPressed();

            if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == 67) {
                if (isAlt) {
                    if (bridge != null) {
                        bridge.sendString("\033\u007f"); // Kill word backward
                    }
                    HapticHelper.tick(getContext());
                } else if (isShift) {
                    sendDelete();
                } else {
                    sendBackspace();
                }
                consumeModifiersAfterKey();
                postInvalidateImmediate();
                return true;
            }

            if (handleAltOrCtrlChord(keyCode, event, isAlt, isCtrl)) {
                return true;
            }
        }
        return super.onKeyPreIme(keyCode, event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            boolean isAlt = isAltActive(event);
            boolean isCtrl = isCtrlActive(event);
            if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == 67 
                    || keyCode == KeyEvent.KEYCODE_FORWARD_DEL || keyCode == 112
                    || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == 66
                    || isAlt || isCtrl) {
                return onKeyDown(keyCode, event);
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.imeOptions |= EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_ENTER_ACTION;
        outAttrs.inputType = InputType.TYPE_NULL;
        return new BaseInputConnection(this, false) {
            @Override
            public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                if (beforeLength > 0 && bridge != null) {
                    for (int i = 0; i < beforeLength; i++) {
                        bridge.sendBackspace();
                    }
                    postInvalidateImmediate();
                    return true;
                }
                return super.deleteSurroundingText(beforeLength, afterLength);
            }

            @Override
            public boolean sendKeyEvent(KeyEvent event) {
                if (dispatchKeyEvent(event)) {
                    return true;
                }
                return super.sendKeyEvent(event);
            }
        };
    }

    private void notifyModifiers() {
        if (modifierListener != null) {
            modifierListener.onModifierChanged(ctrlSticky, ctrlLocked, altSticky, altLocked);
        }
    }

    private void consumeModifiersAfterKey() {
        if (ctrlSticky && !ctrlLocked) {
            ctrlSticky = false;
        }
        if (altSticky && !altLocked) {
            altSticky = false;
        }
        notifyModifiers();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (bridge == null) return super.onKeyDown(keyCode, event);
        boolean isCtrl = isCtrlActive(event);
        boolean isAlt = isAltActive(event);
        boolean isShift = event != null && event.isShiftPressed();
        int unicodeChar = 0;
        if (event != null) {
            try {
                unicodeChar = event.getUnicodeChar(event.getMetaState() & ~KeyEvent.META_CTRL_MASK & ~KeyEvent.META_ALT_MASK);
            } catch (Throwable ignored) {}
        }
        if (handleKeyEvent(keyCode, isAlt, isCtrl, isShift, unicodeChar)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        if (bridge == null) return super.onTrackballEvent(event);
        if (event.getAction() == MotionEvent.ACTION_MOVE) {
            float y = event.getY();
            if (y < -0.15f) {
                // Scrolling UP on optical trackpad scrolls terminal content UP (scrollback history)
                sendPageUp();
                return true;
            } else if (y > 0.15f) {
                // Scrolling DOWN on optical trackpad scrolls terminal content DOWN
                sendPageDown();
                return true;
            }
            float x = event.getX();
            if (x < -0.2f) {
                sendArrowLeft();
                return true;
            } else if (x > 0.2f) {
                sendArrowRight();
                return true;
            }
            return true;
        } else if (event.getAction() == MotionEvent.ACTION_DOWN) {
            // Trackball center click sends Enter (\r)
            bridge.sendString("\r");
            HapticHelper.tick(getContext());
            if (promptSubmitListener != null) {
                promptSubmitListener.onPromptSubmitted();
            }
            postInvalidateImmediate();
            return true;
        }
        return super.onTrackballEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        requestFocus();
        if (gestureDetector != null && gestureDetector.onTouchEvent(event)) {
            return true;
        }
        return super.onTouchEvent(event);
    }
}
