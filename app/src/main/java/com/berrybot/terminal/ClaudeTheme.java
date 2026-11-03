package com.berrybot.terminal;

public class ClaudeTheme {
    public static final int BG_COLOR = 0xFF181816;       // Warm dark Claude background
    public static final int FG_COLOR = 0xFFF4ECE1;       // Light cream text
    public static final int CORAL = 0xFFD97757;          // Claude coral accent
    public static final int CORAL_BRIGHT = 0xFFFF8C68;   // Bright coral
    public static final int CURSOR_COLOR = 0xFFD97757;   // Coral cursor
    public static final int SELECTION_BG = 0x88D97757;   // Semi-transparent coral selection

    // 256-color palette
    public static final int[] PALETTE = new int[256];

    static {
        // Base 16 ANSI colors tuned for Claude Code aesthetic
        PALETTE[0]  = 0xFF181816; // 0: Black (Claude bg)
        PALETTE[1]  = 0xFFD97757; // 1: Red (Claude coral)
        PALETTE[2]  = 0xFF7EC77B; // 2: Green
        PALETTE[3]  = 0xFFE89D5E; // 3: Yellow/Amber
        PALETTE[4]  = 0xFF74A7E0; // 4: Blue
        PALETTE[5]  = 0xFFC678DD; // 5: Magenta
        PALETTE[6]  = 0xFF56B6C2; // 6: Cyan
        PALETTE[7]  = 0xFFF4ECE1; // 7: White (Claude cream)
        PALETTE[8]  = 0xFF5C5855; // 8: Bright Black (Grey)
        PALETTE[9]  = 0xFFFF8C68; // 9: Bright Coral
        PALETTE[10] = 0xFF8FE68C; // 10: Bright Green
        PALETTE[11] = 0xFFF3D18C; // 11: Bright Yellow
        PALETTE[12] = 0xFF8AC7FA; // 12: Bright Blue
        PALETTE[13] = 0xFFD88CEF; // 13: Bright Magenta
        PALETTE[14] = 0xFF6AD4E0; // 14: Bright Cyan
        PALETTE[15] = 0xFFFFFFFF; // 15: Bright White

        // 6x6x6 color cube (16-231)
        int[] cubeValues = {0x00, 0x5F, 0x87, 0xAF, 0xD7, 0xFF};
        for (int r = 0; r < 6; r++) {
            for (int g = 0; g < 6; g++) {
                for (int b = 0; b < 6; b++) {
                    int index = 16 + (r * 36) + (g * 6) + b;
                    PALETTE[index] = 0xFF000000 | (cubeValues[r] << 16) | (cubeValues[g] << 8) | cubeValues[b];
                }
            }
        }

        // Grayscale ramp (232-255)
        for (int i = 0; i < 24; i++) {
            int gray = 8 + (i * 10);
            PALETTE[232 + i] = 0xFF000000 | (gray << 16) | (gray << 8) | gray;
        }
    }
}
