package com.berrybot.ssh;

import android.content.Context;
import android.util.Log;

import com.berrybot.R;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deterministic Ed25519 seed generation from BIP-39 mnemonic wordlists or custom passphrases.
 * 
 * Generates identical Ed25519 keys across reinstalls and devices, allowing
 * users to configure their server authorized_keys once in their lifetime.
 * 
 * Strict Android 4.3 (API 18) Dalvik runtime compatibility.
 */
public class MnemonicKeyDerivation {
    private static final String TAG = "BerryBot.Mnemonic";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static List<String> cachedWords = null;

    /**
     * Loads the 2048-word BIP-39 English dictionary from res/raw/bip39_english.txt.
     */
    public static synchronized List<String> getWordList(Context context) {
        if (cachedWords != null && !cachedWords.isEmpty()) {
            return cachedWords;
        }
        List<String> words = new ArrayList<>(2048);
        if (context != null) {
            try {
                InputStream is = context.getResources().openRawResource(R.raw.bip39_english);
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, UTF8));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        words.add(line);
                    }
                }
                reader.close();
            } catch (Exception e) {
                Log.e(TAG, "Failed to load bip39 wordlist from res/raw", e);
            }
        }
        cachedWords = words;
        return cachedWords;
    }

    /**
     * Generates a random 12-word mnemonic phrase using cryptographically secure random values.
     */
    public static String generate12WordMnemonic(Context context) {
        List<String> words = getWordList(context);
        if (words == null || words.size() < 2048) {
            return "abandon ability able about above absent absorb abstract absurd abuse access accident";
        }
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            int idx = random.nextInt(words.size());
            if (i > 0) sb.append(' ');
            sb.append(words.get(idx));
        }
        return sb.toString();
    }

    /**
     * Derives a deterministic 32-byte Ed25519 seed from any mnemonic or passphrase
     * using standard PBKDF2-HMAC-SHA512 (2048 rounds).
     */
    public static byte[] deriveSeedFromPassphrase(String passphrase, String salt) {
        if (passphrase == null || passphrase.trim().isEmpty()) return null;
        String normalizedPass = passphrase.trim().replaceAll("\\s+", " ").toLowerCase();
        String normalizedSalt = (salt != null && !salt.isEmpty()) ? "mnemonic" + salt : "mnemonic";
        return pbkdf2HmacSha512(normalizedPass.getBytes(UTF8), normalizedSalt.getBytes(UTF8), 2048, 32);
    }

    /**
     * Pure Java implementation of PBKDF2 with HMAC-SHA512 compatible with API 18 Dalvik.
     */
    public static byte[] pbkdf2HmacSha512(byte[] password, byte[] salt, int iterations, int keyByteLength) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(password, "HmacSHA512"));
            int hashLen = mac.getMacLength(); // 64 bytes
            int blocks = (keyByteLength + hashLen - 1) / hashLen;
            byte[] out = new byte[keyByteLength];
            int outOffset = 0;

            for (int block = 1; block <= blocks; block++) {
                mac.update(salt);
                mac.update((byte) (block >> 24));
                mac.update((byte) (block >> 16));
                mac.update((byte) (block >> 8));
                mac.update((byte) block);
                byte[] u = mac.doFinal();
                byte[] t = u.clone();

                for (int iter = 1; iter < iterations; iter++) {
                    u = mac.doFinal(u);
                    for (int k = 0; k < hashLen; k++) {
                        t[k] ^= u[k];
                    }
                }

                int toCopy = Math.min(hashLen, keyByteLength - outOffset);
                System.arraycopy(t, 0, out, outOffset, toCopy);
                outOffset += toCopy;
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("PBKDF2 HMAC-SHA512 failed", e);
        }
    }
}
