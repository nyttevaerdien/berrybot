/*
 * This file is part of "JTA - Telnet/SSH for the JAVA(tm) platform".
 *
 * (c) Matthias L. Jugel, Marcus Mei�ner 1996-2005. All Rights Reserved.
 *
 * Please visit http://javatelnet.org/ for updates and contact.
 *
 * --LICENSE NOTICE--
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 * --LICENSE NOTICE--
 *
 */

package de.mud.terminal;

import java.util.Arrays;

/**
 * Implementation of a Video Display Unit (VDU) buffer. This class contains
 * all methods to manipulate the buffer that stores characters and their
 * attributes as well as the regions displayed.
 *
 * @author Matthias L. Jugel, Marcus Meißner
 * @version $Id: VDUBuffer.java 503 2005-10-24 07:34:13Z marcus $
 */
public class VDUBuffer {

  /** The current version id tag */
  public final static String ID = "$Id: VDUBuffer.java 503 2005-10-24 07:34:13Z marcus $";

  /** Enable debug messages. */
  public final static int debug = 0;

  public int height, width;                          /* rows and columns */
  public boolean[] update;        /* contains the lines that need update */
  public char[][] charArray;                  /* contains the characters */
  public long[][] charAttributes;            /* contains character attrs */
  public int bufSize;
  public int maxBufSize;                                 /* buffer sizes */
  public int screenBase;                      /* the actual screen start */
  public int windowBase;                   /* where the start displaying */
  public int scrollMarker;               /* marks the last line inserted */

  private int topMargin;                               /* top scroll margin */
  private int bottomMargin;                         /* bottom scroll margin */

  // Alternate Screen Buffer state
  private boolean isAltBuffer = false;
  private char[][] normalCharArray;
  private long[][] normalCharAttributes;
  private int normalScreenBase, normalWindowBase, normalBufSize, normalMaxBufSize;
  private int normalCursorX, normalCursorY;
  private int cursorStyle = 0; // 0,1,2: block; 3,4: underline; 5,6: bar

  public int getCursorStyle() { return cursorStyle; }
  public void setCursorStyle(int style) { this.cursorStyle = style; }

  public synchronized void setAlternateBuffer(boolean enable) {
    if (isAltBuffer == enable) return;
    isAltBuffer = enable;
    if (enable) {
      normalCharArray = charArray;
      normalCharAttributes = charAttributes;
      normalScreenBase = screenBase;
      normalWindowBase = windowBase;
      normalBufSize = bufSize;
      normalMaxBufSize = maxBufSize;
      normalCursorX = cursorX;
      normalCursorY = cursorY;

      charArray = new char[height][];
      charAttributes = new long[height][];
      for (int i = 0; i < height; i++) {
        charArray[i] = new char[width];
        Arrays.fill(charArray[i], ' ');
        charAttributes[i] = new long[width];
      }
      bufSize = maxBufSize = height;
      screenBase = windowBase = cursorX = cursorY = 0;
    } else {
      if (normalCharArray != null) {
        charArray = normalCharArray;
        charAttributes = normalCharAttributes;
        screenBase = normalScreenBase;
        windowBase = normalWindowBase;
        bufSize = normalBufSize;
        maxBufSize = normalMaxBufSize;
        cursorX = normalCursorX;
        cursorY = normalCursorY;
      }
    }
    markLine(0, height);
    redraw();
  }

  // Zero-allocation row pools for line insertion / scrolling
  private char[][] rowCharPool = new char[16][];
  private long[][] rowAttrPool = new long[16][];

  private void ensurePoolCapacity(int cap) {
    if (rowCharPool == null || rowCharPool.length < cap) {
      int newCap = Math.max(cap, (rowCharPool != null ? rowCharPool.length * 2 : 16));
      rowCharPool = new char[newCap][];
      rowAttrPool = new long[newCap][];
    }
  }

  // cursor variables
  protected boolean showcursor = true;
  protected int cursorX, cursorY;

  /** Scroll up when inserting a line. */
  public final static boolean SCROLL_UP = false;
  /** Scroll down when inserting a line. */
  public final static boolean SCROLL_DOWN = true;

