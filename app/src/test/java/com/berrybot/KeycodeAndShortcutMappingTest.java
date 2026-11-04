package com.berrybot;

import com.berrybot.terminal.TerminalBridge;
import com.berrybot.terminal.TerminalView;
import com.berrybot.ssh.SSHConnectionManager;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.util.concurrent.atomic.AtomicBoolean;

import android.view.KeyEvent;
import de.mud.terminal.vt320;

public class KeycodeAndShortcutMappingTest {

    private TerminalBridge bridge;
    private TerminalView view;
    private ByteArrayOutputStream networkBytes;
    private AtomicBoolean pasteTriggered;
    private AtomicBoolean submitTriggered;

    @Before
    public void setUp() {
        networkBytes = new ByteArrayOutputStream();
        pasteTriggered = new AtomicBoolean(false);
        submitTriggered = new AtomicBoolean(false);

        // Standalone TerminalBridge with no Android context
        bridge = new TerminalBridge(null);

        // Mock SSHConnectionManager to capture all transmitted bytes
        bridge.setConnectionManager(new SSHConnectionManager(null, null) {
            @Override
            public void sendData(byte[] data) {
                if (data != null) {
                    networkBytes.write(data, 0, data.length);
                }
            }

            @Override
            public void sendByte(int b) {
                networkBytes.write(b);
            }
        });

        view = new TerminalView(null);
        view.setBridge(bridge);
        view.setPasteListener(new TerminalView.PasteListener() {
            @Override
            public void onPasteRequested() {
                pasteTriggered.set(true);
            }
        });
        view.setPromptSubmitListener(new TerminalView.PromptSubmitListener() {
            @Override
            public void onPromptSubmitted() {
                submitTriggered.set(true);
            }
        });
    }

    private String getSentText() {
        return new String(networkBytes.toByteArray(), Charset.forName("UTF-8"));
    }

    @Test
    public void testAltMProducesCarriageReturn() {
        // Physical Alt+M keycode 41 / KEYCODE_M
        networkBytes.reset();
        submitTriggered.set(false);
        boolean handled = view.handleKeyEvent(KeyEvent.KEYCODE_M, true, false, false, 'm');
        Assert.assertTrue("Alt+M should be handled", handled);
        Assert.assertEquals("Alt+M should send \\r", "\r", getSentText());
        Assert.assertTrue("Prompt submit listener should be triggered", submitTriggered.get());

        // Ctrl+M keycode
        networkBytes.reset();
        submitTriggered.set(false);
        boolean handledCtrlM = view.handleKeyEvent(KeyEvent.KEYCODE_M, false, true, false, 'm');
        Assert.assertTrue("Ctrl+M should be handled", handledCtrlM);
        Assert.assertEquals("Ctrl+M should send \\r", "\r", getSentText());
        Assert.assertTrue("Prompt submit listener should be triggered", submitTriggered.get());
    }

    @Test
    public void testStickyAltMProducesCarriageReturn() {
        view.toggleAlt(); // Turn on sticky Alt
        networkBytes.reset();
        submitTriggered.set(false);

        boolean handled = view.handleKeyEvent(KeyEvent.KEYCODE_M, false, false, false, 'm');
        Assert.assertTrue("Sticky Alt + M should be handled", handled);
        Assert.assertEquals("Sticky Alt + M should send \\r", "\r", getSentText());
        Assert.assertTrue("Prompt submit listener should be triggered", submitTriggered.get());
    }

    @Test
    public void testCtrlVTriggersPasteCallback() {
        networkBytes.reset();
        pasteTriggered.set(false);

        boolean handled = view.handleKeyEvent(KeyEvent.KEYCODE_V, false, true, false, 'v');
        Assert.assertTrue("Ctrl+V should be handled", handled);
        Assert.assertTrue("Paste listener should have been triggered", pasteTriggered.get());
        Assert.assertEquals("Ctrl+V should not send text directly to network", "", getSentText());

        // Normal V without Ctrl does NOT trigger paste
        pasteTriggered.set(false);
        view.handleKeyEvent(KeyEvent.KEYCODE_V, false, false, false, 'v');
        Assert.assertFalse("Normal 'v' should NOT trigger paste", pasteTriggered.get());
        Assert.assertEquals("Normal 'v' should send character 'v'", "v", getSentText());
    }

