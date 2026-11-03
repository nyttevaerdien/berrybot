package com.berrybot;

import com.berrybot.terminal.TerminalBridge;
import com.berrybot.util.ClipboardHelper;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.Charset;

import de.mud.terminal.VDUBuffer;
import de.mud.terminal.vt320;

public class TerminalEmulatorTest {

    private vt320 buffer;
    private StringBuilder sentOutput;

    @Before
    public void setUp() {
        sentOutput = new StringBuilder();
        buffer = new vt320() {
            @Override
            public void write(byte[] b) {
                sentOutput.append(new String(b, Charset.forName("UTF-8")));
            }

            @Override
            public void write(int b) {
                sentOutput.append((char) b);
            }

            @Override
            public void sendTelnetCommand(byte cmd) {}

            @Override
            public void setWindowSize(int c, int r) {}

            @Override
            public void debug(String s) {}
        };
        buffer.setBufferSize(100);
        buffer.setScreenSize(80, 24, true);
    }

    @Test
    public void testBasicTextOutput() {
        buffer.putString("Hello, BerryBot!");
        Assert.assertEquals('H', buffer.getChar(0, 0));
        Assert.assertEquals('e', buffer.getChar(1, 0));
        Assert.assertEquals('l', buffer.getChar(2, 0));
        Assert.assertEquals('l', buffer.getChar(3, 0));
        Assert.assertEquals('o', buffer.getChar(4, 0));
        Assert.assertEquals(',', buffer.getChar(5, 0));
        Assert.assertEquals(' ', buffer.getChar(6, 0));
        Assert.assertEquals('B', buffer.getChar(7, 0));
    }

    @Test
    public void testCursorMovementAnsi() {
        // Move to row 5, col 10 (1-indexed in ANSI: ESC [ 5 ; 10 H)
        buffer.putString("\033[5;10H");
        buffer.putString("Positioned");
        Assert.assertEquals('P', buffer.getChar(9, 4));
        Assert.assertEquals('o', buffer.getChar(10, 4));
    }

    @Test
    public void testCursorMovementRelativeAndClamping() {
        // Start at row 10, col 20 (ANSI 1-indexed: 11;21)
        buffer.putString("\033[11;21H");
        Assert.assertEquals(20, buffer.getCursorColumn());
        Assert.assertEquals(10, buffer.getCursorRow());

        // CUU: Move cursor up 3 rows -> row 7
        buffer.putString("\033[3A");
        Assert.assertEquals(7, buffer.getCursorRow());
        Assert.assertEquals(20, buffer.getCursorColumn());

        // CUB: Move cursor left 5 cols -> col 15
        buffer.putString("\033[5D");
        Assert.assertEquals(7, buffer.getCursorRow());
        Assert.assertEquals(15, buffer.getCursorColumn());

        // CUD: Move cursor down 4 rows -> row 11
        buffer.putString("\033[4B");
        Assert.assertEquals(11, buffer.getCursorRow());
        Assert.assertEquals(15, buffer.getCursorColumn());

        // CUF: Move cursor right 6 cols -> col 21
        buffer.putString("\033[6C");
        Assert.assertEquals(11, buffer.getCursorRow());
        Assert.assertEquals(21, buffer.getCursorColumn());

        // Clamping: Try moving 999 up and 999 left
        buffer.putString("\033[999A\033[999D");
        Assert.assertEquals(0, buffer.getCursorRow());
        Assert.assertEquals(0, buffer.getCursorColumn());
    }

    @Test
    public void testLineInsertionAndVerticalScrollingWithoutNullPointers() {
        // Fill terminal screen
        for (int r = 0; r < 24; r++) {
            buffer.putString(0, r, "Line " + r);
        }

        // Scroll up 100 times - must not throw NullPointerException or ArrayIndexOutOfBoundsException
        for (int i = 0; i < 100; i++) {
            buffer.insertLine(23, 1, VDUBuffer.SCROLL_UP);
        }

        // Test with zero scrollback (maxBufSize == height)
        buffer.setBufferSize(24);
        Assert.assertEquals(24, buffer.getMaxBufferSize());
        for (int i = 0; i < 50; i++) {
            buffer.putString(0, 23, "NoScrollback " + i);
            buffer.insertLine(23, 1, VDUBuffer.SCROLL_UP);
        }

        // Verify bottom line is clear space after scroll
        Assert.assertEquals(' ', buffer.getChar(0, 23));
    }