  /*  Attributes bit-field usage:
   *
   *  8421 8421 8421 8421 8421 8421 8421 8421  8421 8421 8421 8421 8421 8421 8421 8421
   *  |||| |||| |||| |||| |||| |||| |||| ||||  |||| |||| |||| |||| |||| |||| |||| |||`- Bold
   *  |||| |||| |||| |||| |||| |||| |||| ||||  |||| |||| |||| |||| |||| |||| |||| ||`-- Underline
   *  |||| |||| |||| |||| |||| |||| |||| ||||  |||| |||| |||| |||| |||| |||| |||| |`--- Invert
   *  |||| |||| |||| |||| |||| |||| |||| ||||  |||| |||| |||| |||| |||| |||| |||| `---- Low
   *  |||| |||| |||| |||| |||| |||| |||| ||||  |||| |||| |||| |||| |||| |||| |||`------ Invisible
   *  |||| |||| |||| |||| |||| |||| |||| ||||  |||| |||| |||| |||| |||| |||| ||`------- Fullwidth character
   *  |||| |||| |||| |||| |||| |||| |||| ||||  |`++-++++-++++-++++-++++-++++-++-------- Foreground Color
   *  |||| |||| `+++-++++-++++-++++-++++-++++--+--------------------------------------- Background Color
   *  `+++-++++------------------------------------------------------------------------ Reserved for future use
   */

  /** Make character normal. */
  public final static long NORMAL = 0x00;
  /** Make character bold. */
  public final static long BOLD = 0x01;
  /** Underline character. */
  public final static long UNDERLINE = 0x02;
  /** Invert character. */
  public final static long INVERT = 0x04;
  /** Lower intensity character. */
  public final static long LOW = 0x08;
  /** Invisible character. */
  public final static long INVISIBLE = 0x10;
  /** Unicode full-width character (CJK, et al.) */
  public final static long FULLWIDTH = 0x20;

  /** how much to left shift the foreground color */
  public final static int COLOR_FG_SHIFT = 6;
  /** how much to left shift the background color */
  public final static int COLOR_BG_SHIFT = 31;
  /** color mask */
  public final static long COLOR = 0xffffffffffffc0L;    /* 0000 0000 1111 1111 1111 1111 1111 1111  1111 1111 1111 1111 1111 1111 1100 0000 */
  /** foreground color mask */
  public final static long COLOR_FG = 0x7fffffc0L;       /* 0000 0000 0000 0000 0000 0000 0000 0000  0111 1111 1111 1111 1111 1111 1100 0000 */
  /** background color mask */
  public final static long COLOR_BG = 0xffffff80000000L; /* 0000 0000 1111 1111 1111 1111 1111 1111  1000 0000 0000 0000 0000 0000 0000 0000 */
  /** how much to left shift the red component */
  public final static int COLOR_RED_SHIFT = 16;
  /** how much to left shift the green component */
  public final static int COLOR_GREEN_SHIFT = 8;
  /** how much to left shift the blue component */
  public final static int COLOR_BLUE_SHIFT = 0;

  /**
   * Create a new video display buffer with the passed width and height in
   * characters.
   * @param width the length of the character lines
   * @param height the amount of lines on the screen
   */
  public VDUBuffer(int width, int height) {
    // set the display screen size
    setScreenSize(width, height, false);
  }

  /**
   * Create a standard video display buffer with 80 columns and 24 lines.
   */
  public VDUBuffer() {
    this(80, 24);
  }

  /**
   * Put a character on the screen with normal font and outline.
   * The character previously on that position will be overwritten.
   * You need to call redraw() to update the screen.
   * @param c x-coordinate (column)
   * @param l y-coordinate (line)
   * @param ch the character to show on the screen
   * @see #insertChar
   * @see #deleteChar
   * @see #redraw
   */
  public void putChar(int c, int l, char ch) {
    putChar(c, l, ch, NORMAL);
  }

  /**
   * Put a character on the screen with specific font and outline.
   * The character previously on that position will be overwritten.
   * You need to call redraw() to update the screen.
   * @param c x-coordinate (column)
   * @param l y-coordinate (line)
   * @param ch the character to show on the screen
   * @param attributes the character attributes
   * @see #BOLD
   * @see #UNDERLINE
   * @see #INVERT
   * @see #INVISIBLE
   * @see #NORMAL
   * @see #LOW
   * @see #insertChar
   * @see #deleteChar
   * @see #redraw
   */

