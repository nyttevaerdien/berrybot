package com.berrybot;

import com.berrybot.ssh.KeyStorage;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.ConnectionInfo;
import com.trilead.ssh2.crypto.keys.Ed25519KeyPairGenerator;
import com.trilead.ssh2.crypto.keys.Ed25519PrivateKey;
import com.trilead.ssh2.crypto.keys.Ed25519Provider;
import com.trilead.ssh2.crypto.keys.Ed25519PublicKey;
import com.trilead.ssh2.signature.Ed25519Verify;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

public class SSHKeyAndKexTest {

    @BeforeClass
    public static void setUp() {
        Ed25519Provider.insertIfNeeded();
    }

    @Test
    public void testExtractEd25519Seed_Standard48BytePkcs8() throws Exception {
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        KeyPair kp = gen.generateKeyPair();
        byte[] pkcs8Der = kp.getPrivate().getEncoded();

        Assert.assertNotNull("PKCS#8 DER should not be null", pkcs8Der);
        Assert.assertEquals("Standard PKCS#8 Ed25519 DER length must be 48 bytes", 48, pkcs8Der.length);

        byte[] seed = KeyStorage.extractEd25519Seed(pkcs8Der);
        Assert.assertNotNull("Extracted seed must not be null", seed);
        Assert.assertEquals("Extracted seed must be exactly 32 bytes", 32, seed.length);

        // Reconstruct KeyPair from the seed and verify it matches the generated key
        KeyPair restoredKp = KeyStorage.createEd25519KeyPairFromSeed(seed);
        Assert.assertNotNull("Restored KeyPair must not be null", restoredKp);
        Assert.assertArrayEquals("Restored public key bytes must match original",
                kp.getPublic().getEncoded(), restoredKp.getPublic().getEncoded());
        Assert.assertArrayEquals("Restored private key bytes must match original",
                kp.getPrivate().getEncoded(), restoredKp.getPrivate().getEncoded());
    }

    @Test
    public void testExtractEd25519Seed_ConnectBot49ByteDer() throws Exception {
        String pem = "-----BEGIN PRIVATE KEY-----\n" +
                "MC8CAQAwCAYDK2VkCgEBBCABAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQ==\n" +
                "-----END PRIVATE KEY-----\n";

        String b64 = pem.replaceAll("-----[A-Z0-9 ]+-----", "").replaceAll("\\s+", "");
        byte[] der = Base64.getDecoder().decode(b64);
        Assert.assertEquals("DER length for ConnectBot key must be 49 bytes", 49, der.length);

        byte[] seed = KeyStorage.extractEd25519Seed(der);
        Assert.assertNotNull("Seed must not be null", seed);
        Assert.assertEquals("Seed length must be 32 bytes", 32, seed.length);
        for (byte b : seed) {
            Assert.assertEquals(1, b);
        }
    }

    @Test
    public void testExtractEd25519Seed_EdgeCases() {
        // 1. Raw 32-byte seed
        byte[] rawSeed = new byte[32];
        Arrays.fill(rawSeed, (byte) 0x42);
        byte[] extracted = KeyStorage.extractEd25519Seed(rawSeed);
        Assert.assertArrayEquals("Raw 32-byte seed should be returned as-is", rawSeed, extracted);

        // 2. Null input
        Assert.assertNull("Null input must return null", KeyStorage.extractEd25519Seed(null));

        // 3. Truncated input (< 32 bytes)
        byte[] truncated = new byte[31];
        Assert.assertNull("Input < 32 bytes must return null", KeyStorage.extractEd25519Seed(truncated));

        // 4. 48-byte arbitrary buffer without ASN.1 tags (fallback to last 32 bytes)
        byte[] arbitrary = new byte[48];
        for (int i = 0; i < 48; i++) arbitrary[i] = (byte) i;
        byte[] fallbackSeed = KeyStorage.extractEd25519Seed(arbitrary);
        Assert.assertNotNull("Fallback seed should not be null", fallbackSeed);
        Assert.assertEquals("Fallback seed length must be 32 bytes", 32, fallbackSeed.length);
        Assert.assertEquals("Fallback seed should take last 32 bytes", (byte) 16, fallbackSeed[0]);
    }

