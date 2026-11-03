package com.berrybot.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Log;

import com.berrybot.ssh.SSHConnectionManager;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;

import de.mud.terminal.VDUBuffer;
import de.mud.terminal.vt320;

public class TerminalBridge {
    private static final String TAG = "BerryBot.TerminalBridge";
    private static final int BUFFER_SIZE = 16384;

    private final Context context;
    private vt320 buffer;
    private SSHConnectionManager connectionManager;
    private TerminalView terminalView;

    private Paint textPaint;
    private Paint bgPaint;
    private Paint cursorPaint;

    private Typeface typeface;
    private static final float DEFAULT_FONT_SIZE_SP = 10f;
    private float fontSize = 20f; // in px
    private int charWidth = 10;
    private int charHeight = 20;
    private int charAscent = 16;

    private int columns = 80;
    private int rows = 36;

    private final CharsetDecoder decoder;
    private final ByteBuffer byteBuffer;
    private final CharBuffer charBuffer;
    private char[] feedChars = new char[16384];
    private char[] lineCharBuf = new char[256];
    private final char[] cursorCharBuf = new char[2];

    private void ensureLineCharBuf(int cols) {
        if (lineCharBuf == null || lineCharBuf.length < cols) {
            lineCharBuf = new char[Math.max(cols, lineCharBuf != null ? lineCharBuf.length * 2 : 256)];
        }
    }

    private boolean cursorVisible = true;
    private boolean cursorBlinkState = true;

    public TerminalBridge(Context context) {
        this.context = context;

        decoder = Charset.forName("UTF-8").newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPLACE);
        decoder.onUnmappableCharacter(CodingErrorAction.REPLACE);

        byteBuffer = ByteBuffer.allocate(BUFFER_SIZE);
        charBuffer = CharBuffer.allocate(BUFFER_SIZE);