  public synchronized void putChar(int c, int l, char ch, long attributes) {
    int line = screenBase + l;
    if (charArray == null || charAttributes == null || line < 0 || line >= charArray.length) return;
    char[] rowChars = charArray[line];
    long[] rowAttrs = charAttributes[line];
    if (rowChars == null || rowAttrs == null || c < 0 || c >= rowChars.length) return;
    rowChars[c] = ch;
    rowAttrs[c] = attributes;
    if (update != null && l >= 0 && l < height && (l + 1) < update.length) {
      update[l + 1] = true;
    }
  }

  /**
   * Get the character at the specified position.
   * @param c x-coordinate (column)
   * @param l y-coordinate (line)
   * @see #putChar
   */
  public synchronized char getChar(int c, int l) {
    int line = screenBase + l;
    if (charArray == null || line < 0 || line >= charArray.length) return ' ';
    char[] row = charArray[line];
    if (row == null || c < 0 || c >= row.length) return ' ';
    return row[c];
  }

  /**
   * Get the attributes for the specified position.
   * @param c x-coordinate (column)
   * @param l y-coordinate (line)
   * @see #putChar
   */
  public synchronized long getAttributes(int c, int l) {
    int line = screenBase + l;
    if (charAttributes == null || line < 0 || line >= charAttributes.length) return 0L;
    long[] row = charAttributes[line];
    if (row == null || c < 0 || c >= row.length) return 0L;
    return row[c];
  }

  /**
   * Insert a character at a specific position on the screen.
   * All character right to from this position will be moved one to the right.
   * You need to call redraw() to update the screen.
   * @param c x-coordinate (column)
   * @param l y-coordinate (line)
   * @param ch the character to insert
   * @param attributes the character attributes
   * @see #BOLD
   * @see #UNDERLINE
   * @see #INVERT
   * @see #INVISIBLE
   * @see #NORMAL
   * @see #LOW
   * @see #putChar
   * @see #deleteChar
   * @see #redraw
   */
  public synchronized void insertChar(int c, int l, char ch, long attributes) {
    int line = screenBase + l;
    if (charArray == null || charAttributes == null || line < 0 || line >= charArray.length) return;
    char[] rowChars = charArray[line];
    long[] rowAttrs = charAttributes[line];
    if (rowChars == null || rowAttrs == null || c < 0 || c >= width) return;
    int count = width - c - 1;
    if (count > 0 && c + 1 + count <= rowChars.length) {
      System.arraycopy(rowChars, c, rowChars, c + 1, count);
      System.arraycopy(rowAttrs, c, rowAttrs, c + 1, count);
    }
    putChar(c, l, ch, attributes);
  }

  /**
   * Delete a character at a given position on the screen.
   * All characters right to the position will be moved one to the left.
   * You need to call redraw() to update the screen.
   * @param c x-coordinate (column)
   * @param l y-coordinate (line)
   * @see #putChar
   * @see #insertChar
   * @see #redraw
   */
  public synchronized void deleteChar(int c, int l) {
    int line = screenBase + l;
    if (charArray == null || charAttributes == null || line < 0 || line >= charArray.length) return;
    char[] rowChars = charArray[line];
    long[] rowAttrs = charAttributes[line];
    if (rowChars == null || rowAttrs == null || c < 0 || c >= width) return;
    if (c < width - 1) {
      int count = width - c - 1;
      if (c + 1 + count <= rowChars.length) {
        System.arraycopy(rowChars, c + 1, rowChars, c, count);
        System.arraycopy(rowAttrs, c + 1, rowAttrs, c, count);
      }
    }
    putChar(width - 1, l, (char) 0);
  }

  /**
   * Put a String at a specific position. Any characters previously on that
   * position will be overwritten. You need to call redraw() for screen update.
   * @param c x-coordinate (column)
   * @param l y-coordinate (line)
   * @param s the string to be shown on the screen
   * @see #BOLD
   * @see #UNDERLINE
   * @see #INVERT
   * @see #INVISIBLE
   * @see #NORMAL
   * @see #LOW
   * @see #putChar
   * @see #insertLine
   * @see #deleteLine
   * @see #redraw
   */
  public void putString(int c, int l, String s) {
    putString(c, l, s, NORMAL);
  }