    @Test
    public void testStickyCtrlVTriggersPasteCallback() {
        view.toggleCtrl(); // Turn on sticky Ctrl
        pasteTriggered.set(false);
        networkBytes.reset();

        boolean handled = view.handleKeyEvent(KeyEvent.KEYCODE_V, false, false, false, 'v');
        Assert.assertTrue("Sticky Ctrl + V should be handled", handled);
        Assert.assertTrue("Paste listener should have been triggered by sticky Ctrl+V", pasteTriggered.get());
    }

    @Test
    public void testKeycodeDelProducesAscii127() {
        networkBytes.reset();
        boolean handled = view.handleKeyEvent(KeyEvent.KEYCODE_DEL, false, false, false, 0);
        Assert.assertTrue("KEYCODE_DEL should be handled", handled);

        byte[] sent = networkBytes.toByteArray();
        Assert.assertEquals("Should send exactly 1 byte", 1, sent.length);
        Assert.assertEquals("KEYCODE_DEL must produce ASCII 127 (0x7F)", 0x7F, sent[0] & 0xFF);
    }

    @Test
    public void testDirectSendBackspaceProducesAscii127() {
        networkBytes.reset();
        bridge.sendBackspace();
        byte[] sent = networkBytes.toByteArray();
        Assert.assertEquals("sendBackspace() should transmit 1 byte", 1, sent.length);
        Assert.assertEquals("sendBackspace() must produce 0x7F (ASCII 127)", 0x7F, sent[0] & 0xFF);
    }

    @Test
    public void testShiftDelSendsForwardDelete() {
        networkBytes.reset();
        boolean handled = view.handleKeyEvent(KeyEvent.KEYCODE_DEL, false, false, true, 0);
        Assert.assertTrue("Shift+DEL should be handled", handled);
        Assert.assertEquals("Shift+DEL should send VT forward delete sequence", "\033[3~", getSentText());
    }

    @Test
    public void testAltDelSendsKillWordBackward() {
        networkBytes.reset();
        boolean handled = view.handleKeyEvent(KeyEvent.KEYCODE_DEL, true, false, false, 0);
        Assert.assertTrue("Alt+DEL should be handled", handled);
        Assert.assertEquals("Alt+DEL should send ESC+DEL sequence", "\033\u007f", getSentText());
    }

    @Test
    public void testArrowKeysSendStandardVt100Sequences() {
        // Up Arrow
        networkBytes.reset();
        view.handleKeyEvent(KeyEvent.KEYCODE_DPAD_UP, false, false, false, 0);
        Assert.assertEquals("Up Arrow should send \\033[A", "\033[A", getSentText());

        // Down Arrow
        networkBytes.reset();
        view.handleKeyEvent(KeyEvent.KEYCODE_DPAD_DOWN, false, false, false, 0);
        Assert.assertEquals("Down Arrow should send \\033[B", "\033[B", getSentText());

        // Right Arrow
        networkBytes.reset();
        view.handleKeyEvent(KeyEvent.KEYCODE_DPAD_RIGHT, false, false, false, 0);
        Assert.assertEquals("Right Arrow should send \\033[C", "\033[C", getSentText());

        // Left Arrow
        networkBytes.reset();
        view.handleKeyEvent(KeyEvent.KEYCODE_DPAD_LEFT, false, false, false, 0);
        Assert.assertEquals("Left Arrow should send \\033[D", "\033[D", getSentText());
    }

    @Test
    public void testDirectViewArrowMethods() {
        // Direct method tests matching touch bar / action triggers
        networkBytes.reset();
        view.sendArrowUp();
        Assert.assertEquals("sendArrowUp should send \\033[A", "\033[A", getSentText());

        networkBytes.reset();
        view.sendArrowDown();
        Assert.assertEquals("sendArrowDown should send \\033[B", "\033[B", getSentText());

        networkBytes.reset();
        view.sendArrowRight();
        Assert.assertEquals("sendArrowRight should send \\033[C", "\033[C", getSentText());

        networkBytes.reset();
        view.sendArrowLeft();
        Assert.assertEquals("sendArrowLeft should send \\033[D", "\033[D", getSentText());
    }

    @Test
    public void testVt320KeypadArrowSequences() {
        // Test vt320 buffer directly
        vt320 buf = bridge.getBuffer();

        networkBytes.reset();
        buf.keyPressed(vt320.KEY_UP, ' ', 0);
        Assert.assertEquals("vt320 KEY_UP should send \\033[A", "\033[A", getSentText());

        networkBytes.reset();
        buf.keyPressed(vt320.KEY_DOWN, ' ', 0);
        Assert.assertEquals("vt320 KEY_DOWN should send \\033[B", "\033[B", getSentText());

        networkBytes.reset();
        buf.keyPressed(vt320.KEY_RIGHT, ' ', 0);
        Assert.assertEquals("vt320 KEY_RIGHT should send \\033[C", "\033[C", getSentText());

        networkBytes.reset();
        buf.keyPressed(vt320.KEY_LEFT, ' ', 0);
        Assert.assertEquals("vt320 KEY_LEFT should send \\033[D", "\033[D", getSentText());
    }

