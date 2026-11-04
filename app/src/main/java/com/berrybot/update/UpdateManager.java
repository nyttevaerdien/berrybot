package com.berrybot.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StrictMode;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * OTA (Over-The-Air) Self-Updater for BerryBot.
 * Polls for new releases, prompts user to update, downloads the APK,
 * and launches the BlackBerry 10 / Android package installer.
 * 
 * Strict Android 4.3 Jelly Bean (API 18) Dalvik runtime compatibility.
 */
public class UpdateManager {
    private static final String TAG = "BerryBot.Update";
    private static final String PREFS_NAME = "berrybot_prefs";
    private static final String PREF_UPDATE_URL = "update_manifest_url";

    // Default release endpoint via jsDelivr CDN (mirrors GitHub in real-time and supports Android 4.3 cipher suites & TLS 1.2)
    public static final String DEFAULT_UPDATE_URL = 
            "https://cdn.jsdelivr.net/gh/nyttevaerdien/berrybot@release-apk/version.json";

    public static final String[] FALLBACK_UPDATE_URLS = new String[] {
            "https://cdn.jsdelivr.net/gh/nyttevaerdien/berrybot@release-apk/version.json",
            "https://cdn.jsdelivr.net/gh/nyttevaerdien/berrybot@latest/version.json",
            "https://cdn.jsdelivr.net/gh/nyttevaerdien/berrybot@main/version.json",
            "https://fastly.jsdelivr.net/gh/nyttevaerdien/berrybot@release-apk/version.json",
            "https://cdn.jsdelivr.net/gh/nyttevaerdien/berrybot@master/version.json"
    };