  /**
   * Put a String at a specific position giving all characters the same
   * attributes. Any characters previously on that position will be
   * overwritten. You need to call redraw() to update the screen.
   * @param c x-coordinate (column)
   * @param l y-coordinate (line)
   * @param s the string to be shown on the screen
   * @param attributes character attributes
   * @see #BOLD
   * @see #UNDERLINE
   * @see #INVERT
   * @see #INVISIBLE
   * @see #NORMAL
   * @see #LOW
   * @see #putChar
   * @see #insertLine
   * @see #deleteLine
   * @see #redraw
   */
  public void putString(int c, int l, String s, long attributes) {
    for (int i = 0; i < s.length() && c + i < width; i++)
      putChar(c + i, l, s.charAt(i), attributes);
  }

  /**
   * Insert a blank line at a specific position.
   * The current line and all previous lines are scrolled one line up. The
   * top line is lost. You need to call redraw() to update the screen.
   * @param l the y-coordinate to insert the line
   * @see #deleteLine
   * @see #redraw
   */
  public void insertLine(int l) {
    insertLine(l, 1, SCROLL_UP);
  }

  /**
   * Insert blank lines at a specific position.
   * You need to call redraw() to update the screen
   * @param l the y-coordinate to insert the line
   * @param n amount of lines to be inserted
   * @see #deleteLine
   * @see #redraw
   */
  public void insertLine(int l, int n) {
    insertLine(l, n, SCROLL_UP);
  }

  /**
   * Insert a blank line at a specific position. Scroll text according to
   * the argument.
   * You need to call redraw() to update the screen
   * @param l the y-coordinate to insert the line
   * @param scrollDown scroll down
   * @see #deleteLine
   * @see #SCROLL_UP
   * @see #SCROLL_DOWN
   * @see #redraw
   */
  public void insertLine(int l, boolean scrollDown) {
    insertLine(l, 1, scrollDown);
  }