    @Test
    public void testScrollingWithinMargins() {
        // Set scroll margins between row 5 and row 15 (1-based: ESC [ 6 ; 16 r)
        buffer.putString("\033[6;16r");
        Assert.assertEquals(5, buffer.getTopMargin());
        Assert.assertEquals(15, buffer.getBottomMargin());

        // Put text above, inside, and below margins
        buffer.putString(0, 2, "AboveMargin");
        buffer.putString(0, 10, "InsideMargin");
        buffer.putString(0, 20, "BelowMargin");

        // Scroll inside margin forward with ESC [ S
        buffer.putString("\033[10;1H\033[S");

        // Verify text above and below margin was untouched
        Assert.assertEquals('A', buffer.getChar(0, 2));
        Assert.assertEquals('B', buffer.getChar(0, 20));

        // Reset margins
        buffer.putString("\033[r");
        Assert.assertEquals(0, buffer.getTopMargin());
        Assert.assertEquals(23, buffer.getBottomMargin());
    }

    @Test
    public void testDeleteLineAndAreaWithoutNullPointer() {
        buffer.putString(0, 0, "Line to delete");
        buffer.deleteLine(0);
        Assert.assertEquals(' ', buffer.getChar(0, 0));

        buffer.putString(0, 1, "Area to clear completely");
        buffer.deleteArea(0, 1, 80, 1, 0L);
        Assert.assertEquals(' ', buffer.getChar(0, 1));
    }

    @Test
    public void testColorsAndAttributes() {
        // Red bold text: ESC [ 1 ; 31 m
        buffer.putString("\033[1;31mCoral\033[0m");
        long attr = buffer.getAttributes(0, 0);
        Assert.assertTrue("Attribute should have BOLD set", (attr & VDUBuffer.BOLD) != 0);
        Assert.assertTrue("Attribute should have COLOR_FG set", (attr & VDUBuffer.COLOR_FG) != 0);

        // Reset attributes: ESC [ 0 m
        buffer.putString("Plain");
        long plainAttr = buffer.getAttributes(5, 0);
        Assert.assertEquals("Plain text should have NORMAL attributes", VDUBuffer.NORMAL, plainAttr & VDUBuffer.BOLD);
    }

    @Test
    public void testUnderlineInvertLowAttributes() {
        // Underline (4), Invert (7), Low/Dim (2)
        buffer.putString("\033[4;7;2mStyle\033[0m");
        long attr = buffer.getAttributes(0, 0);
        Assert.assertTrue("Underline should be set", (attr & VDUBuffer.UNDERLINE) != 0);
        Assert.assertTrue("Invert should be set", (attr & VDUBuffer.INVERT) != 0);
        Assert.assertTrue("Low/Dim should be set", (attr & VDUBuffer.LOW) != 0);
    }

    @Test
    public void testXterm256Colors() {
        // Set foreground to xterm color 208 (coral orange): ESC [ 38 ; 5 ; 208 m
        buffer.putString("\033[38;5;208mOrange\033[0m");
        long attr = buffer.getAttributes(0, 0);
        int fgIndex = (int) ((attr & VDUBuffer.COLOR_FG) >> VDUBuffer.COLOR_FG_SHIFT) - 1;
        Assert.assertEquals("FG color index should match 208", 208, fgIndex);

        // Set background to xterm color 45: ESC [ 48 ; 5 ; 45 m
        buffer.putString("\033[48;5;45mCyanBg\033[0m");
        long bgAttr = buffer.getAttributes(6, 0);
        int bgIndex = (int) ((bgAttr & VDUBuffer.COLOR_BG) >> VDUBuffer.COLOR_BG_SHIFT) - 1;
        Assert.assertEquals("BG color index should match 45", 45, bgIndex);
    }

    @Test
    public void testTrueColor24BitAttributes() {
        // 24-bit TrueColor FG (RGB 255, 128, 64) and BG (RGB 10, 20, 30)
        buffer.putString("\033[38;2;255;128;64;48;2;10;20;30mTrueColor\033[0m");
        long attr = buffer.getAttributes(0, 0);
        Assert.assertTrue("FG color should be set", (attr & VDUBuffer.COLOR_FG) != 0);
        Assert.assertTrue("BG color should be set", (attr & VDUBuffer.COLOR_BG) != 0);
    }

    @Test
    public void testHighIntensityAnsiColors() {
        // High-intensity FG: 91 (Bright Red), High-intensity BG: 104 (Bright Blue)
        buffer.putString("\033[91;104mBright\033[0m");
        long attr = buffer.getAttributes(0, 0);
        Assert.assertTrue("High intensity FG should be set", (attr & VDUBuffer.COLOR_FG) != 0);
        Assert.assertTrue("High intensity BG should be set", (attr & VDUBuffer.COLOR_BG) != 0);
    }

