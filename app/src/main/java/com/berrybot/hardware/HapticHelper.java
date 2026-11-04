package com.berrybot.hardware;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Vibrator;
import android.util.Log;

/**
 * Hardware haptic tactile feedback helper for BlackBerry Classic.
 * Provides subtle 15-20ms haptic ticks on key physical interactions.
 * Fully compatible with Android 4.3 Jelly Bean (API 18) Dalvik runtime.
 */
public class HapticHelper {
    private static final String TAG = "BerryBot.Haptic";
    private static final String PREF_NAME = "berrybot_prefs";
    private static final String KEY_HAPTIC = "haptic_feedback";
    private static final long DEFAULT_TICK_MS = 20;

    private static volatile HapticHelper instance;
    private final Context context;
    private final Vibrator vibrator;

    private HapticHelper(Context context) {
        this.context = context.getApplicationContext();
        Vibrator v = null;
        try {
            v = (Vibrator) this.context.getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to get Vibrator service", t);
        }
        this.vibrator = v;
    }

    public static HapticHelper getInstance(Context context) {
        if (instance == null) {
            synchronized (HapticHelper.class) {
                if (instance == null && context != null) {
                    instance = new HapticHelper(context);
                }
            }
        }
        return instance;
    }

    public boolean isHapticEnabled() {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            return prefs.getBoolean(KEY_HAPTIC, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public void setHapticEnabled(boolean enabled) {
        try {
            SharedPreferences.Editor editor = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
            editor.putBoolean(KEY_HAPTIC, enabled);
            editor.apply();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to persist haptic preference", t);
        }
    }

    public void performTick() {
        performTick(DEFAULT_TICK_MS);
    }

    public void performKeystrokeTick() {
        performTick(10);
    }

    public void performTrackpadTick() {
        performTick(20);
    }

    private long lastAlertPatternTime = 0;

    public void performAlertPattern() {
        if (!isHapticEnabled()) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastAlertPatternTime < 2000) return; // Debounce terminal bell vibrations
        lastAlertPatternTime = now;
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(15); // Subtle 15ms tactile feedback instead of jarring double-buzz
            }
        } catch (Throwable t) {
            Log.w(TAG, "Vibrate alert pattern failed: " + t.getMessage());
        }
    }

    public void performCompletionPattern() {
        if (!isHapticEnabled()) return;
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(new long[]{0, 50, 70, 80}, -1);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Vibrate completion pattern failed: " + t.getMessage());
        }
    }

    public void performTick(long durationMs) {
        if (!isHapticEnabled()) return;
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(durationMs);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Vibrate tick failed: " + t.getMessage());
        }
    }

    public static void tick(Context context) {
        if (context == null) return;
        HapticHelper helper = getInstance(context);
        if (helper != null) {
            helper.performTick();
        }
    }
}