        initPaints();
        initBuffer();
    }

    private void initPaints() {
        try {
            if (context != null && context.getAssets() != null) {
                typeface = Typeface.createFromAsset(context.getAssets(), "fonts/DejaVuSansMono.ttf");
            } else {
                typeface = Typeface.MONOSPACE;
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not load DejaVuSansMono.ttf, falling back to MONOSPACE", e);
            typeface = Typeface.MONOSPACE;
        }

        float density = 2.0f;
        if (context != null && context.getResources() != null && context.getResources().getDisplayMetrics() != null) {
            density = context.getResources().getDisplayMetrics().scaledDensity;
            if (density <= 0) density = 2.0f;
        }
        fontSize = DEFAULT_FONT_SIZE_SP * density;

        textPaint = new Paint();
        textPaint.setTypeface(typeface);
        textPaint.setAntiAlias(true);
        textPaint.setTextSize(fontSize);

        bgPaint = new Paint();
        bgPaint.setStyle(Paint.Style.FILL);

        cursorPaint = new Paint();
        cursorPaint.setStyle(Paint.Style.FILL);
        cursorPaint.setColor(ClaudeTheme.CURSOR_COLOR);

        recomputeFontMetrics();
    }

    private void recomputeFontMetrics() {
        if (textPaint == null) return;
        textPaint.setTextSize(fontSize);
        float[] widths = new float[1];
        try {
            textPaint.getTextWidths("X", widths);
        } catch (Throwable ignored) {}
        charWidth = Math.max(10, (int) Math.ceil(widths[0]));
        if (widths[0] > 0) {
            textPaint.setTextScaleX(((float) charWidth) / widths[0]);
        }

        Paint.FontMetrics fm = null;
        try {
            fm = textPaint.getFontMetrics();
        } catch (Throwable ignored) {}

        if (fm != null) {
            charHeight = Math.max(1, (int) Math.ceil(fm.descent - fm.ascent));
            charAscent = (int) Math.ceil(-fm.ascent);
        } else {
            charHeight = 20;
            charAscent = 16;
        }
    }

    private void initBuffer() {
        buffer = new vt320() {
            @Override
            public void write(byte[] b) {
                if (connectionManager != null) {
                    connectionManager.sendData(b);
                }
            }

            @Override
            public void write(int b) {
                if (connectionManager != null) {
                    connectionManager.sendByte(b);
                }
            }

            @Override
            public void sendTelnetCommand(byte cmd) {}

            @Override
            public void setWindowSize(int c, int r) {
                columns = c;
                rows = r;
            }

            @Override
            public void debug(String s) {
                if (s != null) Log.d(TAG, s);
            }
        };

        buffer.setBufferSize(500); // 500 lines scrollback
        buffer.setScreenSize(columns, rows, true);
        buffer.setCursorPosition(0, 0);

        buffer.setDisplay(new de.mud.terminal.VDUDisplay() {
            @Override
            public void redraw() {
                if (terminalView != null) {
                    terminalView.requestCoalescedRedraw();
                }
            }

            @Override
            public void updateScrollBar() {
                if (terminalView != null) {
                    terminalView.requestCoalescedRedraw();
                }
            }

            @Override
            public void setVDUBuffer(de.mud.terminal.VDUBuffer b) {}

            @Override
            public de.mud.terminal.VDUBuffer getVDUBuffer() {
                return buffer;
            }

            @Override
            public void setColor(int index, int red, int green, int blue) {}

            @Override
            public void resetColors() {}
        });
    }

    public void setConnectionManager(SSHConnectionManager manager) {
        this.connectionManager = manager;
    }

    public void setTerminalView(TerminalView view) {
        this.terminalView = view;
    }

    public synchronized void destroy() {
        connectionManager = null;
        terminalView = null;
    }

    public synchronized void onDimensionsChanged(int viewWidth, int viewHeight) {
        if (charWidth <= 0 || charHeight <= 0) return;
        int newCols = Math.max(20, viewWidth / charWidth);
        int newRows = Math.max(10, viewHeight / charHeight);

        if (newCols != columns || newRows != rows) {
            columns = newCols;
            rows = newRows;
            buffer.setScreenSize(columns, rows, false);
            if (connectionManager != null) {
                connectionManager.setDimensions(columns, rows, viewWidth, viewHeight);
            }
        }
    }

    public synchronized void feed(byte[] data, int length) {
        if (data == null || length <= 0) return;

        try {
            int offset = 0;
            while (offset < length) {
                int rem = byteBuffer.remaining();
                if (rem <= 0) {
                    byteBuffer.clear();
                    rem = byteBuffer.remaining();
                }
                int chunk = Math.min(length - offset, rem);
                byteBuffer.put(data, offset, chunk);
                offset += chunk;

                byteBuffer.flip();
                decoder.decode(byteBuffer, charBuffer, false);
                byteBuffer.compact();

                charBuffer.flip();
                int charsDecoded = charBuffer.remaining();
                if (charsDecoded > 0) {
                    if (feedChars == null || feedChars.length < charsDecoded) {
                        feedChars = new char[Math.max(charsDecoded, feedChars != null ? feedChars.length * 2 : 16384)];
                    }
                    charBuffer.get(feedChars, 0, charsDecoded);
                    buffer.putString(feedChars, null, 0, charsDecoded);
                }
                charBuffer.clear();
            }

            if (terminalView != null) {
                terminalView.requestCoalescedRedraw();
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in feed()", t);
        }
    }

    public synchronized void draw(Canvas canvas, int viewWidth, int viewHeight) {
        try {
            // Fill canvas background
            canvas.drawColor(ClaudeTheme.BG_COLOR);

            if (buffer == null) return;

            int curCols = buffer.getColumns();
            int curRows = buffer.getRows();
            if (curCols <= 0 || curRows <= 0) return;

            ensureLineCharBuf(curCols);

            int absBase = buffer.screenBase;
            char[][] charArr = buffer.charArray;
            long[][] attrArr = buffer.charAttributes;
            if (charArr == null || attrArr == null) return;

            int lastFg = -1;
            boolean lastBold = false;
            boolean lastUnderline = false;

            for (int row = 0; row < curRows; row++) {
                int absRow = absBase + row;
                if (absRow < 0 || absRow >= charArr.length) continue;

                char[] rowChars = charArr[absRow];
                long[] rowAttrs = attrArr[absRow];
                if (rowChars == null || rowAttrs == null) continue;

                int rowCols = Math.min(curCols, Math.min(rowChars.length, rowAttrs.length));
                int y = row * charHeight;
                int textY = y + charAscent;

                // 1. Batch contiguous cell backgrounds with identical color into a single drawRect() call
                int col = 0;
                while (col < rowCols) {
                    long attr = rowAttrs[col];
                    int bgColor = getBackgroundColor(attr);
                    int runStart = col;
                    col++;
                    while (col < rowCols && getBackgroundColor(rowAttrs[col]) == bgColor) {
                        col++;
                    }

                    if (bgColor != ClaudeTheme.BG_COLOR) {
                        bgPaint.setColor(bgColor);
                        canvas.drawRect(runStart * charWidth, y, col * charWidth, y + charHeight, bgPaint);
                    }
                }

                // 2. Find last non-blank/visible column to avoid drawing trailing empty cells
                int lastVisibleCol = rowCols - 1;
                while (lastVisibleCol >= 0) {
                    char c = rowChars[lastVisibleCol];
                    long attr = rowAttrs[lastVisibleCol];
                    if (c > ' ' || (attr & VDUBuffer.UNDERLINE) != 0) {
                        break;
                    }
                    lastVisibleCol--;
                }
                if (lastVisibleCol < 0) {
                    continue; // Row is completely empty
                }

                // 3. Batch contiguous runs of characters with identical attributes into single drawText() calls
                col = 0;
                while (col <= lastVisibleCol) {
                    while (col <= lastVisibleCol && (rowChars[col] == 0 || rowChars[col] == ' ') && (rowAttrs[col] & VDUBuffer.UNDERLINE) == 0) {
                        col++;
                    }
                    if (col > lastVisibleCol) break;

                    int runStart = col;
                    long firstAttr = rowAttrs[col];
                    int runFg = getForegroundColor(firstAttr);
                    boolean runBold = (firstAttr & VDUBuffer.BOLD) != 0;
                    boolean runUnderline = (firstAttr & VDUBuffer.UNDERLINE) != 0;

                    while (col <= lastVisibleCol) {
                        long attr = rowAttrs[col];
                        if (getForegroundColor(attr) != runFg ||
                            ((attr & VDUBuffer.BOLD) != 0) != runBold ||
                            ((attr & VDUBuffer.UNDERLINE) != 0) != runUnderline) {
                            break;
                        }
                        char ch = rowChars[col];
                        if (ch == 0 || (attr & VDUBuffer.INVISIBLE) != 0) {
                            lineCharBuf[col] = ' ';
                        } else {
                            lineCharBuf[col] = ch;
                        }
                        col++;
                    }

                    int runCount = col - runStart;
                    if (runCount > 0) {
                        if (runFg != lastFg) {
                            textPaint.setColor(runFg);
                            lastFg = runFg;
                        }
                        if (runBold != lastBold) {
                            textPaint.setFakeBoldText(runBold);
                            lastBold = runBold;
                        }
                        if (runUnderline != lastUnderline) {
                            textPaint.setUnderlineText(runUnderline);
                            lastUnderline = runUnderline;
                        }

                        canvas.drawText(lineCharBuf, runStart, runCount, runStart * charWidth, textY, textPaint);
                    }
                }
            }

            // 4. Draw cursor
            if (cursorVisible && cursorBlinkState && buffer.isCursorVisible()) {
                int cursorCol = buffer.getCursorColumn();
                int cursorRow = buffer.getCursorRow();
                if (cursorCol >= 0 && cursorCol < curCols && cursorRow >= 0 && cursorRow < curRows) {
                    int cx = cursorCol * charWidth;
                    int cy = cursorRow * charHeight;
                    canvas.drawRect(cx, cy, cx + charWidth, cy + charHeight, cursorPaint);

                    // Draw char under cursor with inverted color
                    char c = buffer.getChar(cursorCol, cursorRow);
                    if (c != 0 && c != ' ') {
                        cursorCharBuf[0] = c;
                        textPaint.setColor(ClaudeTheme.BG_COLOR);
                        textPaint.setFakeBoldText(false);
                        textPaint.setUnderlineText(false);
                        canvas.drawText(cursorCharBuf, 0, 1, cx, cy + charAscent, textPaint);
                        lastFg = ClaudeTheme.BG_COLOR;
                        lastBold = false;
                        lastUnderline = false;
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error in draw()", t);
        }
    }

    private int getForegroundColor(long attr) {
        if ((attr & VDUBuffer.INVERT) != 0) {
            return extractBgColor(attr);
        }
        return extractFgColor(attr);
    }

    private int getBackgroundColor(long attr) {
        if ((attr & VDUBuffer.INVERT) != 0) {
            return extractFgColor(attr);
        }
        return extractBgColor(attr);
    }

    private int extractFgColor(long attr) {
        if ((attr & VDUBuffer.COLOR_FG) != 0) {
            int fg = (int) ((attr & VDUBuffer.COLOR_FG) >> VDUBuffer.COLOR_FG_SHIFT) - 1;
            if (fg >= 0 && fg < 256) {
                if (fg < 8 && (attr & VDUBuffer.BOLD) != 0) {
                    return ClaudeTheme.PALETTE[fg + 8];
                }
                return ClaudeTheme.PALETTE[fg];
            } else if (fg >= 256) {
                return 0xFF000000 | (fg - 256);
            }
        }
        return (attr & VDUBuffer.BOLD) != 0 ? ClaudeTheme.PALETTE[15] : ClaudeTheme.FG_COLOR;
    }

    private int extractBgColor(long attr) {
        if ((attr & VDUBuffer.COLOR_BG) != 0) {
            int bg = (int) ((attr & VDUBuffer.COLOR_BG) >> VDUBuffer.COLOR_BG_SHIFT) - 1;
            if (bg >= 0 && bg < 256) {
                return ClaudeTheme.PALETTE[bg];
            } else if (bg >= 256) {
                return 0xFF000000 | (bg - 256);
            }
        }
        return ClaudeTheme.BG_COLOR;
    }

    public synchronized void sendKey(int key, char c, int modifiers) {
        if (buffer != null) {
            buffer.keyPressed(key, c, modifiers);
        }
    }

    public synchronized void sendString(String s) {
        if (connectionManager != null && s != null) {
            connectionManager.sendData(s.getBytes(Charset.forName("UTF-8")));
        }
    }

    public synchronized void sendEscape() {
        if (buffer != null) {
            buffer.keyTyped(vt320.KEY_ESCAPE, ' ', 0);
        }
    }

    public synchronized String getVisibleScreenText() {
        if (buffer == null) return "";
        int cols = buffer.getColumns();
        int rows = buffer.getRows();
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            int end = cols;
            while (end > 0) {
                char ch = buffer.getChar(end - 1, r);
                if (ch != 0 && ch != ' ') break;
                end--;
            }
            for (int c = 0; c < end; c++) {
                char ch = buffer.getChar(c, r);
                sb.append(ch == 0 ? ' ' : ch);
            }
            sb.append('\n');
        }
        int len = sb.length();
        while (len > 0 && (sb.charAt(len - 1) == '\n' || sb.charAt(len - 1) == ' ' || sb.charAt(len - 1) == '\r')) {
            len--;
        }
        sb.setLength(len);
        return sb.toString();
    }

    public synchronized String getFullBufferText() {
        if (buffer == null) return "";
        StringBuilder sb = new StringBuilder();
        char[][] arr = buffer.charArray;
        if (arr != null) {
            for (int r = 0; r < arr.length; r++) {
                char[] row = arr[r];
                if (row == null) continue;
                int end = row.length;
                while (end > 0 && (row[end - 1] == 0 || row[end - 1] == ' ' || row[end - 1] == '\t')) {
                    end--;
                }
                if (end > 0) {
                    for (int c = 0; c < end; c++) {
                        char ch = row[c];
                        sb.append(ch == 0 ? ' ' : ch);
                    }
                }
                sb.append('\n');
            }
        }
        int len = sb.length();
        while (len > 0 && (sb.charAt(len - 1) == '\n' || sb.charAt(len - 1) == ' ' || sb.charAt(len - 1) == '\r')) {
            len--;
        }
        sb.setLength(len);
        return sb.toString();
    }

    public synchronized void sendBackspace() {
        if (connectionManager != null) {
            connectionManager.sendByte(0x7F);
        }
    }

    public synchronized void sendDelete() {
        sendString("\033[3~");
    }

    public synchronized void sendTab() {
        if (connectionManager != null) {
            connectionManager.sendByte(0x09);
        }
    }

    public synchronized void sendCtrlChar(char c) {
        char upper = Character.toUpperCase(c);
        if (upper >= '@' && upper <= '_') {
            int ctrlCode = upper - '@';
            if (connectionManager != null) {
                connectionManager.sendByte(ctrlCode);
            }
        }
    }

    public synchronized void setFontSize(float sp) {
        float density = 2.0f;
        if (context != null && context.getResources() != null && context.getResources().getDisplayMetrics() != null) {
            density = context.getResources().getDisplayMetrics().scaledDensity;
            if (density <= 0) density = 2.0f;
        }
        float px = sp * density;
        if (px >= 10 && px <= 48) {
            this.fontSize = px;
            recomputeFontMetrics();
            if (terminalView != null) {
                onDimensionsChanged(terminalView.getWidth(), terminalView.getHeight());
                terminalView.postInvalidate();
            }
        }
    }

    public float getFontSizeSp() {
        float density = 2.0f;
        if (context != null && context.getResources() != null && context.getResources().getDisplayMetrics() != null) {
            density = context.getResources().getDisplayMetrics().scaledDensity;
            if (density <= 0) density = 2.0f;
        }
        return fontSize / density;
    }

    public void increaseFontSize() {
        setFontSize(getFontSizeSp() + 1f);
    }

    public void decreaseFontSize() {
        setFontSize(Math.max(8f, getFontSizeSp() - 1f));
    }

    public vt320 getBuffer() {
        return buffer;
    }

    public int getCharHeight() { return charHeight; }
    public int getCharWidth() { return charWidth; }

    public int getColumns() { return columns; }
    public int getRows() { return rows; }
}