    @Test
    public void testBackspaceHandling() {
        // Test backspace moves cursor back and allows character overwrite
        buffer.putString("ABCD\bE");
        Assert.assertEquals('A', buffer.getChar(0, 0));
        Assert.assertEquals('B', buffer.getChar(1, 0));
        Assert.assertEquals('C', buffer.getChar(2, 0));
        Assert.assertEquals('E', buffer.getChar(3, 0)); // 'D' overwritten by 'E'
        Assert.assertEquals(4, buffer.getCursorColumn());

        // Destructive backspace sequence "\b \b"
        buffer.putString("\b \b");
        Assert.assertEquals(' ', buffer.getChar(3, 0));
        Assert.assertEquals(3, buffer.getCursorColumn());

        // Backspace at left margin does not underflow below col 0
        buffer.setCursorPosition(0, 0);
        buffer.putString("\b\b\b");
        Assert.assertEquals(0, buffer.getCursorColumn());
    }

    @Test
    public void testCarriageReturnAndLineFeed() {
        // Carriage return '\r' resets column to 0 without advancing row
        buffer.setCursorPosition(0, 0);
        buffer.putString("Hello\rWorld");
        Assert.assertEquals('W', buffer.getChar(0, 0));
        Assert.assertEquals('o', buffer.getChar(1, 0));
        Assert.assertEquals('r', buffer.getChar(2, 0));
        Assert.assertEquals('l', buffer.getChar(3, 0));
        Assert.assertEquals('d', buffer.getChar(4, 0));
        Assert.assertEquals(0, buffer.getCursorRow());

        // Line feed '\n' advances row
        buffer.putString("\nNextLine");
        Assert.assertEquals(1, buffer.getCursorRow());
        Assert.assertEquals('N', buffer.getChar(5, 1));

        // Combined "\r\n"
        buffer.putString("\r\nRowTwo");
        Assert.assertEquals(2, buffer.getCursorRow());
        Assert.assertEquals('R', buffer.getChar(0, 2));
    }

    @Test
    public void testAscii127StreamHandling() {
        // ASCII 127 in stream must be handled safely without throwing or corrupting text
        buffer.setCursorPosition(0, 0);
        buffer.putString("AB" + (char) 127 + "CD");
        Assert.assertEquals('A', buffer.getChar(0, 0));
        Assert.assertEquals('B', buffer.getChar(1, 0));
    }

    @Test
    public void testUnicodeCyrillicCharacters() {
        String cyrillic = "Привет, мир! Тест терминала BerryBot";
        buffer.putString(cyrillic);
        Assert.assertEquals('П', buffer.getChar(0, 0));
        Assert.assertEquals('р', buffer.getChar(1, 0));
        Assert.assertEquals('и', buffer.getChar(2, 0));
        Assert.assertEquals('в', buffer.getChar(3, 0));
        Assert.assertEquals('е', buffer.getChar(4, 0));
        Assert.assertEquals('т', buffer.getChar(5, 0));
        Assert.assertEquals(',', buffer.getChar(6, 0));
        Assert.assertEquals(' ', buffer.getChar(7, 0));
        Assert.assertEquals('м', buffer.getChar(8, 0));
        Assert.assertEquals('и', buffer.getChar(9, 0));
        Assert.assertEquals('р', buffer.getChar(10, 0));
    }

    @Test
    public void testUnicodeBoxDrawingCharacters() {
        // Standard ANSI box drawing
        String topRow = "┌──┬──┐";
        String midRow = "│  │  │";
        String botRow = "└──┴──┘";

        buffer.putString(0, 0, topRow);
        buffer.putString(0, 1, midRow);
        buffer.putString(0, 2, botRow);

        Assert.assertEquals('┌', buffer.getChar(0, 0));
        Assert.assertEquals('─', buffer.getChar(1, 0));
        Assert.assertEquals('┬', buffer.getChar(3, 0));
        Assert.assertEquals('┐', buffer.getChar(6, 0));

        Assert.assertEquals('│', buffer.getChar(0, 1));
        Assert.assertEquals(' ', buffer.getChar(1, 1));
        Assert.assertEquals('│', buffer.getChar(3, 1));
        Assert.assertEquals('│', buffer.getChar(6, 1));

        Assert.assertEquals('└', buffer.getChar(0, 2));
        Assert.assertEquals('─', buffer.getChar(1, 2));
        Assert.assertEquals('┴', buffer.getChar(3, 2));
        Assert.assertEquals('┘', buffer.getChar(6, 2));
    }

    @Test
    public void testUnicodeCharacters() {
        // Claude Code symbols: ● ⎿ ❯ ✻ ⏵
        String claudeSymbols = "● ⎿ ❯ ✻ ⏵";
        buffer.putString(claudeSymbols);
        Assert.assertEquals('●', buffer.getChar(0, 0));
        Assert.assertEquals(' ', buffer.getChar(1, 0));
        Assert.assertEquals('⎿', buffer.getChar(2, 0));
        Assert.assertEquals(' ', buffer.getChar(3, 0));
        Assert.assertEquals('❯', buffer.getChar(4, 0));
        Assert.assertEquals(' ', buffer.getChar(5, 0));
        Assert.assertEquals('✻', buffer.getChar(6, 0));
        Assert.assertEquals(' ', buffer.getChar(7, 0));
        Assert.assertEquals('⏵', buffer.getChar(8, 0));
    }

