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
 * - Constant Blue: Connected & Agent Idle
 * - Pulsing Yellow / Amber: Agent thinking / processing response
 * - Active Green (Continuous): Response ready! Blinks until user picks up phone & resumes app
 * - Pulsing Red: SSH connection error or disconnect
 *
 * Fully compliant with Android 4.3 Jelly Bean (API 18) Dalvik runtime on BlackBerry 10.
 */
public class LedNotificationManager {
    private static final String TAG = "BerryBot.LED";
    private static final int NOTIFICATION_ID = 2001;
    private static final long STREAM_INACTIVITY_MS = 3000;

    public enum LedState {
        NONE,
        CONNECTING, // Pulsing Amber / Yellow (400ms on / 400ms off)
        CONNECTED,  // Calm Blue breathing pulse (300ms on / 2500ms off) - Agent is Idle
        STREAMING,  // Pulsing Amber / Yellow (500ms on / 500ms off) - Agent thinking/generating
        COMPLETE,   // Continuous Green pulse (800ms on / 800ms off) - Response ready!
        ERROR       // Pulsing Red (400ms on / 800ms off) - Disconnected / Error
    }

    public interface LedStateListener {
        void onLedStateChanged(LedState state, String statusText);
    }

    private static volatile LedNotificationManager instance;

    private final Context context;
    private final NotificationManager notificationManager;
    private final PowerManager powerManager;
    private final Handler mainHandler;

    private volatile boolean isAppForeground = false;
    private volatile boolean isScreenOn = true;
    private volatile boolean isStreaming = false;
    private volatile boolean promptPending = false;
    private LedState currentState = LedState.NONE;
    private String customCompleteMessage = null;
    private LedStateListener stateListener;

