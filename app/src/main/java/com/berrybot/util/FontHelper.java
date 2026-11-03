package com.berrybot.util;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

/**
 * Centralized Typeface provider for BerryBot.
 * 
 * Provides DejaVu Sans Mono so that 0 (zero with center dot) is easily
 * distinguishable from O (capital O), and 1 (one) is distinct from l (lowercase L).
 */
public class FontHelper {
    private static final String TAG = "BerryBot.Font";
    private static final String FONT_PATH = "fonts/DejaVuSansMono.ttf";
    private static Typeface monospaceTypeface = null;

    public static synchronized Typeface getMonospaceTypeface(Context context) {
        if (monospaceTypeface != null) {
            return monospaceTypeface;
        }

        if (context != null) {
            try {
                monospaceTypeface = Typeface.createFromAsset(context.getAssets(), FONT_PATH);
                if (monospaceTypeface != null) {
                    return monospaceTypeface;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Could not load " + FONT_PATH + ", falling back to system MONOSPACE: " + t.getMessage());
            }
        }

        monospaceTypeface = Typeface.MONOSPACE;
        return monospaceTypeface;
    }
}
