package com.berrybot.ssh;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Log;

import com.trilead.ssh2.crypto.PEMDecoder;
import com.trilead.ssh2.crypto.keys.Ed25519KeyPairGenerator;
import com.trilead.ssh2.crypto.keys.Ed25519PrivateKey;
import com.trilead.ssh2.crypto.keys.Ed25519Provider;
import com.trilead.ssh2.crypto.keys.Ed25519PublicKey;
import com.trilead.ssh2.signature.Ed25519Verify;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Arrays;

public class KeyStorage {
    private static final String TAG = "BerryBot.KeyStorage";
    private static final String PREF_NAME = "berrybot_keys";
    private static final String KEY_PRIV_PKCS8 = "priv_key_pkcs8";
    private static final String KEY_PUB_X509 = "pub_key_x509";
    private static final String KEY_RAW_PEM = "raw_pem_key";
    private static final String KEY_NICKNAME = "key_nickname";
    private static final String KEY_PASSPHRASE = "key_passphrase";
    private static final String PREF_KEY_STORAGE_VERSION = "key_storage_version";
    private static final int CURRENT_KEY_STORAGE_VERSION = 3;

    private static KeyStorage sInstance;

    public static synchronized KeyStorage getInstance(Context context) {
        if (sInstance == null && context != null) {
            sInstance = new KeyStorage(context.getApplicationContext());
        }
        return sInstance;
    }

    private final SharedPreferences prefs;
    private KeyPair currentKeyPair;
    private String lastImportError = null;

    public String getLastImportError() {
        return lastImportError;
    }

    static {
        Ed25519Provider.insertIfNeeded();
    }

    public KeyStorage(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        loadOrGenerateKey();
    }

    /**
     * Extracts the 32-byte Ed25519 seed from raw seed bytes or PKCS#8 DER (48, 49, 85 bytes).
     * Returns an independent cloned 32-byte array so caller buffers can be safely wiped.
     */
    public static byte[] extractEd25519Seed(byte[] bytes) {
        if (bytes == null || bytes.length < 32) return null;
        if (bytes.length == 32) {
            byte[] seed = new byte[32];
            System.arraycopy(bytes, 0, seed, 0, 32);
            return seed;
        }
        // In PKCS#8 DER, privateKey OCTET STRING is tagged with 0x04, length 0x20 (32 bytes)
        for (int i = 0; i <= bytes.length - 34; i++) {
            if (bytes[i] == 0x04 && (bytes[i + 1] & 0xFF) == 0x20) {
                byte[] seed = new byte[32];
                System.arraycopy(bytes, i + 2, seed, 0, 32);
                return seed;
            }
        }
        // Fallback strictly for standard 48-byte, 49-byte, or 83-85-byte Ed25519 PKCS#8 DER
        if (bytes.length == 48 || bytes.length == 49 || (bytes.length >= 83 && bytes.length <= 85)) {
            byte[] seed = new byte[32];
            System.arraycopy(bytes, bytes.length - 32, seed, 0, 32);
            return seed;
        }
        return null;
    }

    /**
     * Constructs an Ed25519 KeyPair from a 32-byte seed.
     * Clones the seed before handing it to Ed25519PrivateKey so caller can safely wipe the seed array.
     */
    public static KeyPair createEd25519KeyPairFromSeed(byte[] seed) {
        if (seed == null || seed.length != 32) return null;
        try {
            Ed25519PrivateKey privKey = new Ed25519PrivateKey(seed.clone());
            com.google.crypto.tink.subtle.Ed25519Sign.KeyPair tinkKp =
                    com.google.crypto.tink.subtle.Ed25519Sign.KeyPair.newKeyPairFromSeed(seed);
            Ed25519PublicKey pubKey = new Ed25519PublicKey(tinkKp.getPublicKey());
            return new KeyPair(pubKey, privKey);
        } catch (Exception e) {
            Log.e(TAG, "Failed to construct Ed25519 KeyPair from seed", e);
            return null;
        }
    }

    public synchronized KeyPair getKeyPair() {
        if (currentKeyPair == null) {
            loadOrGenerateKey();
        }
        return currentKeyPair;
    }

