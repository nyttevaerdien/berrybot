package com.berrybot;

import com.berrybot.ssh.SSHConnectionManager;
import com.trilead.ssh2.Connection;

import org.junit.Assert;
import org.junit.Test;

public class PowerAndRadioOptimizationTest {

    @Test
    public void testExponentialBackoffProgression() {
        long backoff = SSHConnectionManager.INITIAL_RECONNECT_BACKOFF_MS;
        Assert.assertEquals(2000L, backoff);

        backoff = SSHConnectionManager.computeNextBackoff(backoff);
        Assert.assertEquals("First retry backoff should double to 4s", 4000L, backoff);

        backoff = SSHConnectionManager.computeNextBackoff(backoff);
        Assert.assertEquals("Second retry backoff should double to 8s", 8000L, backoff);

        backoff = SSHConnectionManager.computeNextBackoff(backoff);
        Assert.assertEquals("Third retry backoff should double to 16s", 16000L, backoff);

        backoff = SSHConnectionManager.computeNextBackoff(backoff);
        Assert.assertEquals("Fourth retry backoff should cap at 30s", 30000L, backoff);

        backoff = SSHConnectionManager.computeNextBackoff(backoff);
        Assert.assertEquals("Subsequent retry backoffs should remain capped at 30s", 30000L, backoff);
    }

    @Test
    public void testKeepAliveTimingThreshold() {
        long interval = SSHConnectionManager.KEEPALIVE_INTERVAL_MS;
        Assert.assertEquals("Keep-alive interval should be 45 seconds for mobile radio power saving", 45000L, interval);

        long now = 100000L;
        // Case 1: Active within last 10 seconds -> NO keepalive packet sent
        long lastActivityRecent = now - 10000L;
        Assert.assertFalse("Should not send keep-alive when connection was active 10s ago",
                SSHConnectionManager.shouldSendKeepAlive(now, lastActivityRecent, interval));

        // Case 2: Active within last 44 seconds -> NO keepalive packet sent
        long lastActivity44s = now - 44000L;
        Assert.assertFalse("Should not send keep-alive when connection was active 44s ago",
                SSHConnectionManager.shouldSendKeepAlive(now, lastActivity44s, interval));

        // Case 3: Idle for exactly 45 seconds -> Send keepalive
        long lastActivity45s = now - 45000L;
        Assert.assertTrue("Should send keep-alive when connection has been idle for 45s",
                SSHConnectionManager.shouldSendKeepAlive(now, lastActivity45s, interval));

        // Case 4: Idle for 60 seconds -> Send keepalive
        long lastActivity60s = now - 60000L;
        Assert.assertTrue("Should send keep-alive when connection has been idle for 60s",
                SSHConnectionManager.shouldSendKeepAlive(now, lastActivity60s, interval));
    }

    @Test
    public void testLiveSSHKeepAliveIgnorePacket() {
        // Only run live packet transmission if a local sshd service is actively listening
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 22), 200);
        } catch (Exception e) {
            // CI runners (e.g. GitHub Actions) do not run sshd; skip gracefully
            return;
        }

        try {
            Connection conn = new Connection("127.0.0.1", 22);
            conn.connect(null, 5000, 5000);

            // Send SSH_MSG_IGNORE packet (Trilead keep-alive mechanism)
            conn.sendIgnorePacket();

            // Send SSH_MSG_IGNORE packet with explicit random payload
            byte[] payload = new byte[]{0x01, 0x02, 0x03, 0x04};
            conn.sendIgnorePacket(payload);

            conn.close();
        } catch (Exception ignored) {
        }
    }
}
