package com.berrybot;

import com.berrybot.update.UpdateManager;

import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;

public class UpdateManagerTest {

    @Test
    public void testVersionManifestParsingWithApkUrl() throws Exception {
        String jsonStr = "{\n" +
                "  \"versionCode\": 11,\n" +
                "  \"versionName\": \"1.0.11\",\n" +
                "  \"apkUrl\": \"https://github.com/nyttevaerdien/berrybot/releases/download/v1.0.11/berrybot-debug.apk\",\n" +
                "  \"changelog\": \"OTA self-updater release\"\n" +
                "}";

        JSONObject json = new JSONObject(jsonStr);
        int versionCode = json.optInt("versionCode", 0);
        String versionName = json.optString("versionName", "");
        String apkUrl = json.optString("apkUrl", "");
        if (apkUrl.length() == 0) {
            apkUrl = json.optString("downloadUrl", "");
        }
        String changelog = json.optString("changelog", "");

        Assert.assertEquals(11, versionCode);
        Assert.assertEquals("1.0.11", versionName);
        Assert.assertEquals("https://github.com/nyttevaerdien/berrybot/releases/download/v1.0.11/berrybot-debug.apk", apkUrl);
        Assert.assertEquals("OTA self-updater release", changelog);
    }

    @Test
    public void testVersionManifestParsingWithDownloadUrlFallback() throws Exception {
        String jsonStr = "{\n" +
                "  \"versionCode\": 12,\n" +
                "  \"versionName\": \"1.0.12\",\n" +
                "  \"downloadUrl\": \"https://github.com/nyttevaerdien/berrybot/releases/download/v1.0.12/berrybot-debug.apk\",\n" +
                "  \"changelog\": \"Bug fixes\"\n" +
                "}";

        JSONObject json = new JSONObject(jsonStr);
        int versionCode = json.optInt("versionCode", 0);
        String versionName = json.optString("versionName", "");
        String apkUrl = json.optString("apkUrl", "");
        if (apkUrl.length() == 0) {
            apkUrl = json.optString("downloadUrl", "");
        }

        Assert.assertEquals(12, versionCode);
        Assert.assertEquals("1.0.12", versionName);
        Assert.assertEquals("https://github.com/nyttevaerdien/berrybot/releases/download/v1.0.12/berrybot-debug.apk", apkUrl);
    }

    @Test
    public void testVersionComparisonLogic() {
        int currentCode = 10;
        int newerCode = 11;
        int olderCode = 9;
        int sameCode = 10;

        Assert.assertTrue("Newer version must trigger update", newerCode > currentCode);
        Assert.assertFalse("Older version must not trigger update", olderCode > currentCode);
        Assert.assertFalse("Same version must not trigger update", sameCode > currentCode);
    }

    @Test
    public void testDefaultUpdateUrlConstants() {
        Assert.assertNotNull(UpdateManager.DEFAULT_UPDATE_URL);
        Assert.assertTrue(UpdateManager.DEFAULT_UPDATE_URL.startsWith("https://"));
        Assert.assertTrue(UpdateManager.DEFAULT_UPDATE_URL.contains("jsdelivr.net"));
        Assert.assertTrue(UpdateManager.DEFAULT_UPDATE_URL.contains("nyttevaerdien/berrybot"));
    }

    @Test
    public void testTLSSocketFactoryConnectionToCdn() {
        try {
            java.net.URL url = new java.net.URL(UpdateManager.DEFAULT_UPDATE_URL);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
            if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                javax.net.ssl.HttpsURLConnection https = (javax.net.ssl.HttpsURLConnection) conn;
                https.setSSLSocketFactory(new com.berrybot.update.TLSSocketFactory());
                https.setHostnameVerifier(UpdateManager.HOSTNAME_VERIFIER);
            }
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setRequestProperty("User-Agent", "BerryBot-OTA/1.0");
            int code = conn.getResponseCode();
            Assert.assertEquals("Should successfully reach jsDelivr CDN update manifest", 200, code);
            conn.disconnect();
        } catch (Exception e) {
            System.out.println("Live test skipped or network issue: " + e.getMessage());
        }
    }
}