    public static void destroyKeyPair(KeyPair kp) {
        if (kp == null) return;
        try {
            if (kp.getPrivate() instanceof Ed25519PrivateKey) {
                ((Ed25519PrivateKey) kp.getPrivate()).destroy();
            }
        } catch (Throwable ignored) {
        }
    }

    public synchronized void reload() {
        destroyKeyPair(currentKeyPair);
        currentKeyPair = null;
        loadOrGenerateKey();
    }

    public synchronized String getFingerprint() {
        KeyPair kp = getKeyPair();
        if (kp == null || kp.getPublic() == null) {
            return "None";
        }
        try {
            byte[] encoded;
            if (kp.getPublic() instanceof Ed25519PublicKey) {
                encoded = Ed25519Verify.get().encodePublicKey(kp.getPublic());
            } else if (kp.getPublic() instanceof RSAPublicKey) {
                encoded = com.trilead.ssh2.signature.RSASHA1Verify.get().encodePublicKey(kp.getPublic());
            } else {
                encoded = kp.getPublic().getEncoded();
            }
            if (encoded == null) return "Unknown";
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(encoded);
            String b64 = Base64.encodeToString(digest, Base64.NO_WRAP | Base64.NO_PADDING).trim();
            return "SHA256:" + b64;
        } catch (Exception e) {
            Log.e(TAG, "Failed to compute fingerprint", e);
            return "Error";
        }
    }

    public synchronized String getOpenSSHPublicKey() {
        KeyPair kp = getKeyPair();
        if (kp == null || kp.getPublic() == null) {
            return "";
        }
        String nickname = prefs.getString(KEY_NICKNAME, "berrybot@blackberry");
        return formatOpenSSHPublicKey(kp.getPublic(), nickname);
    }