  /**
   * Insert blank lines at a specific position.
   * The current line and all previous lines are scrolled one line up. The
   * top line is lost. You need to call redraw() to update the screen.
   * @param l the y-coordinate to insert the line
   * @param n number of lines to be inserted
   * @param scrollDown scroll down
   * @see #deleteLine
   * @see #SCROLL_UP
   * @see #SCROLL_DOWN
   * @see #redraw
   */
  public synchronized void insertLine(int l, int n, boolean scrollDown) {
    char cbuf[][] = null;
    long abuf[][] = null;
    int offset = 0;
    int oldBase = screenBase;

    int newScreenBase = screenBase;
    int newWindowBase = windowBase;
    int newBufSize = bufSize;

    if (l > bottomMargin) /* We do not scroll below bottom margin (below the scrolling region). */
      return;
    int top = (l < topMargin ?
            0 : (l > bottomMargin ?
            (bottomMargin + 1 < height ?
            bottomMargin + 1 : height - 1) : topMargin));
    int bottom = (l > bottomMargin ?
            height - 1 : (l < topMargin ?
            (topMargin > 0 ?
            topMargin - 1 : 0) : bottomMargin));

    if (scrollDown) {
      if (n > (bottom - top)) n = (bottom - top);
      int size = bottom - l - (n - 1);
      if (size < 0) size = 0;

      ensurePoolCapacity(n);
      for (int i = 0; i < n; i++) {
        int idx = oldBase + bottom - n + 1 + i;
        if (idx < charArray.length) {
          rowCharPool[i] = charArray[idx];
          rowAttrPool[i] = charAttributes[idx];
        } else {
          rowCharPool[i] = null;
          rowAttrPool[i] = null;
        }
      }

      if (size > 0) {
        System.arraycopy(charArray, oldBase + l, charArray, oldBase + l + n, size);
        System.arraycopy(charAttributes, oldBase + l, charAttributes, oldBase + l + n, size);
      }

      for (int i = 0; i < n; i++) {
        int targetIdx = oldBase + l + i;
        char[] rowC = rowCharPool[i];
        long[] rowA = rowAttrPool[i];
        if (rowC == null || rowC.length != width) {
          rowC = new char[width];
          rowA = new long[width];
        }
        Arrays.fill(rowC, ' ');
        Arrays.fill(rowA, 0L);
        charArray[targetIdx] = rowC;
        charAttributes[targetIdx] = rowA;
      }
      cbuf = charArray;
      abuf = charAttributes;
    } else {
      if (n > (bottom - top) + 1) n = (bottom - top) + 1;

      // Restricted margin scrolling (top > 0 or bottom < height - 1) must be in-place
      // and NEVER pollute the scrollback buffer or modify screenBase
      if (topMargin > 0 || bottomMargin < height - 1) {
        int start = oldBase + topMargin;
        int count = bottomMargin - topMargin;
        char[] recycledC = charArray[start];
        long[] recycledA = charAttributes[start];
        System.arraycopy(charArray, start + 1, charArray, start, count);
        System.arraycopy(charAttributes, start + 1, charAttributes, start, count);
        Arrays.fill(recycledC, ' ');
        Arrays.fill(recycledA, 0L);
        charArray[oldBase + bottomMargin] = recycledC;
        charAttributes[oldBase + bottomMargin] = recycledA;
        markLine(topMargin, bottomMargin - topMargin + 1);
        redraw();
        return;
      }

      if (bufSize < maxBufSize) {
        if (bufSize + n > maxBufSize) {
          offset = n - (maxBufSize - bufSize);
          scrollMarker += offset;
          newBufSize = maxBufSize;
          newScreenBase = maxBufSize - height;
          newWindowBase = screenBase;
        } else {
          scrollMarker += n;
          newScreenBase += n;
          newWindowBase += n;
          newBufSize += n;
        }
      } else {
        offset = n;
      }

      // Check if charArray needs expansion to hold newBufSize
      if (charArray.length < newBufSize) {
        cbuf = new char[newBufSize][];
        abuf = new long[newBufSize][];
        for (int i = 0; i < newBufSize; i++) {
          cbuf[i] = new char[width];
          Arrays.fill(cbuf[i], ' ');
          abuf[i] = new long[width];
        }
      } else {
        cbuf = charArray;
        abuf = charAttributes;
      }

      ensurePoolCapacity(Math.max(offset, n));
      for (int i = 0; i < offset; i++) {
        rowCharPool[i] = charArray[i];
        rowAttrPool[i] = charAttributes[i];
      }

      int marginDisplaced = 0;
      if (top > 0) {
        marginDisplaced = Math.min(n, l - top + 1);
        for (int i = 0; i < marginDisplaced; i++) {
          rowCharPool[offset + i] = charArray[oldBase + top + i];
          rowAttrPool[offset + i] = charAttributes[oldBase + top + i];
        }
      }

      // copy anything from the top of the buffer (+offset) to the new top up to oldBase
      if (oldBase > 0) {
        System.arraycopy(charArray, offset,
                         cbuf, 0,
                         oldBase - offset);
        System.arraycopy(charAttributes, offset,
                         abuf, 0,
                         oldBase - offset);
      }

      // copy anything from the top of the screen (screenBase) up to the topMargin to the new screen
      if (top > 0) {
        System.arraycopy(charArray, oldBase,
                         cbuf, newScreenBase,
                         top);
        System.arraycopy(charAttributes, oldBase,
                         abuf, newScreenBase,
                         top);
      }

      // copy anything from topMargin up to the amount of lines inserted to the gap
      if (oldBase - offset >= 0) {
        System.arraycopy(charArray, oldBase + top,
                         cbuf, oldBase - offset,
                         n);
        System.arraycopy(charAttributes, oldBase + top,
                         abuf, oldBase - offset,
                         n);
      }

      // copy anything from topMargin + n up to line l
      System.arraycopy(charArray, oldBase + top + n,
                       cbuf, newScreenBase + top,
                       l - top - (n - 1));
      System.arraycopy(charAttributes, oldBase + top + n,
                       abuf, newScreenBase + top,
                       l - top - (n - 1));

      // copy lines below l
      if (l < height - 1) {
        System.arraycopy(charArray, oldBase + l + 1,
                         cbuf, newScreenBase + l + 1,
                         (height - 1) - l);
        System.arraycopy(charAttributes, oldBase + l + 1,
                         abuf, newScreenBase + l + 1,
                         (height - 1) - l);
      }
    }

    scrollMarker -= n;

    if (!scrollDown) {
      int poolIdx = 0;
      int totalPool = offset + (top > 0 ? Math.min(n, l - top + 1) : 0);
      for (int i = 0; i < n; i++) {
        int targetLine = (newScreenBase + l) - i;
        if (targetLine >= 0 && targetLine < cbuf.length) {
          char[] rowC;
          long[] rowA;
          if (poolIdx < totalPool && rowCharPool[poolIdx] != null && rowCharPool[poolIdx].length == width) {
            rowC = rowCharPool[poolIdx];
            rowA = rowAttrPool[poolIdx];
            poolIdx++;
          } else if (cbuf[targetLine] != null && cbuf[targetLine].length == width) {
            rowC = cbuf[targetLine];
            rowA = abuf[targetLine];
          } else {
            rowC = new char[width];
            rowA = new long[width];
          }
          Arrays.fill(rowC, ' ');
          Arrays.fill(rowA, 0L);
          cbuf[targetLine] = rowC;
          abuf[targetLine] = rowA;
        }
      }
    }

    charArray = cbuf;
    charAttributes = abuf;
    screenBase = newScreenBase;
    windowBase = newWindowBase;
    bufSize = newBufSize;

    if (scrollDown)
      markLine(l, bottom - l + 1);
    else
      markLine(top, l - top + 1);

    if (display != null) {
      display.updateScrollBar();
    }
  }