    private final Runnable streamIdleRunnable = new Runnable() {
        @Override
        public void run() {
            if (promptPending && isStreaming) {
                isStreaming = false;
                promptPending = false;
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
                refreshNotification();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                isScreenOn = true;
                if (isAppForeground && currentState == LedState.COMPLETE) {
                    // User turned screen on while app is in foreground and viewed response
                    onUserViewedResponse();
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

    public LedState getCurrentState() {
        return currentState;
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

    public void setLedStateListener(LedStateListener listener) {
        this.stateListener = listener;
        if (listener != null && currentState != LedState.NONE) {
            listener.onLedStateChanged(currentState, getStatusTextForState(currentState));
        }
    }

    public void onActivityResumed() {
        isAppForeground = true;
        if (powerManager != null) {
            try {
                isScreenOn = powerManager.isScreenOn();
            } catch (Throwable ignored) {}
        }

        // If the user picked up the phone while green LED was active, transition to idle Blue
        if (currentState == LedState.COMPLETE) {
            onUserViewedResponse();
        } else if (currentState == LedState.CONNECTED) {
            refreshNotification();
        }
    }

    public void onActivityPaused() {
        isAppForeground = false;
        if (powerManager != null) {
            try {
                isScreenOn = powerManager.isScreenOn();
            } catch (Throwable ignored) {}
        }
        refreshNotification();
    }

    public void onUserInteraction() {
        // If user interacts with device while response complete alert is active, clear it
        if (currentState == LedState.COMPLETE) {
            onUserViewedResponse();
        }
    }

    public void onConnecting() {
        isStreaming = false;
        promptPending = false;
        mainHandler.removeCallbacks(streamIdleRunnable);
        setLedState(LedState.CONNECTING, "Connecting to SSH server…");
    }

    public void onConnected() {
        isStreaming = false;
        promptPending = false;
        mainHandler.removeCallbacks(streamIdleRunnable);
        setLedState(LedState.CONNECTED, "BerryBot • Connected & Idle");
    }

    public void onDataReceived(int length) {
        if (length <= 0) return;

        // ONLY manage streaming completion if a prompt was actually submitted by the user
        // Avoids continuous vibration loops caused by tmux clocks, keepalives, or cursor blinks
        if (!promptPending) return;

        mainHandler.removeCallbacks(streamIdleRunnable);

        if (!isStreaming) {
            isStreaming = true;
            setLedState(LedState.STREAMING, "Antigravity streaming response…");
        }

        // When data stream goes silent for 3 seconds after a prompt, mark generation complete
        mainHandler.postDelayed(streamIdleRunnable, STREAM_INACTIVITY_MS);
    }

    public void onPromptSubmitted() {
        promptPending = true;
        isStreaming = true;
        customCompleteMessage = null;
        mainHandler.removeCallbacks(streamIdleRunnable);
        setLedState(LedState.STREAMING, "Antigravity agent thinking…");
        // Initial fallback timeout in case agent takes time before emitting output
        mainHandler.postDelayed(streamIdleRunnable, 12000);
    }

    public void onStreamingComplete() {
        promptPending = false;
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
        promptPending = false;
        mainHandler.removeCallbacks(streamIdleRunnable);
        this.customCompleteMessage = message;
        String alertTitle = (title != null && !title.isEmpty()) ? title : "BerryBot • Antigravity Agent";
        String alertBody = (message != null && !message.isEmpty()) ? message : "Response ready!";
        triggerPushNotification(alertTitle, alertBody, true);
    }

    public void onError(String message) {
        isStreaming = false;
        promptPending = false;
        mainHandler.removeCallbacks(streamIdleRunnable);
        setLedState(LedState.ERROR, message != null ? message : "SSH connection error");
    }

    public void onDisconnected() {
        isStreaming = false;
        promptPending = false;
        mainHandler.removeCallbacks(streamIdleRunnable);
        setLedState(LedState.ERROR, "BerryBot disconnected");
    }

    private void onUserViewedResponse() {
        // Switch from Green (Response Ready) to Blue (Connected & Idle)
        customCompleteMessage = null;
        setLedState(LedState.CONNECTED, "BerryBot • Connected & Idle");
    }

    private void refreshNotification() {
        if (currentState != LedState.NONE) {
            setLedState(currentState, getStatusTextForState(currentState));
        }
    }

    private String getStatusTextForState(LedState state) {
        switch (state) {
            case CONNECTING:
                return "Connecting to SSH server…";
            case CONNECTED:
                return "Connected & Idle";
            case STREAMING:
                return "Agent thinking…";
            case COMPLETE:
                return (customCompleteMessage != null && !customCompleteMessage.isEmpty())
                        ? customCompleteMessage
                        : "Response ready!";
            case ERROR:
                return "SSH connection alert / error";
            default:
                return "BerryBot active";
        }
    }

    /**
     * Triggers completion push notification and sets continuous Green LED.
     * Vibrates ONCE so the user is alerted, then stays in COMPLETE state with green LED pulsing
     * until the phone is picked up and viewed.
     */
    private synchronized void triggerPushNotification(String title, String statusText, boolean withVibrate) {
        this.currentState = LedState.COMPLETE;

        if (stateListener != null) {
            stateListener.onLedStateChanged(currentState, statusText);
        }

        // Vibrate once on completion
        if (withVibrate) {
            if (isAppForeground && isScreenOn) {
                // Subtle single tactile tick if user is actively looking at screen
                HapticHelper.getInstance(context).performTick(25);
            }
        }

        postNotification(title, statusText, Color.GREEN, 800, 800, false);
    }

    private synchronized void setLedState(LedState state, String statusText) {
        this.currentState = state;

        if (stateListener != null) {
            stateListener.onLedStateChanged(state, statusText);
        }

        switch (state) {
            case CONNECTED:
                // When connected and idle: clear notification so BlackBerry hardware LED stays OFF (not flashing red!)
                clearNotificationOnly();
                break;
            case STREAMING:
            case CONNECTING:
                // While thinking/streaming: keep hardware LED quiet; on-screen dot displays yellow
                clearNotificationOnly();
                break;
            case COMPLETE:
                // Response ready: flash hardware notification LED until user picks up phone & views response
                postNotification("BerryBot • Antigravity", statusText, Color.GREEN, 800, 800, false);
                break;
            case ERROR:
                // Disconnected or connection failure
                postNotification("BerryBot", statusText, Color.RED, 400, 800, false);
                break;
            default:
                clearNotificationOnly();
                break;
        }
    }

    private void clearNotificationOnly() {
        try {
            if (notificationManager != null) {
                notificationManager.cancel(NOTIFICATION_ID);
            }
        } catch (Throwable ignored) {}
    }

    private void postNotification(String title, String text, int ledColor, int onMs, int offMs, boolean sound) {
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
                .setContentText(text)
                .setContentIntent(pi)
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(false)
                .setOngoing(false);

            if (sound) {
                builder.setDefaults(Notification.DEFAULT_SOUND);
            }

            builder.setLights(ledColor, onMs, offMs);

            Notification notification = builder.build();
            // Enable hardware LED flashing flag
            notification.flags |= Notification.FLAG_SHOW_LIGHTS;
            // Prevent auto-cancel so LED continues flashing until phone is picked up
            notification.flags &= ~Notification.FLAG_AUTO_CANCEL;
            // Never let default lights override custom RGB color
            notification.defaults &= ~Notification.DEFAULT_LIGHTS;

            // BlackBerry 10 Hub requires a vibrate/sound signature to elevate notification to hardware LED
            if (sound || ledColor == Color.GREEN) {
                if (isAppForeground && isScreenOn) {
                    notification.vibrate = null; // Do not buzz notification if user is actively watching screen
                } else {
                    notification.vibrate = new long[]{0, 150, 100, 150}; // Tactile completion alert for desk/pocket
                }
            } else {
                notification.vibrate = new long[]{0, 1}; // Micro-tick (1ms) to engage BB10 Hub alert driver without user vibration
            }

            notification.ledARGB = ledColor;
            notification.ledOnMS = onMs;
            notification.ledOffMS = offMs;

            if (notificationManager != null) {
                notificationManager.notify(NOTIFICATION_ID, notification);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to post LED notification", t);
        }
    }

    public synchronized void clearNotification() {
        currentState = LedState.NONE;
        customCompleteMessage = null;
        clearNotificationOnly();
    }

    public synchronized void cleanup() {
        clearNotification();
        try {
            context.unregisterReceiver(screenReceiver);
        } catch (Throwable ignored) {}
        instance = null;
    }
}