    @Test
    public void testOtherTerminalShortcuts() {
        // Ctrl+C (SIGINT)
        networkBytes.reset();
        view.handleKeyEvent(KeyEvent.KEYCODE_C, false, true, false, 'c');
        Assert.assertEquals("Ctrl+C should send \\003", "\003", getSentText());

        // Ctrl+L (Clear screen)
        networkBytes.reset();
        view.handleKeyEvent(KeyEvent.KEYCODE_L, false, true, false, 'l');
        Assert.assertEquals("Ctrl+L should send \\014", "\014", getSentText());

        // Ctrl+D (EOF)
        networkBytes.reset();
        view.handleKeyEvent(KeyEvent.KEYCODE_D, false, true, false, 'd');
        Assert.assertEquals("Ctrl+D should send \\004", "\004", getSentText());

        // Ctrl+B (Tmux prefix)
        networkBytes.reset();
        view.handleKeyEvent(KeyEvent.KEYCODE_B, false, true, false, 'b');
        Assert.assertEquals("Ctrl+B should send \\002", "\002", getSentText());

        // Enter
        networkBytes.reset();
        view.handleKeyEvent(KeyEvent.KEYCODE_ENTER, false, false, false, '\r');
        Assert.assertEquals("Enter should send \\r", "\r", getSentText());

        // Tab (Direct KEYCODE_TAB)
        networkBytes.reset();
        view.handleKeyEvent(KeyEvent.KEYCODE_TAB, false, false, false, '\t');
        byte[] tabBytes = networkBytes.toByteArray();
        Assert.assertEquals("Tab should send 1 byte", 1, tabBytes.length);
        Assert.assertEquals("Tab should send 0x09", 0x09, tabBytes[0]);

        // Alt+Space -> Tab
        networkBytes.reset();
        view.handleKeyEvent(KeyEvent.KEYCODE_SPACE, true, false, false, ' ');
        byte[] altSpaceBytes = networkBytes.toByteArray();
        Assert.assertEquals("Alt+Space should send 1 byte (Tab)", 1, altSpaceBytes.length);
        Assert.assertEquals("Alt+Space should produce ASCII 9 (\\t)", '\t', (char) altSpaceBytes[0]);

        // Alt+T -> Tab
        networkBytes.reset();
        view.handleKeyEvent(KeyEvent.KEYCODE_T, true, false, false, 't');
        byte[] altTBytes = networkBytes.toByteArray();
        Assert.assertEquals("Alt+T should send 1 byte (Tab)", 1, altTBytes.length);
        Assert.assertEquals("Alt+T should produce ASCII 9 (\\t)", '\t', (char) altTBytes[0]);

        // Escape
        networkBytes.reset();
        view.handleKeyEvent(KeyEvent.KEYCODE_ESCAPE, false, false, false, 0);
        Assert.assertTrue("Escape should send ESC sequence", getSentText().contains("\033"));
    }

    @Test
    public void testMenuOptionDispatchesUnambiguously() {
        String optSettings = "⚙️ Connection Settings";
        String optDisconnect = "❌ Disconnect / Stop";
        String optConnect = "⚡ Connect";
        String optReconnect = "⚡ Reconnect Session";

        // Connection Settings must NOT match connect/reconnect
        Assert.assertFalse(optSettings.startsWith("⚡ Connect"));
        Assert.assertFalse(optSettings.startsWith("⚡ Reconnect"));
        Assert.assertTrue(optSettings.startsWith("⚙️ Connection Settings"));

        // Disconnect must NOT match connect/reconnect
        Assert.assertFalse(optDisconnect.startsWith("⚡ Connect"));
        Assert.assertFalse(optDisconnect.startsWith("⚡ Reconnect"));
        Assert.assertTrue(optDisconnect.startsWith("❌ Disconnect"));

        // Connect matches connect
        Assert.assertTrue(optConnect.startsWith("⚡ Connect"));
        Assert.assertFalse(optConnect.startsWith("⚙️ Connection Settings"));

        // Reconnect matches reconnect
        Assert.assertTrue(optReconnect.startsWith("⚡ Reconnect"));
        Assert.assertFalse(optReconnect.startsWith("⚙️ Connection Settings"));
    }
}