  /**
   * Delete a line at a specific position. Subsequent lines will be scrolled
   * up to fill the space and a blank line is inserted at the end of the
   * screen.
   * @param l the y-coordinate to insert the line
   * @see #deleteLine
   */
  public synchronized void deleteLine(int l) {
    if (charArray == null || charAttributes == null) return;
    int bottom = (l > bottomMargin ? height - 1 :
            (l < topMargin ? topMargin : bottomMargin + 1));
    int numRows = bottom - l - 1;

    int srcLine = screenBase + l;
    if (srcLine < 0 || srcLine >= charArray.length) return;

    char[] discardedChars = charArray[srcLine];
    long[] discardedAttributes = charAttributes[srcLine];

    if (numRows > 0 && srcLine + 1 + numRows <= charArray.length) {
	    System.arraycopy(charArray, srcLine + 1,
	                     charArray, srcLine, numRows);
	    System.arraycopy(charAttributes, srcLine + 1,
	                     charAttributes, srcLine, numRows);
    }

    int newBottomRow = screenBase + bottom - 1;
    if (newBottomRow >= 0 && newBottomRow < charArray.length) {
      if (discardedChars == null || discardedChars.length != width) {
        discardedChars = new char[width];
      }
      if (discardedAttributes == null || discardedAttributes.length != width) {
        discardedAttributes = new long[width];
      }
      charArray[newBottomRow] = discardedChars;
      charAttributes[newBottomRow] = discardedAttributes;
      Arrays.fill(charArray[newBottomRow], ' ');
      Arrays.fill(charAttributes[newBottomRow], 0L);
    }

    markLine(l, bottom - l);
  }

  /**
   * Delete a rectangular portion of the screen.
   * You need to call redraw() to update the screen.
   * @param c x-coordinate (column)
   * @param l y-coordinate (row)
   * @param w with of the area in characters
   * @param h height of the area in characters
   * @param curAttr attribute to fill
   * @see #deleteChar
   * @see #deleteLine
   * @see #redraw
   */
  public synchronized void deleteArea(int c, int l, int w, int h, long curAttr) {
    if (charArray == null || charAttributes == null || w <= 0 || h <= 0) return;
    int startCol = Math.max(0, c);
    int endColumn = Math.min(width, c + w);
    if (startCol >= endColumn) return;

    for (int i = 0; i < h && l + i < height; i++) {
      int targetRow = screenBase + l + i;
      if (targetRow >= 0 && targetRow < charArray.length) {
        char[] rowChars = charArray[targetRow];
        long[] rowAttrs = charAttributes[targetRow];
        if (rowChars != null && rowAttrs != null) {
          int end = Math.min(endColumn, rowChars.length);
          if (startCol < end) {
            Arrays.fill(rowAttrs, startCol, end, curAttr);
            Arrays.fill(rowChars, startCol, end, ' ');
          }
        }
      }
    }
    markLine(l, h);
  }

