package com.berrybot.util;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * Centralized Typeface provider for BerryBot.
 * 
 * Uses Hack Monospace font featuring an unmistakable slashed zero (0 vs O),
 * curved l vs seriffed 1, and crossbarred I for maximum clarity on the BlackBerry Classic.
 * Includes fail-safe internal storage extraction for Android 4.3 Dalvik compatibility.
 */
public class FontHelper {
    private static final String TAG = "BerryBot.Font";
    private static final String FONT_ASSET = "fonts/Hack-Regular.ttf";
    private static final String FONT_FILE_NAME = "Hack-Regular.ttf";
    private static Typeface monospaceTypeface = null;

    public static synchronized Typeface getMonospaceTypeface(Context context) {
        if (monospaceTypeface != null) {
            return monospaceTypeface;
        }

        if (context == null) {
            return Typeface.MONOSPACE;
        }

        Context appCtx = context.getApplicationContext() != null ? context.getApplicationContext() : context;

        // Strategy 1: Direct Asset Loading (Supported when uncompressed via aaptOptions noCompress)
        try {
            monospaceTypeface = Typeface.createFromAsset(appCtx.getAssets(), FONT_ASSET);
            if (monospaceTypeface != null) {
                Log.i(TAG, "Typeface loaded directly from assets: " + FONT_ASSET);
                return monospaceTypeface;
            }
        } catch (Throwable t) {
            Log.w(TAG, "createFromAsset failed (" + t.getMessage() + "), attempting disk extract fallback...");
        }

        // Strategy 2: Extract to Internal Storage & load via Typeface.createFromFile
        // This completely bypasses Dalvik AssetManager memory-mapping limitations on Android 4.3
        try {
            File fontFile = new File(appCtx.getFilesDir(), FONT_FILE_NAME);
            if (!fontFile.exists() || fontFile.length() == 0) {
                InputStream in = appCtx.getAssets().open(FONT_ASSET);
                FileOutputStream out = new FileOutputStream(fontFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = in.read(buf)) > 0) {
                    out.write(buf, 0, len);
                }
                out.flush();
                out.close();
                in.close();
                Log.i(TAG, "Extracted font asset to " + fontFile.getAbsolutePath());
            }

            if (fontFile.exists() && fontFile.length() > 0) {
                monospaceTypeface = Typeface.createFromFile(fontFile);
                if (monospaceTypeface != null) {
                    Log.i(TAG, "Typeface loaded from internal storage file: " + fontFile.getAbsolutePath());
                    return monospaceTypeface;
                }
            }
        } catch (Throwable t2) {
            Log.e(TAG, "Failed disk extraction fallback for font", t2);
        }

        Log.w(TAG, "Fallback to system Typeface.MONOSPACE");
        monospaceTypeface = Typeface.MONOSPACE;
        return monospaceTypeface;
    }
}