    /**
     * Formats an Ed25519 or RSA public key into standard OpenSSH format:
     * "ssh-ed25519 <Base64> <nickname>" or "ssh-rsa <Base64> <nickname>".
     */
    public static String formatOpenSSHPublicKey(PublicKey pub, String nickname) {
        if (pub == null) return "";
        try {
            String nick = (nickname != null && !nickname.trim().isEmpty()) ? nickname : "berrybot@blackberry";
            if (pub instanceof Ed25519PublicKey) {
                byte[] encoded = Ed25519Verify.get().encodePublicKey(pub);
                char[] b64Chars = com.trilead.ssh2.crypto.Base64.encode(encoded);
                return "ssh-ed25519 " + new String(b64Chars) + " " + nick;
            } else if (pub instanceof RSAPublicKey) {
                byte[] encoded = com.trilead.ssh2.signature.RSASHA1Verify.get().encodePublicKey(pub);
                char[] b64Chars = com.trilead.ssh2.crypto.Base64.encode(encoded);
                return "ssh-rsa " + new String(b64Chars) + " " + nick;
            } else {
                return pub.getAlgorithm() + " key (" + nick + ")";
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to format OpenSSH public key", e);
            return "";
        }
    }

    public synchronized boolean generateNewEd25519Key(String nickname) {
        try {
            Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
            KeyPair kp = gen.generateKeyPair();
            prefs.edit().remove(KEY_PASSPHRASE).commit();
            saveKeyPair(kp, nickname, null);
            currentKeyPair = kp;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to generate Ed25519 key", e);
            return false;
        }
    }

    /**
     * Generates a deterministic Ed25519 keypair from a seed phrase or user passphrase.
     * The exact same phrase will always generate the exact same public key across reinstalls.
     */
    public synchronized boolean generateDeterministicKeyFromPhrase(String phrase, String nickname) {
        if (phrase == null || phrase.trim().isEmpty()) return false;
        String normalized = phrase.trim().replaceAll("\\s+", " ");
        byte[] seed = MnemonicKeyDerivation.deriveSeedFromPassphrase(normalized, null);
        if (seed == null) return false;
        try {
            KeyPair kp = createEd25519KeyPairFromSeed(seed);
            if (kp != null && kp.getPrivate() != null) {
                prefs.edit()
                        .putString(KEY_PASSPHRASE, normalized)
                        .remove(KEY_RAW_PEM)
                        .commit();
                saveKeyPair(kp, nickname != null ? nickname : "berrybot@classic", null);
                currentKeyPair = kp;
                return true;
            }
        } finally {
            Arrays.fill(seed, (byte) 0);
        }
        return false;
    }

    public synchronized String getSavedPassphrase() {
        return prefs.getString(KEY_PASSPHRASE, null);
    }

    public synchronized boolean importPrivateKey(String keyData, String password, String nickname) {
        lastImportError = null;
        if (keyData == null || keyData.trim().isEmpty()) {
            lastImportError = "Key input is empty";
            return false;
        }
        String trimmed = keyData.replace("\r", "").trim();
        KeyPair kp = null;

        // Strategy 1: Try PEMDecoder (handles traditional OpenSSH, RSA, DSA, EC PEM format)
        char[] pemChars = trimmed.toCharArray();
        try {
            kp = PEMDecoder.decode(pemChars, password);
        } catch (Exception e1) {
            Log.d(TAG, "PEMDecoder failed parse");
            lastImportError = "PEM decode failed: " + e1.getClass().getSimpleName();
        } finally {
            Arrays.fill(pemChars, ' ');
        }

        // Strategy 2: If PEMDecoder failed or returned null, try PKCS#8 or raw seed Base64
        if (kp == null) {
            byte[] der = null;
            byte[] seed = null;
            try {
                String b64 = trimmed
                        .replaceAll("-----[A-Z0-9 ]+-----", "")
                        .replaceAll("\\s+", "");
                der = Base64.decode(b64, Base64.DEFAULT);

                // 2a. Try Ed25519 seed or PKCS#8 DER (48, 49, 85 bytes, or raw 32 bytes)
                seed = extractEd25519Seed(der);
                if (seed != null) {
                    kp = createEd25519KeyPairFromSeed(seed);
                }

                // 2b. If not Ed25519, try RSA PKCS#8
                if (kp == null) {
                    PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(der);
                    try {
                        KeyFactory rsaKf = KeyFactory.getInstance("RSA");
                        PrivateKey priv = rsaKf.generatePrivate(spec);
                        if (priv instanceof RSAPrivateCrtKey) {
                            RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
                            RSAPublicKeySpec pubSpec = new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
                            PublicKey pub = rsaKf.generatePublic(pubSpec);
                            kp = new KeyPair(pub, priv);
                        } else {
                            kp = new KeyPair(null, priv);
                        }
                    } catch (Exception eRsa) {
                        // 2c. Try EC PKCS#8
                        try {
                            KeyFactory ecKf = KeyFactory.getInstance("EC");
                            PrivateKey priv = ecKf.generatePrivate(spec);
                            kp = new KeyPair(null, priv);
                        } catch (Exception eEc) {
                            lastImportError = "Not a recognized key format (Ed25519, RSA, or EC)";
                        }
                    }
                }
            } catch (Exception eDer) {
                if (lastImportError == null) {
                    lastImportError = "Invalid key format or encoding";
                }
            } finally {
                if (der != null) Arrays.fill(der, (byte) 0);
                if (seed != null) Arrays.fill(seed, (byte) 0);
            }
        }

        if (kp != null && kp.getPrivate() != null) {
            saveKeyPair(kp, nickname != null ? nickname : "imported@blackberry", trimmed);
            currentKeyPair = kp;
            lastImportError = null;
            return true;
        }

        Log.w(TAG, "Import failed: " + lastImportError);
        return false;
    }

    private synchronized void loadOrGenerateKey() {
        int version = prefs.getInt(PREF_KEY_STORAGE_VERSION, 0);

        // Version migration: if version < 3, clear any old bundled/compromised key
        if (version < CURRENT_KEY_STORAGE_VERSION) {
            prefs.edit()
                    .remove(KEY_PRIV_PKCS8)
                    .remove(KEY_PUB_X509)
                    .remove(KEY_RAW_PEM)
                    .remove(KEY_NICKNAME)
                    .putInt(PREF_KEY_STORAGE_VERSION, CURRENT_KEY_STORAGE_VERSION)
                    .commit();
            currentKeyPair = null;
        }

        // Try restoring deterministic key from saved passphrase
        String savedPhrase = prefs.getString(KEY_PASSPHRASE, null);
        if (savedPhrase != null && !savedPhrase.trim().isEmpty()) {
            byte[] seed = MnemonicKeyDerivation.deriveSeedFromPassphrase(savedPhrase.trim(), null);
            if (seed != null) {
                try {
                    currentKeyPair = createEd25519KeyPairFromSeed(seed);
                    if (currentKeyPair != null) return;
                } finally {
                    Arrays.fill(seed, (byte) 0);
                }
            }
        }

        // Try loading from saved raw PEM
        String rawPem = prefs.getString(KEY_RAW_PEM, null);
        if (rawPem != null) {
            char[] pemChars = rawPem.toCharArray();
            try {
                currentKeyPair = PEMDecoder.decode(pemChars, null);
                if (currentKeyPair != null) return;
            } catch (Exception ignored) {
            } finally {
                Arrays.fill(pemChars, ' ');
            }
        }

        // Try loading from saved Ed25519 / PKCS#8 key
        String privB64 = prefs.getString(KEY_PRIV_PKCS8, null);
        if (privB64 != null) {
            byte[] privBytes = null;
            byte[] seed = null;
            try {
                privBytes = Base64.decode(privB64, Base64.DEFAULT);
                seed = extractEd25519Seed(privBytes);
                if (seed != null) {
                    currentKeyPair = createEd25519KeyPairFromSeed(seed);
                    if (currentKeyPair != null) return;
                }
                // Fallback to RSA or EC PKCS#8 decode if not Ed25519
                PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(privBytes);
                try {
                    KeyFactory rsaKf = KeyFactory.getInstance("RSA");
                    PrivateKey priv = rsaKf.generatePrivate(spec);
                    if (priv instanceof RSAPrivateCrtKey) {
                        RSAPrivateCrtKey crt = (RSAPrivateCrtKey) priv;
                        RSAPublicKeySpec pubSpec = new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
                        currentKeyPair = new KeyPair(rsaKf.generatePublic(pubSpec), priv);
                        return;
                    }
                } catch (Exception ignoredRsa) {
                    try {
                        KeyFactory ecKf = KeyFactory.getInstance("EC");
                        currentKeyPair = new KeyPair(null, ecKf.generatePrivate(spec));
                        return;
                    } catch (Exception ignoredEc) {
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to restore key from preferences, regenerating...", e);
            } finally {
                if (privBytes != null) Arrays.fill(privBytes, (byte) 0);
                if (seed != null) Arrays.fill(seed, (byte) 0);
            }
        }

        // On first launch: generate a fresh, unique Ed25519 keypair locally on device
        generateNewEd25519Key("berrybot@classic");
    }

    private void saveKeyPair(KeyPair kp, String nickname, String rawPem) {
        if (this.currentKeyPair != null && this.currentKeyPair != kp) {
            destroyKeyPair(this.currentKeyPair);
        }
        SharedPreferences.Editor editor = prefs.edit();
        if (kp.getPrivate() != null) {
            byte[] privEncoded = kp.getPrivate().getEncoded();
            if (privEncoded != null) {
                editor.putString(KEY_PRIV_PKCS8, Base64.encodeToString(privEncoded, Base64.NO_WRAP));
                Arrays.fill(privEncoded, (byte) 0);
            }
        }
        if (kp.getPublic() != null) {
            byte[] pubEncoded = kp.getPublic().getEncoded();
            if (pubEncoded != null) {
                editor.putString(KEY_PUB_X509, Base64.encodeToString(pubEncoded, Base64.NO_WRAP));
            }
        }
        if (nickname != null) {
            editor.putString(KEY_NICKNAME, nickname);
        }
        if (rawPem != null) {
            editor.putString(KEY_RAW_PEM, rawPem);
        } else {
            editor.remove(KEY_RAW_PEM);
        }
        editor.commit();
        this.currentKeyPair = kp;
    }
}