  /**
   * Delete a rectangular portion of the screen.
   * You need to call redraw() to update the screen.
   * @param c x-coordinate (column)
   * @param l y-coordinate (row)
   * @param w with of the area in characters
   * @param h height of the area in characters
   * @see #deleteChar
   * @see #deleteLine
   * @see #redraw
   */
  public void deleteArea(int c, int l, int w, int h) {
    deleteArea(c, l, w, h, 0);
  }

  /**
   * Sets whether the cursor is visible or not.
   * @param doshow
   */
  public void showCursor(boolean doshow) {
    showcursor = doshow;
  }

  /**
   * Check whether the cursor is currently visible.
   * @return visibility
   */
  public boolean isCursorVisible() {
    return showcursor;
  }

  /**
   * Puts the cursor at the specified position.
   * @param c column
   * @param l line
   */
  public void setCursorPosition(int c, int l) {
    cursorX = c;
    cursorY = l;
  }

  /**
   * Get the current column of the cursor position.
   */
  public int getCursorColumn() {
    return cursorX;
  }

  /**
   * Get the current line of the cursor position.
   */
  public int getCursorRow() {
    return cursorY;
  }

  /**
   * Set the current window base. This allows to view the scrollback buffer.
   * @param line the line where the screen window starts
   * @see #setBufferSize
   * @see #getBufferSize
   */
  public void setWindowBase(int line) {
    if (line > screenBase)
      line = screenBase;
    else if (line < 0) line = 0;
    windowBase = line;
    update[0] = true;
    redraw();
  }

  /**
   * Get the current window base.
   * @see #setWindowBase
   */
  public int getWindowBase() {
    return windowBase;
  }

  /**
   * Set the scroll margins simultaneously.  If they're out of bounds, trim them.
   * @param l1 line that is the top
   * @param l2 line that is the bottom
   */
  public void setMargins(int l1, int l2) {
    if (l1 > l2)
      return;

    if (l1 < 0)
      l1 = 0;
    if (l2 >= height)
      l2 = height - 1;

    topMargin = l1;
    bottomMargin = l2;
  }

  /**
   * Set the top scroll margin for the screen. If the current bottom margin
   * is smaller it will become the top margin and the line will become the
   * bottom margin.
   * @param l line that is the margin
   */
  public void setTopMargin(int l) {
    if (l > bottomMargin) {
      topMargin = bottomMargin;
      bottomMargin = l;
    } else
      topMargin = l;
    if (topMargin < 0) topMargin = 0;
    if (bottomMargin >= height) bottomMargin = height - 1;
  }

  /**
   * Get the top scroll margin.
   */
  public int getTopMargin() {
    return topMargin;
  }

  /**
   * Set the bottom scroll margin for the screen. If the current top margin
   * is bigger it will become the bottom margin and the line will become the
   * top margin.
   * @param l line that is the margin
   */
  public void setBottomMargin(int l) {
    if (l < topMargin) {
      bottomMargin = topMargin;
      topMargin = l;
    } else
      bottomMargin = l;
    if (topMargin < 0) topMargin = 0;
    if (bottomMargin >= height) bottomMargin = height - 1;
  }

  /**
   * Get the bottom scroll margin.
   */
  public int getBottomMargin() {
    return bottomMargin;
  }

  /**
   * Set scrollback buffer size.
   * @param amount new size of the buffer
   */
  public synchronized void setBufferSize(int amount) {
    if (amount < height) amount = height;
    maxBufSize = amount;

    if (charArray == null || charArray.length < maxBufSize) {
      int allocRows = maxBufSize;
      int w = width > 0 ? width : 80;
      char cbuf[][] = new char[allocRows][];
      long abuf[][] = new long[allocRows][];
      for (int i = 0; i < allocRows; i++) {
        cbuf[i] = new char[w];
        Arrays.fill(cbuf[i], ' ');
        abuf[i] = new long[w];
      }

      int copyCount = charArray != null ? Math.min(charArray.length, allocRows) : 0;
      int copyStart = bufSize - copyCount < 0 ? 0 : bufSize - copyCount;
      if (charArray != null) {
        for (int i = 0; i < copyCount; i++) {
          if (charArray[copyStart + i] != null) {
            int rowLen = Math.min(charArray[copyStart + i].length, w);
            System.arraycopy(charArray[copyStart + i], 0, cbuf[i], 0, rowLen);
            System.arraycopy(charAttributes[copyStart + i], 0, abuf[i], 0, rowLen);
          }
        }
      }
      charArray = cbuf;
      charAttributes = abuf;
      bufSize = copyCount > 0 ? copyCount : height;
      screenBase = bufSize - height;
      if (screenBase < 0) screenBase = 0;
      windowBase = screenBase;
    }

    update[0] = true;
    redraw();
  }