    @Test
    public void testOpenSshPublicKeyFormatting() throws Exception {
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        KeyPair kp = gen.generateKeyPair();

        // 1. Ed25519 format with custom nickname
        String openSsh = KeyStorage.formatOpenSSHPublicKey(kp.getPublic(), "mydevice@blackberry");
        Assert.assertTrue("OpenSSH key should start with ssh-ed25519 AAAAC3NzaC1lZDI1NTE5",
                openSsh.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5"));
        Assert.assertTrue("OpenSSH key should end with custom nickname",
                openSsh.endsWith(" mydevice@blackberry"));

        // 2. Default nickname fallback
        String defaultFormatted = KeyStorage.formatOpenSSHPublicKey(kp.getPublic(), null);
        Assert.assertTrue("Null nickname should default to berrybot@blackberry",
                defaultFormatted.endsWith(" berrybot@blackberry"));

        // 3. Null public key
        Assert.assertEquals("Null public key should return empty string", "", KeyStorage.formatOpenSSHPublicKey(null, "nick"));

        // 4. RSA public key format
        KeyPairGenerator rsaGen = KeyPairGenerator.getInstance("RSA");
        rsaGen.initialize(1024);
        KeyPair rsaKp = rsaGen.generateKeyPair();
        String rsaFormatted = KeyStorage.formatOpenSSHPublicKey(rsaKp.getPublic(), "rsakey@blackberry");
        Assert.assertTrue("RSA key should start with ssh-rsa ", rsaFormatted.startsWith("ssh-rsa "));
        Assert.assertTrue("RSA key should end with nickname", rsaFormatted.endsWith(" rsakey@blackberry"));
    }

    @Test
    public void testEd25519KeyGenerationAndOpenSshFormat() throws Exception {
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        KeyPair kp = gen.generateKeyPair();
        Assert.assertNotNull("KeyPair should not be null", kp);
        Assert.assertNotNull("Public key should not be null", kp.getPublic());
        Assert.assertNotNull("Private key should not be null", kp.getPrivate());

        byte[] pubBytes = Ed25519Verify.get().encodePublicKey(kp.getPublic());
        Assert.assertTrue("Encoded public key should be >= 32 bytes", pubBytes.length >= 32);

        String b64 = Base64.getEncoder().encodeToString(pubBytes);
        String openSshPub = "ssh-ed25519 " + b64 + " berrybot@blackberry";
        Assert.assertTrue("OpenSSH key should start with ssh-ed25519", openSshPub.startsWith("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5"));
    }

    @Test
    public void testKeySpecRoundtrip() throws Exception {
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        KeyPair kp = gen.generateKeyPair();

        // PKCS#8 specs
        byte[] privBytes = kp.getPrivate().getEncoded();
        Ed25519PrivateKey privRestored = new Ed25519PrivateKey(new PKCS8EncodedKeySpec(privBytes));
        Assert.assertEquals("Restored private key should match", kp.getPrivate(), privRestored);

        // Public key X509 specs
        byte[] pubBytes = kp.getPublic().getEncoded();
        Ed25519PublicKey pubRestored = new Ed25519PublicKey(new X509EncodedKeySpec(pubBytes));
        Assert.assertEquals("Restored public key should match", kp.getPublic(), pubRestored);
    }

    @Test
    public void testLiveSSHHandshakeAndFingerprint() throws Exception {
        // Connect to local container sshd
        Connection conn = new Connection("127.0.0.1", 22);
        final String[] serverKeyAlgo = new String[1];
        final String[] computedFp = new String[1];

        ConnectionInfo info = conn.connect((hostname, port, serverHostKeyAlgorithm, serverHostKey) -> {
            serverKeyAlgo[0] = serverHostKeyAlgorithm;
            try {
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                byte[] digest = sha256.digest(serverHostKey);
                computedFp[0] = "SHA256:" + Base64.getEncoder().encodeToString(digest).replaceAll("=+$", "");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return true;
        });

        Assert.assertNotNull("ConnectionInfo should not be null", info);
        Assert.assertEquals("Server key algorithm should be ssh-ed25519", "ssh-ed25519", serverKeyAlgo[0]);
        Assert.assertEquals("Fingerprint should match target server",
                "SHA256:IdMJrWqSJgSzLBX3E5M2sEy8X7OfX4VdENDZ4txdikg", computedFp[0]);
        Assert.assertEquals("KEX should negotiate curve25519-sha256", "curve25519-sha256", info.keyExchangeAlgorithm);

        conn.close();
    }

    @Test
    public void testGenerateAndEncodeEd25519Key() throws Exception {
        Ed25519KeyPairGenerator gen = new Ed25519KeyPairGenerator();
        KeyPair kp = gen.generateKeyPair();
        Assert.assertNotNull("KeyPair must not be null", kp);

        String openSshPub = KeyStorage.formatOpenSSHPublicKey(kp.getPublic(), "berrybot@classic");
        Assert.assertNotNull(openSshPub);
        Assert.assertTrue("Public key must start with ssh-ed25519", openSshPub.startsWith("ssh-ed25519 "));
        Assert.assertTrue("Public key must end with nickname", openSshPub.endsWith(" berrybot@classic"));
    }
}
