package com.berrybot.hardware;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.berrybot.MainActivity;
import com.berrybot.R;

/**
 * BlackBerry Classic RGB Notification LED and background push alert manager.
 * Drives the physical top-mounted RGB LED via Android Notification.FLAG_SHOW_LIGHTS:
 * - Pulsing Amber/Yellow: Connecting to SSH server
 * - Solid Green (brief): SSH connection established
 * - Pulsing Cyan: Antigravity agent thinking / streaming response
 * - Solid Green + Push (sound & vibrate): Antigravity agent finished task / response ready
 * - Pulsing Red: SSH connection error or disconnect
 *
 * Fully compliant with Android 4.3 Jelly Bean (API 18) Dalvik runtime on BlackBerry 10.
 */
public class LedNotificationManager {
    private static final String TAG = "BerryBot.LED";
    private static final int NOTIFICATION_ID = 2001;
    private static final long STREAM_INACTIVITY_MS = 2500;

    public enum LedState {
        NONE,
        CONNECTING, // Pulsing Amber / Yellow (400ms on / 400ms off)
        CONNECTED,  // Subtle solid green confirmation (800ms)
        STREAMING,  // Pulsing Cyan (400ms on / 800ms off) - Agent is active/streaming
        COMPLETE,   // Solid Green - Agent finished!
        ERROR       // Pulsing Red (300ms on / 500ms off)
    }

    private static volatile LedNotificationManager instance;

    private final Context context;
    private final NotificationManager notificationManager;
    private final PowerManager powerManager;
    private final Handler mainHandler;

    private volatile boolean isAppForeground = false;
    private volatile boolean isScreenOn = true;
    private volatile boolean isStreaming = false;
    private LedState currentState = LedState.NONE;
    private String customCompleteMessage = null;