    public static final javax.net.ssl.HostnameVerifier HOSTNAME_VERIFIER = new javax.net.ssl.HostnameVerifier() {
        @Override
        public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
            if (hostname == null) return false;
            String lower = hostname.toLowerCase(java.util.Locale.US);
            if (lower.endsWith(".githubusercontent.com") 
                    || lower.endsWith(".github.com") 
                    || lower.endsWith(".github.io")
                    || lower.endsWith(".jsdelivr.net")
                    || lower.equals("jsdelivr.net")
                    || lower.equals("cdn.jsdelivr.net")
                    || lower.equals("fastly.jsdelivr.net")
                    || lower.equals("gcore.jsdelivr.net")
                    || lower.equals("github.com")
                    || lower.equals("raw.githubusercontent.com")
                    || lower.equals("objects.githubusercontent.com")) {
                return true;
            }
            try {
                return javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(hostname, session);
            } catch (Throwable t) {
                return false;
            }
        }
    };

    private static volatile UpdateManager instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String lastFetchError = null;

    private UpdateManager() {}

    public static boolean isActivityAlive(Activity activity) {
        if (activity == null || activity.isFinishing()) return false;
        if (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed()) return false;
        return true;
    }

    public static UpdateManager getInstance() {
        if (instance == null) {
            synchronized (UpdateManager.class) {
                if (instance == null) {
                    instance = new UpdateManager();
                }
            }
        }
        return instance;
    }

    public String getUpdateManifestUrl(Context context) {
        if (context == null) return DEFAULT_UPDATE_URL;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String url = prefs.getString(PREF_UPDATE_URL, DEFAULT_UPDATE_URL);
        // Automatically migrate legacy endpoints which lack ciphers or suffer from branch cache lag
        if (url != null && (url.contains("raw.githubusercontent.com/nyttevaerdien/berrybot") 
                || url.contains("github.com/nyttevaerdien/berrybot/raw")
                || url.contains("@master/version.json"))) {
            url = DEFAULT_UPDATE_URL;
            prefs.edit().putString(PREF_UPDATE_URL, url).commit();
        }
        return url;
    }

    public void setUpdateManifestUrl(Context context, String url) {
        if (context == null || url == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(PREF_UPDATE_URL, url.trim()).commit();
    }

    public int getCurrentVersionCode(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionCode;
        } catch (Throwable t) {
            return 1;
        }
    }

    public String getCurrentVersionName(Context context) {
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            return pInfo.versionName;
        } catch (Throwable t) {
            return "1.0";
        }
    }

    /**
     * Check for updates on a background thread.
     */
    public void checkForUpdates(final Activity activity, final boolean userInitiated) {
        if (!isActivityAlive(activity)) return;

        if (userInitiated) {
            Toast.makeText(activity, "Checking for updates…", Toast.LENGTH_SHORT).show();
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                checkInternal(activity, userInitiated);
            }
        }).start();
    }

    private void checkInternal(final Activity activity, final boolean userInitiated) {
        String configuredUrl = getUpdateManifestUrl(activity);
        lastFetchError = null;
        String json = fetchUrlWithRedirects(configuredUrl, 0);

        if (json == null || json.length() == 0) {
            // If primary endpoint failed (e.g. SSL error on raw.githubusercontent.com), try CDN fallbacks
            for (String fallbackUrl : FALLBACK_UPDATE_URLS) {
                if (!fallbackUrl.equals(configuredUrl)) {
                    Log.i(TAG, "Attempting fallback update URL: " + fallbackUrl);
                    json = fetchUrlWithRedirects(fallbackUrl, 0);
                    if (json != null && json.length() > 0) {
                        break;
                    }
                }
            }
        }

        if (json == null || json.length() == 0) {
            if (userInitiated) {
                final String errDetail = lastFetchError;
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (!isActivityAlive(activity)) return;
                        showUnreachableDialog(activity, errDetail);
                    }
                });
            }
            return;
        }

        try {
            JSONObject obj = new JSONObject(json);
            final int remoteVersionCode = obj.optInt("versionCode", 0);
            final String remoteVersionName = obj.optString("versionName", "");
            
            // Support both apkUrl and downloadUrl in manifest
            String url = obj.optString("apkUrl", "");
            if (url == null || url.length() == 0) {
                url = obj.optString("downloadUrl", "");
            }
            if (url == null || url.length() == 0) {
                url = obj.optString("shortUrlClck", "");
            }
            final String apkUrl = url;
            final String changelog = obj.optString("changelog", "Performance and stability updates for BlackBerry Classic.");

            final int currentCode = getCurrentVersionCode(activity);

            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (!isActivityAlive(activity)) return;
                    if (remoteVersionCode > currentCode && apkUrl.length() > 0) {
                        showUpdateDialog(activity, remoteVersionName, remoteVersionCode, apkUrl, changelog);
                    } else if (userInitiated) {
                        Toast.makeText(activity, "BerryBot is up to date (v" + getCurrentVersionName(activity) + ")", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        } catch (final Throwable t) {
            Log.w(TAG, "Failed to parse version.json: " + t.getMessage());
            if (userInitiated) {
                postToast(activity, "Error parsing update manifest: " + t.getMessage());
            }
        }
    }

    private void showUpdateDialog(final Activity activity, final String newVersionName, final int newVersionCode, final String apkUrl, final String changelog) {
        if (!isActivityAlive(activity)) return;
        new AlertDialog.Builder(activity)
            .setTitle("BerryBot Update Available (v" + newVersionName + ")")
            .setMessage("A new version of BerryBot is available!\n\n" + 
                        "Current: v" + getCurrentVersionName(activity) + " (Build " + getCurrentVersionCode(activity) + ")\n" +
                        "Latest:  v" + newVersionName + " (Build " + newVersionCode + ")\n\n" +
                        "What's New:\n" + changelog + "\n\n" +
                        "Would you like to download and install this update now?")
            .setPositiveButton("Update Now", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    startDownloadAndInstall(activity, apkUrl, newVersionName);
                }
            })
            .setNeutralButton("Configure URL", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    showConfigureUrlDialog(activity);
                }
            })
            .setNegativeButton("Later", null)
            .show();
    }

    private void showUnreachableDialog(final Activity activity, String errorReason) {
        if (!isActivityAlive(activity)) return;
        String errDetail = (errorReason != null && errorReason.length() > 0) ? ("\nReason: " + errorReason + "\n\n") : "\n\n";
        new AlertDialog.Builder(activity)
            .setTitle("Update Check Failed")
            .setMessage("Could not connect to update manifest server." + errDetail +
                        "Endpoint:\n" + getUpdateManifestUrl(activity) + "\n\n" +
                        "Would you like to edit the update server URL?")
            .setPositiveButton("Edit URL", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    showConfigureUrlDialog(activity);
                }
            })
            .setNegativeButton("Close", null)
            .show();
    }

    public void showConfigureUrlDialog(final Activity activity) {
        if (!isActivityAlive(activity)) return;
        final EditText input = new EditText(activity);
        input.setText(getUpdateManifestUrl(activity));
        input.setSingleLine(true);

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 20, 30, 10);
        layout.addView(input);

        new AlertDialog.Builder(activity)
            .setTitle("OTA Update Endpoint URL")
            .setView(layout)
            .setPositiveButton("Save & Check", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    String newUrl = input.getText().toString().trim();
                    if (newUrl.length() > 0) {
                        setUpdateManifestUrl(activity, newUrl);
                        checkForUpdates(activity, true);
                    }
                }
            })
            .setNeutralButton("Reset Default", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface dialog, int which) {
                    setUpdateManifestUrl(activity, DEFAULT_UPDATE_URL);
                    checkForUpdates(activity, true);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void startDownloadAndInstall(final Activity activity, final String apkUrl, final String versionName) {
        if (!isActivityAlive(activity)) return;
        final ProgressDialog progress = new ProgressDialog(activity);
        progress.setTitle("Downloading Update");
        progress.setMessage("Downloading BerryBot v" + versionName + "…");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(false);
        progress.setMax(100);
        progress.setCancelable(true);
        progress.show();

        new Thread(new Runnable() {
            @Override
            public void run() {
                File apkFile = null;
                boolean success = false;
                HttpURLConnection conn = null;
                InputStream in = null;
                FileOutputStream out = null;

                try {
                    File cacheDir = activity.getExternalCacheDir();
                    if (cacheDir == null) cacheDir = activity.getCacheDir();
                    apkFile = new File(cacheDir, "berrybot-update.apk");
                    if (apkFile.exists()) {
                        apkFile.delete();
                    }

                    // Determine candidate download URLs (prioritizing jsDelivr CDN branch for Android 4.3 SSL compatibility)
                    java.util.List<String> downloadCandidates = new java.util.ArrayList<String>();
                    if (apkUrl != null && apkUrl.contains("jsdelivr.net")) {
                        downloadCandidates.add(apkUrl);
                    } else {
                        downloadCandidates.add("https://cdn.jsdelivr.net/gh/nyttevaerdien/berrybot@release-apk/berrybot.apk");
                        downloadCandidates.add("https://fastly.jsdelivr.net/gh/nyttevaerdien/berrybot@release-apk/berrybot.apk");
                        if (apkUrl != null && apkUrl.length() > 0) {
                            downloadCandidates.add(apkUrl);
                        }
                    }

                    Throwable lastDownloadException = null;
                    for (String candidate : downloadCandidates) {
                        try {
                            if (conn != null) {
                                try { conn.disconnect(); } catch (Throwable ignored) {}
                            }
                            Log.i(TAG, "Attempting APK download from: " + candidate);
                            conn = openConnectionWithRedirects(candidate, 0);
                            int responseCode = conn.getResponseCode();
                            if (responseCode == HttpURLConnection.HTTP_OK) {
                                lastDownloadException = null;
                                break;
                            } else {
                                throw new Exception("HTTP response: " + responseCode + " (" + conn.getResponseMessage() + ")");
                            }
                        } catch (Throwable t) {
                            Log.w(TAG, "Candidate download failed (" + candidate + "): " + t.getMessage());
                            lastDownloadException = t;
                        }
                    }

                    if (lastDownloadException != null || conn == null) {
                        throw (lastDownloadException != null ? lastDownloadException : new Exception("Unable to establish APK download connection"));
                    }

                    final int fileLength = conn.getContentLength();
                    in = conn.getInputStream();
                    out = new FileOutputStream(apkFile);

                    byte[] data = new byte[8192];
                    long total = 0;
                    int count;
                    long lastProgressPost = 0;
                    int lastPercent = -1;

                    while ((count = in.read(data)) != -1) {
                        total += count;
                        if (fileLength > 0) {
                            final int progressPercent = (int) (total * 100 / fileLength);
                            long now = android.os.SystemClock.uptimeMillis();
                            if (progressPercent != lastPercent && (now - lastProgressPost >= 200 || progressPercent >= 100)) {
                                lastProgressPost = now;
                                lastPercent = progressPercent;
                                mainHandler.post(new Runnable() {
                                    @Override
                                    public void run() {
                                        try {
                                            if (progress.isShowing() && isActivityAlive(activity)) {
                                                progress.setProgress(progressPercent);
                                            }
                                        } catch (Throwable ignored) {}
                                    }
                                });
                            }
                        }
                        out.write(data, 0, count);
                    }

                    out.flush();
                    success = true;
                } catch (final Throwable t) {
                    Log.e(TAG, "Download failed", t);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (progress.isShowing()) progress.dismiss();
                            } catch (Throwable ignored) {}
                            if (isActivityAlive(activity)) {
                                Toast.makeText(activity, "Download failed: " + t.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        }
                    });
                } finally {
                    try { if (out != null) out.close(); } catch (Exception ignored) {}
                    try { if (in != null) in.close(); } catch (Exception ignored) {}
                    if (conn != null) conn.disconnect();
                }

                if (success && apkFile != null && apkFile.exists()) {
                    final File finalFile = apkFile;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                if (progress.isShowing()) progress.dismiss();
                            } catch (Throwable ignored) {}
                            if (isActivityAlive(activity)) {
                                launchPackageInstaller(activity, finalFile);
                            }
                        }
                    });
                }
            }
        }).start();
    }

    private HttpURLConnection openConnectionWithRedirects(String urlStr, int redirectCount) throws Exception {
        if (redirectCount > 6) {
            throw new Exception("Too many HTTP redirects");
        }
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        if (conn instanceof javax.net.ssl.HttpsURLConnection) {
            try {
                javax.net.ssl.HttpsURLConnection httpsConn = (javax.net.ssl.HttpsURLConnection) conn;
                httpsConn.setSSLSocketFactory(new TLSSocketFactory());
                httpsConn.setHostnameVerifier(HOSTNAME_VERIFIER);
            } catch (Throwable t) {
                Log.w(TAG, "Failed to apply TLSSocketFactory: " + t.getMessage());
            }
        }
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("User-Agent", "BerryBot-OTA/1.0");
        conn.connect();

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_MOVED_PERM 
                || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                || responseCode == 307 
                || responseCode == 308) {
            String location = conn.getHeaderField("Location");
            conn.disconnect();
            if (location != null && location.length() > 0) {
                // If redirect is relative, resolve it
                if (!location.startsWith("http://") && !location.startsWith("https://")) {
                    location = new URL(url, location).toString();
                }
                return openConnectionWithRedirects(location, redirectCount + 1);
            }
        }
        return conn;
    }

    private void launchPackageInstaller(Context context, File apkFile) {
        try {
            // Guard against StrictMode FileUriExposedException on targetSdk >= 24
            try {
                java.lang.reflect.Method m = StrictMode.class.getMethod("disableDeathOnFileUriExposure");
                m.invoke(null);
            } catch (Throwable ignored) {}

            // Set world-readable permissions for Android 4.3 Dalvik / BB10 Package Installer
            apkFile.setReadable(true, false);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "Failed to launch package installer", t);
            Toast.makeText(context, "Failed to launch installer: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String fetchUrlWithRedirects(String urlStr, int redirectCount) {
        if (redirectCount > 6) return null;
        HttpURLConnection conn = null;
        BufferedReader reader = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                try {
                    javax.net.ssl.HttpsURLConnection httpsConn = (javax.net.ssl.HttpsURLConnection) conn;
                    httpsConn.setSSLSocketFactory(new TLSSocketFactory());
                    httpsConn.setHostnameVerifier(HOSTNAME_VERIFIER);
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to apply TLSSocketFactory: " + t.getMessage());
                }
            }
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("User-Agent", "BerryBot-OTA/1.0");

            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM 
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 
                    || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location != null && location.length() > 0) {
                    if (!location.startsWith("http://") && !location.startsWith("https://")) {
                        location = new URL(url, location).toString();
                    }
                    return fetchUrlWithRedirects(location, redirectCount + 1);
                }
                lastFetchError = "Redirect missing location header";
                return null;
            }

            if (code != HttpURLConnection.HTTP_OK) {
                lastFetchError = "HTTP " + code + " " + conn.getResponseMessage();
                return null;
            }

            reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            return sb.toString().trim();
        } catch (Throwable t) {
            Log.w(TAG, "Failed to fetch update URL: " + t.getMessage(), t);
            lastFetchError = t.getClass().getSimpleName() + ": " + t.getMessage();
            return null;
        } finally {
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }

    private void postToast(final Context context, final String msg) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
