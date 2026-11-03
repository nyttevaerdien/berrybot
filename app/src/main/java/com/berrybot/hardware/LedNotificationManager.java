package com.berrybot.hardware;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.berrybot.MainActivity;
import com.berrybot.R;

/**
 * BlackBerry Classic RGB Notification LED and background alert manager.
 * Drives the physical top-mounted RGB LED via Android Notification.FLAG_SHOW_LIGHTS:
 * - Pulsing Cyan: active SSH output streaming
 * - Solid Green: Antigravity response generation finished
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
        STREAMING, // Pulsing Cyan
        COMPLETE,  // Solid Green
        ERROR      // Pulsing Red
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
        mainHandler.removeCallbacks(streamIdleRunnable);
        mainHandler.postDelayed(streamIdleRunnable, 6000);
        if (shouldShowNotification()) {
            setLedState(LedState.STREAMING, "Waiting for Antigravity response…");
        }
    }

    public void onStreamingComplete() {
        if (currentState != LedState.ERROR) {
            setLedState(LedState.COMPLETE, "Antigravity response ready!");
        }
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

    public void onConnected() {
        if (currentState == LedState.ERROR) {
            currentState = LedState.NONE;
            clearNotification();
        }
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
            case STREAMING:
                return "Antigravity streaming response…";
            case COMPLETE:
                return "Antigravity response ready!";
            case ERROR:
                return "SSH connection alert / error";
            default:
                return "BerryBot active";
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
            PendingIntent pi = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);

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
        try {
            if (notificationManager != null) {
                notificationManager.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {}
    }

    public void cleanup() {
        clearNotification();
        try {
            context.unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {}
    }
}
