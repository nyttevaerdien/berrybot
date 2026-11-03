package com.berrybot.ssh;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;

import com.trilead.ssh2.Connection;
import com.trilead.ssh2.ConnectionInfo;
import com.trilead.ssh2.ConnectionMonitor;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

public class SSHConnectionManager implements ConnectionMonitor {
    private static final String TAG = "BerryBot.SSH";

    public static final String DEFAULT_HOST = "";
    public static final int DEFAULT_PORT = 22;
    public static final String DEFAULT_USER = "";

    // Power & Radio Optimization Constants
    public static final long WAKELOCK_CONNECT_TIMEOUT_MS = 15000;   // 15s to establish connection & authenticate
    public static final long WAKELOCK_IO_TIMEOUT_MS = 5000;         // 5s to write user keystrokes / commands
    public static final long WAKELOCK_STREAMING_TIMEOUT_MS = 10000; // 10s CPU keepalive during active streaming responses
    public static final long WAKELOCK_KEEPALIVE_TIMEOUT_MS = 3000;  // 3s to transmit keepalive ping

    // Tuned keep-alive interval (45s): allows mobile cell radio to stay in low-power PCH/IDLE state (~35s)
    // while preventing NAT/firewall mapping expiration (typically 60s+).
    public static final long KEEPALIVE_INTERVAL_MS = 45000;
    public static final long KEEPALIVE_CHECK_INTERVAL_MS = 15000;