    private final Runnable streamIdleRunnable = new Runnable() {
        @Override
        public void run() {
            if (isStreaming) {
                isStreaming = false;
                onStreamingComplete();
            }
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                isScreenOn = false;
                updateLedNotification();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                isScreenOn = true;
                if (isAppForeground) {
                    clearNotification();
                }
            }
        }
    };

    private LedNotificationManager(Context context) {
        this.context = context.getApplicationContext();
        this.notificationManager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        this.powerManager = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());

        if (powerManager != null) {
            try {
                isScreenOn = powerManager.isScreenOn();
            } catch (Throwable ignored) {}
        }

        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            this.context.registerReceiver(screenReceiver, filter);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register screen state receiver", t);
        }
    }

    public static LedNotificationManager getInstance(Context context) {
        if (instance == null) {
            synchronized (LedNotificationManager.class) {
                if (instance == null && context != null) {
                    instance = new LedNotificationManager(context);
                }
            }
        }
        return instance;
    }

    public void onActivityResumed() {
        isAppForeground = true;
        if (powerManager != null) {
            try {
                isScreenOn = powerManager.isScreenOn();
            } catch (Throwable ignored) {}
        }
        clearNotification();
    }

    public void onActivityPaused() {
        isAppForeground = false;
        if (powerManager != null) {
            try {
                isScreenOn = powerManager.isScreenOn();
            } catch (Throwable ignored) {}
        }
        updateLedNotification();
    }

    public void onConnecting() {
        isStreaming = false;
        mainHandler.removeCallbacks(streamIdleRunnable);
        if (shouldShowNotification()) {
            setLedState(LedState.CONNECTING, "Connecting to SSH server…");
        }
    }

    public void onConnected() {
        isStreaming = false;
        mainHandler.removeCallbacks(streamIdleRunnable);
        if (shouldShowNotification()) {
            setLedState(LedState.CONNECTED, "Connected to SSH server");
            mainHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    clearNotification();
                }
            }, 1200);
        } else {
            clearNotification();
        }
    }

    public void onDataReceived(int length) {
        if (length <= 0) return;

        mainHandler.removeCallbacks(streamIdleRunnable);

        if (!isStreaming) {
            isStreaming = true;
            if (shouldShowNotification()) {
                setLedState(LedState.STREAMING, "Antigravity streaming response…");
            }
        }

        // When data stream goes silent for 2.5 seconds, mark generation complete
        mainHandler.postDelayed(streamIdleRunnable, STREAM_INACTIVITY_MS);
    }

    public void onPromptSubmitted() {
        isStreaming = true;
        customCompleteMessage = null;
        mainHandler.removeCallbacks(streamIdleRunnable);
        mainHandler.postDelayed(streamIdleRunnable, 6000);
        if (shouldShowNotification()) {
            setLedState(LedState.STREAMING, "Waiting for Antigravity response…");
        }
    }

    public void onStreamingComplete() {
        if (currentState != LedState.ERROR) {
            String msg = (customCompleteMessage != null && !customCompleteMessage.isEmpty())
                    ? customCompleteMessage
                    : "Antigravity response ready!";
            triggerPushNotification("BerryBot • Antigravity Agent", msg, true);
        }
    }

    /**
     * Explicit trigger to send push notification and LED alert when agent finishes.
     * Can be invoked via OSC 9, OSC 777, or completion hooks.
     */
    public void sendAgentPushNotification(String title, String message) {
        isStreaming = false;
        mainHandler.removeCallbacks(streamIdleRunnable);
        this.customCompleteMessage = message;
        String alertTitle = (title != null && !title.isEmpty()) ? title : "BerryBot • Antigravity Agent";
        String alertBody = (message != null && !message.isEmpty()) ? message : "Agent task finished • Response ready";
        triggerPushNotification(alertTitle, alertBody, true);
    }

    public void onError(String message) {
        isStreaming = false;
        mainHandler.removeCallbacks(streamIdleRunnable);
        setLedState(LedState.ERROR, message != null ? message : "SSH connection error");
    }

    public void onDisconnected() {
        isStreaming = false;
        mainHandler.removeCallbacks(streamIdleRunnable);
        setLedState(LedState.ERROR, "BerryBot disconnected");
    }

    private boolean shouldShowNotification() {
        return !isAppForeground || !isScreenOn;
    }

    private void updateLedNotification() {
        if (!shouldShowNotification()) {
            clearNotification();
        } else if (currentState != LedState.NONE) {
            setLedState(currentState, getStatusTextForState(currentState));
        }
    }

    private String getStatusTextForState(LedState state) {
        switch (state) {
            case CONNECTING:
                return "Connecting to SSH server…";
            case CONNECTED:
                return "Connected to SSH server";
            case STREAMING:
                return "Antigravity streaming response…";
            case COMPLETE:
                return (customCompleteMessage != null && !customCompleteMessage.isEmpty())
                        ? customCompleteMessage
                        : "Antigravity response ready!";
            case ERROR:
                return "SSH connection alert / error";
            default:
                return "BerryBot active";
        }
    }

    /**
     * Triggers a rich push notification with sound, vibration, and top notification LED.
     */
    private synchronized void triggerPushNotification(String title, String statusText, boolean withPushAlert) {
        this.currentState = LedState.COMPLETE;

        // In foreground and screen on: provide tactile completion vibration
        if (!shouldShowNotification()) {
            HapticHelper.getInstance(context).performCompletionPattern();
            return;
        }

        try {
            Intent intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                pendingFlags |= 0x04000000; // PendingIntent.FLAG_IMMUTABLE
            }
            PendingIntent pi = PendingIntent.getActivity(context, 0, intent, pendingFlags);

            Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(statusText)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOngoing(false);

            if (withPushAlert) {
                // Configure system sound and tactile vibration pattern
                builder.setDefaults(Notification.DEFAULT_SOUND);
                Notification notification = builder.build();
                notification.flags |= Notification.FLAG_SHOW_LIGHTS | Notification.FLAG_AUTO_CANCEL;
                notification.vibrate = new long[]{0, 150, 100, 150};
                notification.ledARGB = Color.GREEN;
                notification.ledOnMS = 1000;
                notification.ledOffMS = 0; // Solid Green constant ON
                if (notificationManager != null) {
                    notificationManager.notify(NOTIFICATION_ID, notification);
                }
            } else {
                setLedState(LedState.COMPLETE, statusText);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to trigger push notification", t);
        }
    }

    private synchronized void setLedState(LedState state, String statusText) {
        this.currentState = state;
        if (!shouldShowNotification() && state != LedState.ERROR) {
            return;
        }

        try {
            Intent intent = new Intent(context, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) {
                pendingFlags |= 0x04000000; // PendingIntent.FLAG_IMMUTABLE
            }
            PendingIntent pi = PendingIntent.getActivity(context, 0, intent, pendingFlags);

            Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("BerryBot")
                .setContentText(statusText)
                .setContentIntent(pi)
                .setAutoCancel(true);

            int ledColor;
            int onMs;
            int offMs;

            switch (state) {
                case CONNECTING:
                    // Pulsing Warm Amber / Yellow
                    ledColor = 0xFFE5C07B;
                    onMs = 400;
                    offMs = 400;
                    builder.setOngoing(true);
                    break;
                case CONNECTED:
                    // Subtle solid green confirmation
                    ledColor = Color.GREEN;
                    onMs = 800;
                    offMs = 0;
                    builder.setOngoing(false);
                    break;
                case STREAMING:
                    // Pulsing Cyan
                    ledColor = Color.CYAN;
                    onMs = 400;
                    offMs = 800;
                    builder.setOngoing(true);
                    break;
                case COMPLETE:
                    // Solid Green
                    ledColor = Color.GREEN;
                    onMs = 1000;
                    offMs = 0; // Solid constant ON
                    builder.setOngoing(false);
                    break;
                case ERROR:
                    // Pulsing Red
                    ledColor = Color.RED;
                    onMs = 300;
                    offMs = 500;
                    builder.setOngoing(false);
                    break;
                default:
                    clearNotification();
                    return;
            }

            Notification notification = builder.build();
            notification.flags |= Notification.FLAG_SHOW_LIGHTS;
            notification.ledARGB = ledColor;
            notification.ledOnMS = onMs;
            notification.ledOffMS = offMs;

            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to update LED notification", t);
        }
    }

    public synchronized void clearNotification() {
        currentState = LedState.NONE;
        customCompleteMessage = null;
        try {
            if (notificationManager != null) {
                notificationManager.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {}
    }

    public synchronized void cleanup() {
        clearNotification();
        try {
            context.unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {}
        instance = null; // Reset singleton instance so recreation re-registers receiver
    }
}