    @Test
    public void testUnicodeEmojiBmpAndSurrogatePairs() {
        // BMP Emojis (single 16-bit code point)
        String bmpEmojis = "★ ⚡ ☕ ✓ ⚠️ ✅ ❌";
        buffer.putString(0, 0, bmpEmojis);
        Assert.assertEquals('★', buffer.getChar(0, 0));
        Assert.assertEquals('⚡', buffer.getChar(2, 0));
        Assert.assertEquals('☕', buffer.getChar(4, 0));

        // Supplementary plane emojis (surrogate pairs)
        String rocket = "🚀"; // U+1F680 -> \uD83D \uDE80
        buffer.putString(0, 1, rocket);
        Assert.assertEquals('\uD83D', buffer.getChar(0, 1));
        Assert.assertEquals('\uDE80', buffer.getChar(1, 1));

        String fire = "🔥"; // U+1F525 -> \uD83D \uDD25
        buffer.putString(0, 2, fire);
        Assert.assertEquals('\uD83D', buffer.getChar(0, 2));
        Assert.assertEquals('\uDD25', buffer.getChar(1, 2));
    }

    @Test
    public void testStreamingUtf8DecoderSplitAcrossChunks() {
        // Test TerminalBridge streaming UTF-8 decoding where multi-byte bytes arrive in separate chunks
        TerminalBridge bridge = new TerminalBridge(null);

        // 1. Cyrillic 'П' (0xD0 0x9F) fed 1 byte at a time
        byte[] p1 = new byte[]{(byte) 0xD0};
        byte[] p2 = new byte[]{(byte) 0x9F};
        bridge.feed(p1, 1);
        Assert.assertEquals("Incomplete byte should not decode yet", ' ', bridge.getBuffer().getChar(0, 0));

        bridge.feed(p2, 1);
        Assert.assertEquals("Completed 2-byte sequence should decode to 'П'", 'П', bridge.getBuffer().getChar(0, 0));

        // 2. Box drawing '┌' (0xE2 0x94 0x8C) fed 1 byte at a time
        byte[] b1 = new byte[]{(byte) 0xE2};
        byte[] b2 = new byte[]{(byte) 0x94};
        byte[] b3 = new byte[]{(byte) 0x8C};
        bridge.feed(b1, 1);
        bridge.feed(b2, 1);
        bridge.feed(b3, 1);
        Assert.assertEquals("Completed 3-byte sequence should decode to '┌'", '┌', bridge.getBuffer().getChar(1, 0));

        // 3. Emoji '🚀' (0xF0 0x9F 0x9A 0x80) fed 1 byte at a time
        byte[] e1 = new byte[]{(byte) 0xF0};
        byte[] e2 = new byte[]{(byte) 0x9F};
        byte[] e3 = new byte[]{(byte) 0x9A};
        byte[] e4 = new byte[]{(byte) 0x80};
        bridge.feed(e1, 1);
        bridge.feed(e2, 1);
        bridge.feed(e3, 1);
        bridge.feed(e4, 1);
        Assert.assertEquals("High surrogate should be at col 2", '\uD83D', bridge.getBuffer().getChar(2, 0));
        Assert.assertEquals("Low surrogate should be at col 3", '\uDE80', bridge.getBuffer().getChar(3, 0));
    }

    @Test
    public void testClearScreen() {
        buffer.putString("Line 1 to be cleared");
        Assert.assertEquals('L', buffer.getChar(0, 0));
        // ESC [ 2 J clears screen
        buffer.putString("\033[2J");
        Assert.assertEquals(' ', buffer.getChar(0, 0));
    }

    @Test
    public void testExcessiveSemicolonsNoIndexOutOfBounds() {
        StringBuilder excessiveCsi = new StringBuilder("\033[");
        for (int i = 0; i < 50; i++) {
            excessiveCsi.append(i).append(';');
        }
        excessiveCsi.append("0m");
        buffer.putString(excessiveCsi.toString());
        buffer.putString("Safe");
        Assert.assertEquals('S', buffer.getChar(0, 0));
    }

    @Test
    public void testMalformedOscAndDcsNoStringIndexOutOfBounds() {
        buffer.putString("\033]\\");
        buffer.putString("\033P\\");
        buffer.putString("\033[38;5mIncomplete");
        buffer.putString("\033[48;2;1;2mTruncatedRGB");
        Assert.assertEquals('I', buffer.getChar(0, 0));
    }

    @Test
    public void testClipboardNormalization() {
        String input = "line1\r\nline2\nline3\r";
        String normalized = ClipboardHelper.normalizeLineEndings(input);
        Assert.assertEquals("line1\rline2\rline3\r", normalized);
    }
}
