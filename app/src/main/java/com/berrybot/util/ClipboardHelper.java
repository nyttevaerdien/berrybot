package com.berrybot.util;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.util.Log;

/**
 * Utility class for clipboard operations and text normalization compatible with Android API 18.
 */
public final class ClipboardHelper {
    private static final String TAG = "BerryBot.Clipboard";

    private ClipboardHelper() {}

    /**
     * Copies the given text to the system clipboard.
     */
    public static boolean copyToClipboard(Context context, String label, String text) {
        if (context == null || text == null) return false;
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText(label != null ? label : "BerryBot", text);
                clipboard.setPrimaryClip(clip);
                return true;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to copy text to clipboard", t);
        }
        return false;
    }

    /**
     * Retrieves plain text from the system clipboard.
     */
    public static String getClipboardText(Context context) {
        if (context == null) return "";
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && clipboard.hasPrimaryClip() && clipboard.getPrimaryClip().getItemCount() > 0) {
                CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(context);
                if (text != null) {
                    return text.toString();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to get clipboard text", t);
        }
        return "";
    }

    /**
     * Normalizes line endings to carriage returns ('\r') as required by VT terminal emulators.
     */
    public static String normalizeLineEndings(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace("\r\n", "\r").replace("\n", "\r");
    }
}