    // Exponential backoff for reconnection retries: 2s -> 4s -> 8s -> 16s -> 30s max
    public static final long INITIAL_RECONNECT_BACKOFF_MS = 2000;
    public static final long MAX_RECONNECT_BACKOFF_MS = 30000;

    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        AUTH_FAILED,
        ERROR
    }

    public interface Listener {
        void onConnectionStateChanged(ConnectionState state, String message);
        void onDataReceived(byte[] data, int length);
    }

    private final Context context;
    private final KeyStorage keyStorage;
    private final Handler mainHandler;
    private static final int MSG_DATA = 1;
    private static final int READ_BUFFER_SIZE = 16384;
    private static final int BUFFER_POOL_SIZE = 4;
    private final byte[][] bufferPool = new byte[BUFFER_POOL_SIZE][];
    private int poolCount = 0;

    public synchronized byte[] obtainBuffer(int minSize) {
        if (poolCount > 0) {
            byte[] b = bufferPool[--poolCount];
            if (b != null && b.length >= minSize) {
                return b;
            }
        }
        return new byte[Math.max(minSize, READ_BUFFER_SIZE)];
    }

    public synchronized void recycleBuffer(byte[] b) {
        if (b != null && b.length >= READ_BUFFER_SIZE && poolCount < BUFFER_POOL_SIZE) {
            bufferPool[poolCount++] = b;
        }
    }

    private final ExecutorService executor;
    private final ExecutorService writerExecutor;
    private final ScheduledExecutorService keepAliveExecutor;

    private PowerManager.WakeLock wakeLock;
    private BroadcastReceiver networkReceiver;
    private int lastKnownNetworkType = -1;

    private Listener listener;
    private Connection connection;
    private Session session;
    private InputStream stdout;
    private InputStream stderr;
    private OutputStream stdin;

    private volatile boolean isRunning = false;
    private volatile boolean userRequestedDisconnect = false;
    private volatile boolean hasStartedOnce = false;
    private volatile long lastActivityTime = 0;

    private boolean isAutoReconnectEnabled = true;
    private long currentBackoffMs = INITIAL_RECONNECT_BACKOFF_MS;
    private ScheduledFuture<?> keepAliveFuture;

    private int columns = 80;
    private int rows = 36;
    private int widthPx = 720;
    private int heightPx = 648;

    private String host = DEFAULT_HOST;
    private int port = DEFAULT_PORT;
    private String user = DEFAULT_USER;

    private final Runnable reconnectRunnable = new Runnable() {
        @Override
        public void run() {
            if (!userRequestedDisconnect && isAutoReconnectEnabled) {
                if (isNetworkAvailable()) {
                    Log.i(TAG, "Executing auto-reconnect attempt...");
                    connectInternal(false);
                } else {
                    Log.i(TAG, "Network not available, postponing auto-reconnect");
                    notifyState(ConnectionState.DISCONNECTED, "Offline (waiting for network)");
                }
            }
        }
    };

    public SSHConnectionManager(Context context, KeyStorage keyStorage) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.keyStorage = keyStorage;
        Handler h = null;
        try {
            if (Looper.getMainLooper() != null) {
                h = new Handler(Looper.getMainLooper(), new Handler.Callback() {
                    @Override
                    public boolean handleMessage(android.os.Message msg) {
                        if (msg.what == MSG_DATA) {
                            byte[] data = (byte[]) msg.obj;
                            int len = msg.arg1;
                            try {
                                if (listener != null) {
                                    listener.onDataReceived(data, len);
                                }
                            } catch (Throwable t) {
                                Log.e(TAG, "Error dispatching data to listener", t);
                            } finally {
                                recycleBuffer(data);
                            }
                            return true;
                        }
                        return false;
                    }
                });
            }
        } catch (Throwable ignored) {}
        this.mainHandler = h;

        this.executor = Executors.newCachedThreadPool(new ThreadFactory() {
            private int count = 0;
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "BerryBot-SSH-" + (++count));
                t.setDaemon(true);
                return t;
            }
        });

        this.writerExecutor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "BerryBot-SSHWriter");
                t.setDaemon(true);
                return t;
            }
        });

        this.keepAliveExecutor = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "BerryBot-KeepAlive");
                t.setDaemon(true);
                return t;
            }
        });

        // Initialize WakeLock with non-reference-counted semantics
        if (this.context != null) {
            try {
                PowerManager pm = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BerryBot:SSH");
                    wakeLock.setReferenceCounted(false);
                }
            } catch (Throwable t) {
                Log.w(TAG, "Failed to initialize WakeLock: " + t.getMessage());
            }

            // Register Network Connectivity Receiver
            registerNetworkReceiver();

            try {
                SharedPreferences prefs = this.context.getSharedPreferences("berrybot_prefs", Context.MODE_PRIVATE);
                this.host = prefs.getString("host", DEFAULT_HOST);
                this.port = prefs.getInt("port", DEFAULT_PORT);
                this.user = prefs.getString("user", DEFAULT_USER);
            } catch (Throwable ignored) {}
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void setAutoReconnectEnabled(boolean enabled) {
        this.isAutoReconnectEnabled = enabled;
    }

    public boolean isAutoReconnectEnabled() {
        return isAutoReconnectEnabled;
    }

    public long getCurrentBackoffMs() {
        return currentBackoffMs;
    }

    public long getLastActivityTime() {
        return lastActivityTime;
    }

    public boolean isWakeLockHeld() {
        return wakeLock != null && wakeLock.isHeld();
    }

    public void acquireWakeLock(long timeoutMs) {
        try {
            if (wakeLock != null) {
                wakeLock.acquire(timeoutMs);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to acquire WakeLock: " + t.getMessage());
        }
    }

    public void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to release WakeLock: " + t.getMessage());
        }
    }

    public boolean isNetworkAvailable() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Throwable t) {
            Log.w(TAG, "Error checking network availability", t);
            return true;
        }
    }

    private synchronized void registerNetworkReceiver() {
        if (networkReceiver != null) return;
        networkReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                handleNetworkChange();
            }
        };
        try {
            IntentFilter filter = new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION);
            context.registerReceiver(networkReceiver, filter);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to register network receiver", t);
        }
    }

    private synchronized void unregisterNetworkReceiver() {
        if (networkReceiver != null) {
            try {
                context.unregisterReceiver(networkReceiver);
            } catch (Throwable ignored) {}
            networkReceiver = null;
        }
    }

    private synchronized void handleNetworkChange() {
        boolean isAvailable = isNetworkAvailable();
        int currentType = -1;
        String typeName = "None";
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo info = cm.getActiveNetworkInfo();
                if (info != null && info.isConnected()) {
                    currentType = info.getType();
                    typeName = info.getTypeName();
                }
            }
        } catch (Throwable ignored) {}

        Log.i(TAG, "Network change: available=" + isAvailable + " (" + typeName + "), previousType=" + lastKnownNetworkType);

        if (!isAvailable) {
            lastKnownNetworkType = -1;
            // 1. Cancel pending retry runnable so we don't hammer CPU while offline
            mainHandler.removeCallbacks(reconnectRunnable);
            // 2. Release WakeLock immediately to let CPU sleep
            releaseWakeLock();
            // 3. Notify and close
            if (isConnected()) {
                notifyState(ConnectionState.DISCONNECTED, "Offline (network disconnected)");
                closeSessionAndConnection();
            } else if (isRunning) {
                notifyState(ConnectionState.DISCONNECTED, "Offline (waiting for network)");
            }
        } else {
            // Network is available
            boolean typeChanged = (lastKnownNetworkType != -1 && lastKnownNetworkType != currentType);
            lastKnownNetworkType = currentType;

            if (typeChanged && isConnected()) {
                // Radio transitioned between Wi-Fi and Cellular. Old TCP socket is dead on previous interface.
                Log.i(TAG, "Radio interface transition detected (" + typeName + "), reconnecting session...");
                currentBackoffMs = INITIAL_RECONNECT_BACKOFF_MS;
                closeSessionAndConnection();
                scheduleReconnect("Network switched to " + typeName);
            } else if (hasStartedOnce && !isConnected() && !userRequestedDisconnect && isAutoReconnectEnabled) {
                Log.i(TAG, "Network restored (" + typeName + "), triggering reconnect...");
                currentBackoffMs = INITIAL_RECONNECT_BACKOFF_MS;
                mainHandler.removeCallbacks(reconnectRunnable);
                connectInternal(false);
            }
        }
    }

    public static long computeNextBackoff(long currentBackoffMs) {
        return Math.min(MAX_RECONNECT_BACKOFF_MS, currentBackoffMs * 2);
    }

    private synchronized void scheduleReconnect(String reason) {
        if (userRequestedDisconnect || !isAutoReconnectEnabled) {
            Log.i(TAG, "Auto-reconnect skipped: userRequestedDisconnect=" + userRequestedDisconnect + ", enabled=" + isAutoReconnectEnabled);
            return;
        }

        if (!isNetworkAvailable()) {
            Log.i(TAG, "Auto-reconnect postponed: network unavailable");
            notifyState(ConnectionState.DISCONNECTED, "Offline (waiting for network)");
            return;
        }

        mainHandler.removeCallbacks(reconnectRunnable);

        long delay = currentBackoffMs;
        currentBackoffMs = computeNextBackoff(currentBackoffMs);

        String delaySec = String.valueOf(delay / 1000);
        notifyState(ConnectionState.CONNECTING, "Reconnecting in " + delaySec + "s…");
        Log.i(TAG, "Scheduled reconnect in " + delay + "ms (" + reason + "), next backoff=" + currentBackoffMs + "ms");

        // CRITICAL POWER RULE: Never hold WakeLock during backoff wait
        releaseWakeLock();

        mainHandler.postDelayed(reconnectRunnable, delay);
    }

    public static boolean shouldSendKeepAlive(long now, long lastActivityTime, long keepAliveIntervalMs) {
        return (now - lastActivityTime) >= keepAliveIntervalMs;
    }

    private synchronized void startKeepAlive() {
        stopKeepAlive();
        lastActivityTime = System.currentTimeMillis();
        keepAliveFuture = keepAliveExecutor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                if (!isConnected()) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (shouldSendKeepAlive(now, lastActivityTime, KEEPALIVE_INTERVAL_MS)) {
                    try {
                        if (connection != null && isConnected()) {
                            Log.d(TAG, "Idle for " + ((now - lastActivityTime) / 1000) + "s, sending SSH keep-alive (SSH_MSG_IGNORE)");
                            // Acquire wake lock for 3s to guarantee transmission through radio buffer
                            acquireWakeLock(WAKELOCK_KEEPALIVE_TIMEOUT_MS);
                            connection.sendIgnorePacket();
                            lastActivityTime = System.currentTimeMillis();
                        }
                    } catch (IOException e) {
                        Log.w(TAG, "Keep-alive ping failed: " + e.getMessage());
                        onConnectionBroken(e);
                    }
                }
            }
        }, KEEPALIVE_CHECK_INTERVAL_MS, KEEPALIVE_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void stopKeepAlive() {
        if (keepAliveFuture != null) {
            keepAliveFuture.cancel(false);
            keepAliveFuture = null;
        }
    }

    public void setDimensions(int cols, int rows, int width, int height) {
        this.columns = Math.max(20, cols);
        this.rows = Math.max(10, rows);
        this.widthPx = width;
        this.heightPx = height;

        if (session != null && isConnected()) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (session != null) {
                            session.resizePTY(columns, SSHConnectionManager.this.rows, widthPx, heightPx);
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to resize PTY: " + e.getMessage());
                    }
                }
            });
        }
    }

    public synchronized void connect() {
        hasStartedOnce = true;
        userRequestedDisconnect = false;
        currentBackoffMs = INITIAL_RECONNECT_BACKOFF_MS;
        mainHandler.removeCallbacks(reconnectRunnable);
        connectInternal(true);
    }

    private synchronized void connectInternal(boolean isExplicitUserConnect) {
        if (connection != null || session != null) {
            closeSessionAndConnection();
        }
        isRunning = true;
        if (isExplicitUserConnect) {
            userRequestedDisconnect = false;
        }

        if (!isNetworkAvailable()) {
            Log.i(TAG, "Cannot connect: device is offline");
            notifyState(ConnectionState.DISCONNECTED, "Offline (no network)");
            isRunning = false;
            return;
        }

        acquireWakeLock(WAKELOCK_CONNECT_TIMEOUT_MS);
        notifyState(ConnectionState.CONNECTING, "Connecting…");

        executor.execute(new Runnable() {
            @Override
            public void run() {
                runConnectionLoop();
            }
        });
    }

    public synchronized void disconnect() {
        userRequestedDisconnect = true;
        isRunning = false;
        mainHandler.removeCallbacks(reconnectRunnable);
        stopKeepAlive();
        releaseWakeLock();
        closeSessionAndConnection();
        notifyState(ConnectionState.DISCONNECTED, "Disconnected");
    }

    public synchronized void destroy() {
        userRequestedDisconnect = true;
        mainHandler.removeCallbacks(reconnectRunnable);
        stopKeepAlive();
        releaseWakeLock();
        unregisterNetworkReceiver();
        isRunning = false;
        closeSessionAndConnection();
        try {
            executor.shutdownNow();
        } catch (Exception ignored) {}
        try {
            writerExecutor.shutdownNow();
        } catch (Exception ignored) {}
        try {
            keepAliveExecutor.shutdownNow();
        } catch (Exception ignored) {}
    }

    public boolean isConnected() {
        return connection != null && session != null && isRunning;
    }

    public void sendData(byte[] data) {
        if (data == null || data.length == 0) return;
        sendData(data, 0, data.length);
    }

    public void sendData(final byte[] data, final int offset, final int length) {
        if (stdin == null || data == null || length <= 0) return;
        acquireWakeLock(WAKELOCK_IO_TIMEOUT_MS);
        writerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (stdin != null) {
                        stdin.write(data, offset, length);
                        stdin.flush();
                        lastActivityTime = System.currentTimeMillis();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to write data to SSH stdin", e);
                }
            }
        });
    }

    public void sendByte(final int b) {
        if (stdin == null) return;
        acquireWakeLock(WAKELOCK_IO_TIMEOUT_MS);
        writerExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (stdin != null) {
                        stdin.write(b);
                        stdin.flush();
                        lastActivityTime = System.currentTimeMillis();
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to write byte to SSH stdin", e);
                }
            }
        });
    }

    private void runConnectionLoop() {
        try {
            Log.i(TAG, "Establishing connection to " + host + ":" + port);
            connection = new Connection(host, port);
            connection.addConnectionMonitor(this);

            ConnectionInfo info = connection.connect(new ServerHostKeyVerifier() {
                @Override
                public boolean verifyServerHostKey(String hostname, int port, String serverHostKeyAlgorithm, byte[] serverHostKey) throws Exception {
                    MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                    byte[] digest = sha256.digest(serverHostKey);
                    String fp = "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP).replaceAll("=+$", "");
                    Log.i(TAG, "Server host key: algo=" + serverHostKeyAlgorithm + ", fp=" + fp);

                    if ("127.0.0.1".equals(hostname) || "localhost".equals(hostname)) {
                        return true;
                    }

                    if (context != null) {
                        SharedPreferences knownHosts = context.getSharedPreferences("berrybot_known_hosts", Context.MODE_PRIVATE);
                        String key = hostname + ":" + port;
                        String savedFp = knownHosts.getString(key, null);
                        if (savedFp == null) {
                            // Trust On First Use (TOFU)
                            knownHosts.edit().putString(key, fp).commit();
                            Log.i(TAG, "Recorded trusted host fingerprint for " + key + ": " + fp);
                            return true;
                        } else if (!savedFp.equals(fp)) {
                            Log.e(TAG, "POTENTIAL MITM ATTACK: Host key changed for " + key);
                            throw new java.io.IOException("Host key verification failed for " + key);
                        }
                    }
                    return true;
                }
            }, 10000, 10000);

            Log.i(TAG, "SSH KEX negotiated: " + info.keyExchangeAlgorithm + ", cipher: " + info.clientToServerCryptoAlgorithm);

            // Authenticate with active key
            KeyPair kp = keyStorage.getKeyPair();
            if (kp == null) {
                notifyState(ConnectionState.ERROR, "No SSH key available for authentication");
                isRunning = false;
                releaseWakeLock();
                return;
            }

            Log.i(TAG, "Authenticating user: " + user + " with " + kp.getPublic().getAlgorithm() + " key");
            boolean authed = connection.authenticateWithPublicKey(user, kp);

            if (!authed) {
                Log.e(TAG, "Authentication rejected by server for user " + user);
                notifyState(ConnectionState.AUTH_FAILED, "Auth Failed: Public key not authorized on server");
                isRunning = false;
                closeSessionAndConnection();
                releaseWakeLock();
                return;
            }

            Log.i(TAG, "Authentication successful! Opening session...");
            session = connection.openSession();
            session.requestPTY("xterm-256color", columns, rows, widthPx, heightPx, null);
            session.startShell();

            stdout = session.getStdout();
            stderr = session.getStderr();
            stdin = session.getStdin();

            lastActivityTime = System.currentTimeMillis();
            currentBackoffMs = INITIAL_RECONNECT_BACKOFF_MS;
            mainHandler.removeCallbacks(reconnectRunnable);

            notifyState(ConnectionState.CONNECTED, "Online [main]");

            // Start reader threads (stdout EOF closes session)
            startReaderThread(stdout, true);
            startReaderThread(stderr, false);

            // Start smart keep-alive timer
            startKeepAlive();

            // Release the connection setup wake lock now that connection is established and idle
            releaseWakeLock();

        } catch (Exception e) {
            Log.e(TAG, "SSH Connection error", e);
            closeSessionAndConnection();
            releaseWakeLock();
            isRunning = false;
            if (!userRequestedDisconnect) {
                scheduleReconnect("Connection failed: " + e.getMessage());
            }
        }
    }

    private void startReaderThread(final InputStream stream, final boolean isStdout) {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                byte[] readBuf = new byte[READ_BUFFER_SIZE];
                try {
                    int read;
                    // Efficient blocking read with 16KB buffer and burst packet coalescing
                    while (isRunning && (read = stream.read(readBuf)) != -1) {
                        if (read > 0) {
                            lastActivityTime = System.currentTimeMillis();
                            acquireWakeLock(WAKELOCK_STREAMING_TIMEOUT_MS);
                            int total = read;
                            while (total < readBuf.length && stream.available() > 0) {
                                int extra = stream.read(readBuf, total, Math.min(stream.available(), readBuf.length - total));
                                if (extra <= 0) break;
                                total += extra;
                            }
                            if (listener != null && mainHandler != null) {
                                byte[] copy = obtainBuffer(total);
                                System.arraycopy(readBuf, 0, copy, 0, total);
                                android.os.Message msg = mainHandler.obtainMessage(MSG_DATA, total, 0, copy);
                                msg.sendToTarget();
                            }
                        }
                    }
                } catch (IOException e) {
                    if (isRunning && !userRequestedDisconnect) {
                        Log.w(TAG, "Reader thread (" + (isStdout ? "stdout" : "stderr") + ") closed: " + e.getMessage());
                    }
                } finally {
                    if (isStdout && isRunning && !userRequestedDisconnect) {
                        Log.w(TAG, "Server closed stdout stream");
                        onConnectionBroken(new IOException("Server closed session"));
                    }
                }
            }
        });
    }

    private synchronized void closeSessionAndConnection() {
        stopKeepAlive();
        if (session != null) {
            try { session.close(); } catch (Exception ignored) {}
            session = null;
        }
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
            connection = null;
        }
        stdin = null;
        stdout = null;
        stderr = null;
    }

    private synchronized void onConnectionBroken(Throwable reason) {
        if (userRequestedDisconnect) {
            return;
        }
        String msg = reason != null ? reason.getMessage() : "Connection lost";
        Log.w(TAG, "SSH Connection broken: " + msg);
        stopKeepAlive();
        closeSessionAndConnection();
        releaseWakeLock();
        isRunning = false;
        scheduleReconnect(msg);
    }

    @Override
    public void connectionLost(Throwable reason) {
        String msg = reason != null ? reason.getMessage() : "unknown";
        Log.w(TAG, "SSH ConnectionMonitor reported connection lost: " + msg);
        if (userRequestedDisconnect || (msg != null && msg.toLowerCase().contains("user request"))) {
            return;
        }
        onConnectionBroken(reason);
    }

    private void notifyState(final ConnectionState state, final String message) {
        if (mainHandler != null) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (listener != null) {
                        listener.onConnectionStateChanged(state, message);
                    }
                }
            });
        } else if (listener != null) {
            listener.onConnectionStateChanged(state, message);
        }
    }

    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getUser() { return user; }

    public void updateConnectionParams(String host, int port, String user) {
        this.host = host;
        this.port = port;
        this.user = user;
        if (context != null) {
            try {
                SharedPreferences.Editor editor = context.getSharedPreferences("berrybot_prefs", Context.MODE_PRIVATE).edit();
                editor.putString("host", host);
                editor.putInt("port", port);
                editor.putString("user", user);
                editor.apply();
            } catch (Throwable ignored) {}
        }
    }
}
