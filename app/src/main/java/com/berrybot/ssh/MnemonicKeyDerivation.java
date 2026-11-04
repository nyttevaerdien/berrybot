package com.berrybot.ssh;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.berrybot.R;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Deterministic Ed25519 seed generation from BIP-39 style mnemonics.
 * Allows users on BlackBerry Classic to recover the exact same SSH private key
 * simply by remembering or writing down 12 standard words.
 *
 * Strict Android 4.3 Jelly Bean (API 18) Dalvik runtime compatibility.
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
            InputStream is = null;
            BufferedReader reader = null;
            try {
                is = context.getResources().openRawResource(R.raw.bip39_english);
                reader = new BufferedReader(new InputStreamReader(is, UTF8));
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        words.add(line);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to load bip39 wordlist from res/raw", e);
            } finally {
                if (reader != null) { try { reader.close(); } catch (Exception ignored) {} }
                if (is != null) { try { is.close(); } catch (Exception ignored) {} }
            }
        }
        cachedWords = words;
        return cachedWords;
    }

    /**
     * Generates a random 12-word mnemonic phrase with standard 4-bit SHA-256 checksum over 128-bit entropy.
     */
    public static String generate12WordMnemonic(Context context) {
        List<String> words = getWordList(context);
        if (words == null || words.size() < 2048) {
            throw new IllegalStateException("BIP-39 wordlist is not available or corrupted");
        }

        SecureRandom random = new SecureRandom();
        if (Build.VERSION.SDK_INT <= 18) {
            try {
                FileInputStream fis = new FileInputStream("/dev/urandom");
                byte[] entropySeed = new byte[32];
                fis.read(entropySeed);
                fis.close();
                random.setSeed(entropySeed);
                Arrays.fill(entropySeed, (byte) 0);
            } catch (Throwable ignored) {}
        }

        byte[] entropy = new byte[16]; // 128 bits
        try {
            random.nextBytes(entropy);
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha256.digest(entropy);
            byte checksum = (byte) ((hash[0] & 0xFF) >> 4); // 4-bit checksum
            Arrays.fill(hash, (byte) 0);

            // Build 132 bits (12 words * 11 bits)
            int[] wordIndices = new int[12];
            boolean[] bits = new boolean[132];
            for (int i = 0; i < 16; i++) {
                for (int b = 0; b < 8; b++) {
                    bits[i * 8 + b] = ((entropy[i] >> (7 - b)) & 1) == 1;
                }
            }
            for (int b = 0; b < 4; b++) {
                bits[128 + b] = ((checksum >> (3 - b)) & 1) == 1;
            }
            for (int i = 0; i < 12; i++) {
                int index = 0;
                for (int b = 0; b < 11; b++) {
                    if (bits[i * 11 + b]) index |= (1 << (10 - b));
                }
                wordIndices[i] = index;
            }

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) {
                if (i > 0) sb.append(' ');
                sb.append(words.get(wordIndices[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate secure mnemonic", e);
        } finally {
            Arrays.fill(entropy, (byte) 0);
        }
    }

    /**
     * Derives a deterministic 32-byte Ed25519 seed from any mnemonic or passphrase
     * using standard PBKDF2-HMAC-SHA512 (2048 rounds).
     */
    public static byte[] deriveSeedFromPassphrase(String passphrase, String salt) {
        if (passphrase == null || passphrase.trim().isEmpty()) return null;
        String normalizedPass = passphrase.trim().replaceAll("\\s+", " ").toLowerCase();
        String normalizedSalt = (salt != null && !salt.isEmpty()) ? "mnemonic" + salt : "mnemonic";
        byte[] passBytes = normalizedPass.getBytes(UTF8);
        byte[] saltBytes = normalizedSalt.getBytes(UTF8);
        try {
            return pbkdf2HmacSha512(passBytes, saltBytes, 2048, 32);
        } finally {
            Arrays.fill(passBytes, (byte) 0);
            Arrays.fill(saltBytes, (byte) 0);
        }
    }

    /**
     * Pure Java implementation of PBKDF2 with HMAC-SHA512 compatible with API 18 Dalvik.
     */
    public static byte[] pbkdf2HmacSha512(byte[] password, byte[] salt, int iterations, int keyByteLength) {
        byte[] u = null;
        byte[] t = null;
        byte[] blockBytes = new byte[4];
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(password, "HmacSHA512"));
            int hashLen = mac.getMacLength(); // 64 bytes
            int blocks = (keyByteLength + hashLen - 1) / hashLen;
            byte[] out = new byte[keyByteLength];
            int outOffset = 0;

            for (int block = 1; block <= blocks; block++) {
                mac.update(salt);
                blockBytes[0] = (byte) (block >> 24);
                blockBytes[1] = (byte) (block >> 16);
                blockBytes[2] = (byte) (block >> 8);
                blockBytes[3] = (byte) block;
                mac.update(blockBytes);
                u = mac.doFinal();
                t = u.clone();

                for (int iter = 1; iter < iterations; iter++) {
                    byte[] nextU = mac.doFinal(u);
                    Arrays.fill(u, (byte) 0);
                    u = nextU;
                    for (int k = 0; k < hashLen; k++) {
                        t[k] ^= u[k];
                    }
                }

                int toCopy = Math.min(hashLen, keyByteLength - outOffset);
                System.arraycopy(t, 0, out, outOffset, toCopy);
                outOffset += toCopy;
                if (u != null) { Arrays.fill(u, (byte) 0); u = null; }
                if (t != null) { Arrays.fill(t, (byte) 0); t = null; }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("PBKDF2 HMAC-SHA512 failed", e);
        } finally {
            Arrays.fill(blockBytes, (byte) 0);
            if (u != null) Arrays.fill(u, (byte) 0);
            if (t != null) Arrays.fill(t, (byte) 0);
        }
    }
}