  /**
   * Retrieve current scrollback buffer size.
   * @see #setBufferSize
   */
  public int getBufferSize() {
    return bufSize;
  }

  /**
   * Retrieve maximum buffer Size.
   * @see #getBufferSize
   */
  public int getMaxBufferSize() {
    return maxBufSize;
  }

  /**
   * Change the size of the screen. This will include adjustment of the
   * scrollback buffer.
   * @param w of the screen
   * @param h of the screen
   */
  public synchronized void setScreenSize(int w, int h, boolean broadcast) {
    char cbuf[][];
    long abuf[][];
    int maxSize = bufSize;
    int oldAbsR = screenBase + getCursorRow();

    if (w < 1 || h < 1) return;

    if (debug > 0)
      System.err.println("VDU: screen size [" + w + "," + h + "]");

    if (h > maxBufSize)
      maxBufSize = h;

    if (h > bufSize) {
      bufSize = h;
      screenBase = 0;
      windowBase = 0;
    }

    if (windowBase + h >= bufSize)
      windowBase = bufSize - h;

    if (screenBase + h >= bufSize)
      screenBase = bufSize - h;


    int allocRows = Math.max(maxBufSize, bufSize);
    cbuf = new char[allocRows][];
    abuf = new long[allocRows][];

    for (int i = 0; i < allocRows; i++) {
      cbuf[i] = new char[w];
      Arrays.fill(cbuf[i], ' ');
      abuf[i] = new long[w];
    }

    if (bufSize < maxSize)
      maxSize = bufSize;

    int rowLength;
    if (charArray != null && charAttributes != null) {
      for (int i = 0; i < maxSize && charArray[i] != null; i++) {
        rowLength = charArray[i].length;
        System.arraycopy(charArray[i], 0, cbuf[i], 0,
                         w < rowLength ? w : rowLength);
        System.arraycopy(charAttributes[i], 0, abuf[i], 0,
                         w < rowLength ? w : rowLength);
      }
    }

    int C = getCursorColumn();
    if (C < 0)
      C = 0;
    else if (C >= w)
      C = w - 1;

    int R = getCursorRow();
    // If the screen size has grown and now there are more rows on the screen,
    // slide the cursor down to the end of the text.
    if (R + screenBase <= oldAbsR)
      R = oldAbsR - screenBase;
    if (R < 0)
      R = 0;
    else if (R >= h)
      R = h - 1;

    setCursorPosition(C, R);

    charArray = cbuf;
    charAttributes = abuf;
    width = w;
    height = h;
    topMargin = 0;
    bottomMargin = h - 1;
    update = new boolean[h + 1];
    update[0] = true;
    /*  FIXME: ???
    if(resizeStrategy == RESIZE_FONT)
      setBounds(getBounds());
    */
  }

  /**
   * Get amount of rows on the screen.
   */
  public int getRows() {
    return height;
  }

  /**
   * Get amount of columns on the screen.
   */
  public int getColumns() {
    return width;
  }

  /**
   * Mark lines to be updated with redraw().
   * @param l starting line
   * @param n amount of lines to be updated
   * @see #redraw
   */
  public synchronized void markLine(int l, int n) {
    if (update == null) return;
    for (int i = 0; (i < n) && (l + i < height); i++) {
      int idx = l + i + 1;
      if (idx >= 0 && idx < update.length) {
        update[idx] = true;
      }
    }
  }

//  private static int checkBounds(int value, int lower, int upper) {
//    if (value < lower)
//      return lower;
//    else if (value > upper)
//      return upper;
//    else
//      return value;
//  }

  /** a generic display that should redraw on demand */
  protected VDUDisplay display;

  public void setDisplay(VDUDisplay display) {
    this.display = display;
  }

  /**
   * Trigger a redraw on the display.
   */
  protected void redraw() {
    if (display != null)
      display.redraw();
  }
}
