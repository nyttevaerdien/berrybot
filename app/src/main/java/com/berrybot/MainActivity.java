package com.berrybot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.berrybot.hardware.HapticHelper;
import com.berrybot.hardware.LedNotificationManager;
import com.berrybot.ssh.KeyStorage;
import com.berrybot.ssh.SSHConnectionManager;
import com.berrybot.terminal.ClaudeTheme;
import com.berrybot.terminal.TerminalBridge;
import com.berrybot.terminal.TerminalView;
import com.berrybot.update.UpdateManager;
import com.berrybot.util.ClipboardHelper;

public class MainActivity extends Activity implements SSHConnectionManager.Listener, TerminalView.ModifierChangeListener {
    private static final String TAG = "BerryBot.MainActivity";

    public static final String[] SLASH_COMMANDS = new String[] {
        "/plan",
        "/boost",
        "/teamwork",
        "/goal",
        "/learn",
        "/compact",
        "/browser",
        "/grill-me"
    };

    public static final String[] SLASH_COMMAND_LABELS = new String[] {
        "/plan  — Step-by-step planning before execution",
        "/boost — Deep thinking & rigorous verification",
        "/teamwork — Multi-agent autonomous collaboration",
        "/goal  — Extra thorough long-running task",
        "/learn — Persist guidelines and corrections",
        "/compact — Compact & summarize context window",
        "/browser — Web browsing, search & interaction",
        "/grill-me — Interactive alignment interview"
    };

    private KeyStorage keyStorage;
    private SSHConnectionManager connectionManager;
    private TerminalBridge terminalBridge;
    private LedNotificationManager ledNotificationManager;

    private TextView tvStatusDot;
    private TextView tvStatusText;
    private TerminalView terminalView;
    private String lastStatusMessage = "Initializing…";

    private Button btnCtrl;
    private Button btnAlt;
    private Button btnFontSizeMinus;
    private Button btnFontSizePlus;
    private Button btnSlash;

    private static final int REQUEST_CODE_KEY_SETUP = 1001;

    // Power & Screen Management
    private static final long SCREEN_ON_STREAMING_TIMEOUT_MS = 30000; // 30s idle screen dimming
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable screenDimRunnable = new Runnable() {
        @Override
        public void run() {
            setScreenAwake(false);
        }
    };

    private void setScreenAwake(boolean awake) {
        if (isFinishing()) return;
        try {
            if (awake) {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
        } catch (Throwable t) {
            android.util.Log.w(TAG, "Failed to update FLAG_KEEP_SCREEN_ON", t);
        }
    }

    private void refreshScreenAwake() {
        setScreenAwake(true);
        mainHandler.removeCallbacks(screenDimRunnable);
        mainHandler.postDelayed(screenDimRunnable, SCREEN_ON_STREAMING_TIMEOUT_MS);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Crash handler to capture Dalvik exceptions on BlackBerry 10
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                java.io.StringWriter sw = new java.io.StringWriter();
                ex.printStackTrace(new java.io.PrintWriter(sw));
                String stack = sw.toString();
                android.util.Log.e("BerryBot.Crash", "CRASH in " + thread.getName() + ": " + stack);
                getSharedPreferences("berrybot_crash", MODE_PRIVATE)
                    .edit()
                    .putString("crash_log", "Thread: " + thread.getName() + "\n\n" + stack)
                    .commit();
            } catch (Throwable ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(10);
        });

        setContentView(R.layout.activity_main);

        // Display previous crash details if any occurred
        String prevCrash = getSharedPreferences("berrybot_crash", MODE_PRIVATE).getString("crash_log", null);
        if (prevCrash != null) {
            getSharedPreferences("berrybot_crash", MODE_PRIVATE).edit().remove("crash_log").commit();
            new AlertDialog.Builder(this)
                .setTitle("Previous Crash Detected")
                .setMessage(prevCrash)
                .setPositiveButton("OK", null)
                .show();
        }

        keyStorage = new KeyStorage(this);
        connectionManager = new SSHConnectionManager(this, keyStorage);
        connectionManager.setListener(this);

        terminalBridge = new TerminalBridge(this);
        terminalBridge.setConnectionManager(connectionManager);

        // Hardware UX: Haptic tactile feedback & RGB Notification LED managers
        HapticHelper.getInstance(this);
        ledNotificationManager = LedNotificationManager.getInstance(this);

        initViews();
        setupKeyBar();

        // Connect automatically on startup only if key has been settled AND host/user are configured
        android.content.SharedPreferences prefs = getSharedPreferences("berrybot_prefs", MODE_PRIVATE);
        boolean keySettled = prefs.getBoolean("key_setup_settled", false);
        String currentHost = prefs.getString("host", "").trim();
        String currentUser = prefs.getString("user", "").trim();
        if (!keySettled || currentHost.isEmpty() || currentUser.isEmpty()) {
            onConnectionStateChanged(SSHConnectionManager.ConnectionState.DISCONNECTED, "Setup: Configure server & SSH key before connecting");
            android.content.Intent intent = new android.content.Intent(this, KeyManagementActivity.class);
            intent.putExtra("is_first_run", true);
            startActivityForResult(intent, REQUEST_CODE_KEY_SETUP);
        } else {
            connectionManager.connect();
        }

        // Silent background OTA update check
        UpdateManager.getInstance().checkForUpdates(this, false);
    }

    private void initViews() {
        tvStatusDot = (TextView) findViewById(R.id.tvStatusDot);
        tvStatusText = (TextView) findViewById(R.id.tvStatusText);
        terminalView = (TerminalView) findViewById(R.id.terminalView);

        terminalView.setBridge(terminalBridge);
        terminalView.setModifierChangeListener(this);
        terminalView.setOnTerminalLongClickListener(this::showCopyDialog);
        terminalView.setPasteListener(this::pasteFromClipboard);
        terminalView.setPromptSubmitListener(() -> {
            if (ledNotificationManager != null) {
                ledNotificationManager.onPromptSubmitted();
            }
        });

        View btnConnection = findViewById(R.id.btnConnection);
        View.OnClickListener menuClickListener = v -> {
            android.content.SharedPreferences p = getSharedPreferences("berrybot_prefs", MODE_PRIVATE);
            String h = p.getString("host", "").trim();
            String u = p.getString("user", "").trim();
            boolean settled = p.getBoolean("key_setup_settled", false);
            if (!settled || h.isEmpty() || u.isEmpty()) {
                Intent intent = new Intent(this, KeyManagementActivity.class);
                intent.putExtra("is_first_run", true);
                startActivityForResult(intent, REQUEST_CODE_KEY_SETUP);
            } else {
                showUnifiedMenu();
            }
        };
        if (btnConnection != null) {
            btnConnection.setOnClickListener(menuClickListener);
            btnConnection.setOnLongClickListener(v -> {
                showServerConfigDialog();
                return true;
            });
        }
        if (tvStatusText != null) {
            tvStatusText.setOnClickListener(menuClickListener);
        }
        if (tvStatusDot != null) {
            tvStatusDot.setOnClickListener(menuClickListener);
        }

        btnSlash = (Button) findViewById(R.id.btnSlash);
        if (btnSlash != null) {
            btnSlash.setOnClickListener(v -> showSlashCommandPicker(null));
            btnSlash.setOnLongClickListener(v -> {
                showSendTextDialog("/");
                return true;
            });
        }

        Button btnCopy = (Button) findViewById(R.id.btnCopy);
        Button btnPaste = (Button) findViewById(R.id.btnPaste);
        btnFontSizeMinus = (Button) findViewById(R.id.btnFontSizeMinus);
        btnFontSizePlus = (Button) findViewById(R.id.btnFontSizePlus);

        if (btnCopy != null) {
            btnCopy.setOnClickListener(v -> showCopyDialog());
        }
        if (btnPaste != null) {
            btnPaste.setOnClickListener(v -> pasteFromClipboard());
            btnPaste.setOnLongClickListener(v -> {
                showSendTextDialog(getClipboardText());
                return true;
            });
        }

        btnFontSizeMinus.setOnClickListener(v -> {
            terminalBridge.decreaseFontSize();
            terminalView.requestFocus();
        });

        btnFontSizePlus.setOnClickListener(v -> {
            terminalBridge.increaseFontSize();
            terminalView.requestFocus();
        });
    }

    private void showSlashCommandPicker(final EditText targetEditText) {
        new AlertDialog.Builder(this)
            .setTitle("Antigravity Slash Commands")
            .setItems(SLASH_COMMAND_LABELS, (dialog, which) -> {
                if (which >= 0 && which < SLASH_COMMANDS.length) {
                    String cmd = SLASH_COMMANDS[which] + " ";
                    if (targetEditText != null) {
                        int start = Math.max(0, targetEditText.getSelectionStart());
                        int end = Math.max(0, targetEditText.getSelectionEnd());
                        targetEditText.getText().replace(Math.min(start, end), Math.max(start, end), cmd, 0, cmd.length());
                        targetEditText.setSelection(Math.min(start, end) + cmd.length());
                    } else {
                        if (terminalBridge != null) {
                            terminalBridge.sendString(cmd);
                        }
                        if (terminalView != null) {
                            terminalView.requestFocus();
                        }
                        Toast.makeText(this, "Inserted " + SLASH_COMMANDS[which], Toast.LENGTH_SHORT).show();
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showUnifiedMenu() {
        String serverInfo = connectionManager.getUser() + "@" + connectionManager.getHost() + ":" + connectionManager.getPort();
        boolean isConnected = connectionManager.isConnected();
        boolean hapticOn = HapticHelper.getInstance(this).isHapticEnabled();

        String[] options = new String[] {
            isConnected ? "⚡ Reconnect Session" : "⚡ Connect",
            "⚙️ Server Connection Settings",
            "🔑 SSH Keys Management",
            "🔄 Check for Updates (OTA)",
            "🧹 Clear Prompt Line (Ctrl+U / Alt+DEL)",
            "🛑 Cancel / Break (Ctrl+C / Alt+C)",
            "📳 Haptic Feedback: " + (hapticOn ? "ON" : "OFF"),
            "⚡ Antigravity Slash Commands (/)...",
            "❓ FAQ & Shortcuts Guide",
            "ℹ️ Connection Status Details",
            isConnected ? "❌ Disconnect" : "🔌 Edit Host"
        };

        new AlertDialog.Builder(this)
            .setTitle("BerryBot Menu • " + (lastStatusMessage != null ? lastStatusMessage : "Menu"))
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    android.content.SharedPreferences prefs = getSharedPreferences("berrybot_prefs", MODE_PRIVATE);
                    String h = prefs.getString("host", "").trim();
                    String u = prefs.getString("user", "").trim();
                    if (!prefs.getBoolean("key_setup_settled", false) || h.isEmpty() || u.isEmpty()) {
                        Intent intent = new Intent(this, KeyManagementActivity.class);
                        intent.putExtra("is_first_run", true);
                        startActivityForResult(intent, REQUEST_CODE_KEY_SETUP);
                    } else {
                        Toast.makeText(this, "Reconnecting...", Toast.LENGTH_SHORT).show();
                        connectionManager.connect();
                        if (terminalView != null) terminalView.requestFocus();
                    }
                } else if (which == 1) {
                    showServerConfigDialog();
                } else if (which == 2) {
                    Intent intent = new Intent(this, KeyManagementActivity.class);
                    startActivityForResult(intent, REQUEST_CODE_KEY_SETUP);
                } else if (which == 3) {
                    UpdateManager.getInstance().checkForUpdates(this, true);
                } else if (which == 4) {
                    if (terminalBridge != null) {
                        terminalBridge.sendString("\025");
                    }
                    HapticHelper.tick(this);
                    Toast.makeText(this, "Cleared line", Toast.LENGTH_SHORT).show();
                    if (terminalView != null) terminalView.requestFocus();
                } else if (which == 5) {
                    if (terminalBridge != null) {
                        terminalBridge.sendString("\003");
                    }
                    HapticHelper.tick(this);
                    Toast.makeText(this, "Sent Cancel (Ctrl+C)", Toast.LENGTH_SHORT).show();
                    if (terminalView != null) terminalView.requestFocus();
                } else if (which == 6) {
                    boolean nowEnabled = !HapticHelper.getInstance(this).isHapticEnabled();
                    HapticHelper.getInstance(this).setHapticEnabled(nowEnabled);
                    if (nowEnabled) {
                        HapticHelper.tick(this);
                        Toast.makeText(this, "Haptic Feedback: ON", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Haptic Feedback: OFF", Toast.LENGTH_SHORT).show();
                    }
                    if (terminalView != null) terminalView.requestFocus();
                } else if (which == 7) {
                    showSlashCommandPicker(null);
                } else if (which == 8) {
                    showFaqDialog();
                } else if (which == 9) {
                    showStatusDetailDialog();
                } else if (which == 10) {
                    if (connectionManager.isConnected()) {
                        connectionManager.disconnect();
                        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
                    } else {
                        showServerConfigDialog();
                    }
                    if (terminalView != null) terminalView.requestFocus();
                }
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void showStatusDetailDialog() {
        new AlertDialog.Builder(this)
            .setTitle("Connection Status")
            .setMessage((lastStatusMessage != null ? lastStatusMessage : "No status") + 
                        "\n\nServer: " + connectionManager.getUser() + "@" + connectionManager.getHost() + ":" + connectionManager.getPort())
            .setPositiveButton("Edit Server", (dialog, which) -> showServerConfigDialog())
            .setNeutralButton("Reconnect", (dialog, which) -> {
                connectionManager.connect();
                terminalView.requestFocus();
            })
            .setNegativeButton("Close", null)
            .show();
    }

    private void showServerConfigDialog() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 16, 32, 16);

        final EditText etHost = new EditText(this);
        etHost.setHint("Server Host / IP");
        etHost.setText(connectionManager.getHost());
        layout.addView(etHost);

        final EditText etPort = new EditText(this);
        etPort.setHint("Port (e.g. 2222)");
        etPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etPort.setText(String.valueOf(connectionManager.getPort()));
        layout.addView(etPort);

        final EditText etUser = new EditText(this);
        etUser.setHint("Username");
        etUser.setText(connectionManager.getUser());
        layout.addView(etUser);

        new AlertDialog.Builder(this)
            .setTitle("Server Connection")
            .setView(layout)
            .setPositiveButton("Save & Connect", (dialog, which) -> {
                String newHost = etHost.getText().toString().trim();
                String portStr = etPort.getText().toString().trim();
                String newUser = etUser.getText().toString().trim();
                int newPort = 22;
                try {
                    newPort = Integer.parseInt(portStr);
                } catch (NumberFormatException ignored) {}
                if (!newHost.isEmpty()) {
                    connectionManager.updateConnectionParams(newHost, newPort, newUser);
                    connectionManager.connect();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void setupKeyBar() {
        Button btnEsc = (Button) findViewById(R.id.btnEsc);
        Button btnTab = (Button) findViewById(R.id.btnTab);
        btnCtrl = (Button) findViewById(R.id.btnCtrl);
        btnAlt = (Button) findViewById(R.id.btnAlt);
        Button btnUp = (Button) findViewById(R.id.btnUp);
        Button btnDown = (Button) findViewById(R.id.btnDown);
        Button btnLeft = (Button) findViewById(R.id.btnLeft);
        Button btnRight = (Button) findViewById(R.id.btnRight);
        Button btnPgUp = (Button) findViewById(R.id.btnPgUp);
        Button btnPgDn = (Button) findViewById(R.id.btnPgDn);

        btnEsc.setOnClickListener(v -> {
            terminalView.sendEsc();
            terminalView.requestFocus();
        });

        btnTab.setOnClickListener(v -> {
            terminalView.sendTab();
            terminalView.requestFocus();
        });

        btnCtrl.setOnClickListener(v -> {
            terminalView.toggleCtrl();
            terminalView.requestFocus();
        });

        btnAlt.setOnClickListener(v -> {
            terminalView.toggleAlt();
            terminalView.requestFocus();
        });

        btnUp.setOnClickListener(v -> {
            terminalView.sendArrowUp();
            terminalView.requestFocus();
        });

        btnDown.setOnClickListener(v -> {
            terminalView.sendArrowDown();
            terminalView.requestFocus();
        });

        btnLeft.setOnClickListener(v -> {
            terminalView.sendArrowLeft();
            terminalView.requestFocus();
        });

        btnRight.setOnClickListener(v -> {
            terminalView.sendArrowRight();
            terminalView.requestFocus();
        });

        btnPgUp.setOnClickListener(v -> {
            terminalView.sendPageUp();
            terminalView.requestFocus();
        });

        btnPgDn.setOnClickListener(v -> {
            terminalView.sendPageDown();
            terminalView.requestFocus();
        });
    }

    private EditText createClaudeMonospaceEditText(String text, boolean isSelectable, float textSizeSp) {
        final EditText et = new EditText(this);
        if (text != null && !text.isEmpty()) {
            et.setText(text);
        }
        et.setTypeface(com.berrybot.util.FontHelper.getMonospaceTypeface(this));
        et.setTextSize(textSizeSp);
        et.setTextColor(getResources().getColor(R.color.claude_cream));
        et.setBackgroundColor(getResources().getColor(R.color.claude_bg));
        et.setPadding(24, 18, 24, 18);
        et.setTextIsSelectable(isSelectable);
        return et;
    }

    private String getClipboardText() {
        return ClipboardHelper.getClipboardText(this);
    }

    private void pasteFromClipboard() {
        try {
            String raw = getClipboardText();
            if (raw.isEmpty()) {
                Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
                return;
            }
            HapticHelper.tick(this);
            String normalized = ClipboardHelper.normalizeLineEndings(raw);
            if (terminalBridge != null) {
                terminalBridge.sendString(normalized);
                if (terminalView != null) {
                    terminalView.requestFocus();
                }
                Toast.makeText(this, "Pasted to terminal (" + raw.length() + " chars)", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            android.util.Log.e(TAG, "Failed to paste from clipboard", t);
            Toast.makeText(this, "Paste failed: " + t.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void showSendTextDialog(String initialText) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(24, 16, 24, 16);

        Button btnSlashHelper = new Button(this);
        btnSlashHelper.setText("⚡ Insert Antigravity Slash Command (/)...");
        btnSlashHelper.setTextSize(11f);
        btnSlashHelper.setTextColor(getResources().getColor(R.color.claude_status_yellow));
        btnSlashHelper.setBackgroundResource(R.drawable.key_bg_selector);
        btnSlashHelper.setPadding(12, 8, 12, 8);
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        btnParams.bottomMargin = 8;
        container.addView(btnSlashHelper, btnParams);

        final EditText et = createClaudeMonospaceEditText(initialText, true, 13f);
        et.setHint("Type or paste command/prompt here...");
        if (initialText != null && !initialText.isEmpty()) {
            et.setSelection(initialText.length());
        }

        btnSlashHelper.setOnClickListener(v -> showSlashCommandPicker(et));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(et);
        LinearLayout.LayoutParams svParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        container.addView(sv, svParams);

        new AlertDialog.Builder(this)
            .setTitle("Compose / Send to Terminal")
            .setView(container)
            .setPositiveButton("Send + Enter", (dialog, which) -> {
                String str = et.getText().toString();
                if (!str.isEmpty() && terminalBridge != null) {
                    HapticHelper.tick(this);
                    if (ledNotificationManager != null) {
                        ledNotificationManager.onPromptSubmitted();
                    }
                    terminalBridge.sendString(ClipboardHelper.normalizeLineEndings(str) + "\r");
                    if (terminalView != null) {
                        terminalView.requestFocus();
                    }
                }
            })
            .setNeutralButton("Send (no Enter)", (dialog, which) -> {
                String str = et.getText().toString();
                if (!str.isEmpty() && terminalBridge != null) {
                    terminalBridge.sendString(ClipboardHelper.normalizeLineEndings(str));
                    if (terminalView != null) {
                        terminalView.requestFocus();
                    }
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showCopyDialog() {
        final String screenText = terminalBridge != null ? terminalBridge.getVisibleScreenText() : "";
        final String fullText = terminalBridge != null ? terminalBridge.getFullBufferText() : "";
        final String clipText = getClipboardText();

        String pasteLabel = clipText.isEmpty()
            ? "Paste from Clipboard (empty)"
            : "Paste: \"" + (clipText.length() > 20 ? clipText.substring(0, 20) + "…" : clipText) + "\"";

        String[] options = new String[] {
            pasteLabel,
            "Compose / Post Text Dialog...",
            "⚡ Antigravity Slash Commands (/)...",
            "Copy Screen Text",
            "View & Select Text...",
            "Copy Entire Scrollback Buffer",
            "FAQ & Shortcuts Guide..."
        };

        new AlertDialog.Builder(this)
            .setTitle("Terminal & Clipboard Actions")
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    pasteFromClipboard();
                } else if (which == 1) {
                    showSendTextDialog(clipText);
                } else if (which == 2) {
                    showSlashCommandPicker(null);
                } else if (which == 3) {
                    ClipboardHelper.copyToClipboard(this, "BerryBot Screen", screenText);
                    Toast.makeText(this, "Screen text copied to clipboard!", Toast.LENGTH_SHORT).show();
                } else if (which == 4) {
                    showSelectTextDialog(screenText.isEmpty() ? fullText : screenText);
                } else if (which == 5) {
                    ClipboardHelper.copyToClipboard(this, "BerryBot Buffer", fullText);
                    Toast.makeText(this, "Full buffer copied to clipboard!", Toast.LENGTH_SHORT).show();
                } else if (which == 6) {
                    showFaqDialog();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showFaqDialog() {
        String faqText = 
            "★ BERRYBOT & ANTIGRAVITY HARDWARE UX GUIDE ★\n\n" +
            "1. PHYSICAL KEYBOARD CHORDS (BlackBerry Classic)\n" +
            "• Alt + Backspace = Ctrl + U (kill / clear entire prompt line!)\n" +
            "• Shift + Backspace = Forward Delete (\033[3~, delete char ahead)\n" +
            "• Alt + C = Ctrl + C (\003, abort / cancel prompt or generation)\n" +
            "• Alt + X or Alt + E = Escape (\033, cancel prompt in agy)\n" +
            "• Alt + L = Ctrl + L (\014, clear and redraw terminal screen)\n" +
            "• Alt + D = Ctrl + D (\004, EOF / exit session)\n" +
            "• Alt + B = Ctrl + B (\002, Tmux prefix)\n" +
            "• Alt + M or Enter = Submit prompt (\r)\n\n" +
            "2. OPTICAL TRACKPAD & HARDWARE NAVIGATION\n" +
            "• Roll / swipe trackpad UP/DOWN: Smoothly scrolls terminal view!\n" +
            "• Click trackpad: Sends Enter (\r) with haptic tick.\n" +
            "• Volume Up / Down side keys scroll terminal history directly!\n" +
            "• Shift + Space = Page Up.\n\n" +
            "3. OTA SELF-UPDATER (OVER-THE-AIR)\n" +
            "• Automatically polls for new releases in background.\n" +
            "• Tap Unified Menu -> 'Check for Updates' anytime.\n" +
            "• Prompts to download & launches native BlackBerry 10 installer.\n\n" +
            "4. TACTILE HAPTIC FEEDBACK\n" +
            "• Subtle 20ms tactile tick on submitting prompts (Enter / Alt+M).\n" +
            "• Haptic tick on pasting clipboard (Ctrl+V / PASTE).\n" +
            "• Haptic tick on clearing line or interrupting (Alt+DEL / Alt+C).\n" +
            "• Toggle anytime in Unified Menu ('Haptic Feedback: ON/OFF').\n\n" +
            "5. MULTICOLOR RGB NOTIFICATION LED\n" +
            "• When BerryBot is in the background or screen is off:\n" +
            "  - Pulsing Cyan: Antigravity is streaming active response.\n" +
            "  - Solid Green: Response finished & ready for input!\n" +
            "  - Pulsing Red: SSH disconnected or connection error.\n\n" +
            "6. ANTIGRAVITY SLASH COMMANDS QUICK PICKER\n" +
            "• Tap '/' button in top status bar (or inside Compose dialog).\n" +
            "• 1-tap insertion: /plan, /boost, /teamwork, /goal,\n" +
            "  /learn, /compact, /browser, /grill-me.\n\n" +
            "7. HOW TO COPY & PASTE\n" +
            "• Tap COPY in top bar (or long-press the terminal).\n" +
            "• Tap PASTE in top bar (or press Ctrl+V).\n" +
            "• Long-press PASTE to open Composer with / slash commands.";

        EditText tv = createClaudeMonospaceEditText(faqText, true, 12f);
        tv.setFocusable(false);
        tv.setLineSpacing(4f, 1f);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);

        new AlertDialog.Builder(this)
            .setTitle("BerryBot Hardware & UX Guide")
            .setView(sv)
            .setPositiveButton("Got It", null)
            .show();
    }

    private void showSelectTextDialog(String text) {
        final EditText et = createClaudeMonospaceEditText(text, true, 12f);

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(et);

        new AlertDialog.Builder(this)
            .setTitle("Select & Copy Text")
            .setView(sv)
            .setPositiveButton("Copy All", (dialog, which) -> {
                ClipboardHelper.copyToClipboard(this, "BerryBot Text", et.getText().toString());
                Toast.makeText(this, "Copied to clipboard!", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Close", null)
            .show();
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        refreshScreenAwake();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ledNotificationManager != null) {
            ledNotificationManager.onActivityResumed();
        }
        if (terminalView != null) {
            terminalView.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (ledNotificationManager != null) {
            ledNotificationManager.onActivityPaused();
        }
        mainHandler.removeCallbacks(screenDimRunnable);
        setScreenAwake(false);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_KEY_SETUP) {
            android.content.SharedPreferences prefs = getSharedPreferences("berrybot_prefs", MODE_PRIVATE);
            String h = prefs.getString("host", "").trim();
            int p = prefs.getInt("port", 22);
            String u = prefs.getString("user", "").trim();
            boolean keySettled = prefs.getBoolean("key_setup_settled", false);

            if (resultCode == RESULT_OK && !h.isEmpty() && !u.isEmpty()) {
                if (connectionManager != null) {
                    connectionManager.updateConnectionParams(h, p, u);
                    connectionManager.connect();
                }
            } else {
                if (!keySettled || h.isEmpty() || u.isEmpty()) {
                    onConnectionStateChanged(SSHConnectionManager.ConnectionState.DISCONNECTED, "Setup required: Tap here to configure server & key.");
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (ledNotificationManager != null) {
            ledNotificationManager.cleanup();
            ledNotificationManager = null;
        }
        mainHandler.removeCallbacks(screenDimRunnable);
        setScreenAwake(false);
        if (connectionManager != null) {
            connectionManager.destroy();
            connectionManager = null;
        }
        if (terminalBridge != null) {
            terminalBridge.destroy();
            terminalBridge = null;
        }
        if (terminalView != null) {
            terminalView.setBridge(null);
            terminalView.setModifierChangeListener(null);
            terminalView.setPasteListener(null);
            terminalView.setPromptSubmitListener(null);
            terminalView.setOnTerminalLongClickListener(null);
        }
    }

    // SSHConnectionManager.Listener
    @Override
    public void onConnectionStateChanged(SSHConnectionManager.ConnectionState state, String message) {
        try {
            lastStatusMessage = message;
            if (ledNotificationManager != null) {
                switch (state) {
                    case CONNECTED:
                        ledNotificationManager.onConnected();
                        break;
                    case AUTH_FAILED:
                    case ERROR:
                        ledNotificationManager.onError(message);
                        break;
                    case DISCONNECTED:
                        ledNotificationManager.onDisconnected();
                        break;
                    default:
                        break;
                }
            }

            if (tvStatusText != null) {
                switch (state) {
                    case CONNECTED:
                        tvStatusText.setText("Online [main]");
                        break;
                    case CONNECTING:
                        if (message != null && message.startsWith("Reconnecting")) {
                            tvStatusText.setText(message);
                        } else {
                            tvStatusText.setText("Connecting…");
                        }
                        break;
                    case AUTH_FAILED:
                        tvStatusText.setText("Auth Failed");
                        break;
                    case ERROR:
                        tvStatusText.setText(message != null ? message : "Error");
                        break;
                    case DISCONNECTED:
                    default:
                        if (message != null && !message.isEmpty()) {
                            tvStatusText.setText(message);
                        } else {
                            tvStatusText.setText("Disconnected");
                        }
                        break;
                }
            }

            if (tvStatusDot != null) {
                switch (state) {
                    case CONNECTED:
                        tvStatusDot.setTextColor(getResources().getColor(R.color.claude_status_green));
                        break;
                    case CONNECTING:
                        tvStatusDot.setTextColor(getResources().getColor(R.color.claude_status_yellow));
                        break;
                    case AUTH_FAILED:
                    case ERROR:
                    case DISCONNECTED:
                    default:
                        tvStatusDot.setTextColor(getResources().getColor(R.color.claude_status_red));
                        break;
                }
            }

            if (state == SSHConnectionManager.ConnectionState.DISCONNECTED ||
                state == SSHConnectionManager.ConnectionState.ERROR ||
                state == SSHConnectionManager.ConnectionState.AUTH_FAILED) {
                mainHandler.removeCallbacks(screenDimRunnable);
                setScreenAwake(false);
            }
        } catch (Throwable t) {
            android.util.Log.e(TAG, "Error in onConnectionStateChanged", t);
        }
    }

    @Override
    public void onDataReceived(byte[] data, int length) {
        try {
            if (terminalBridge != null) {
                terminalBridge.feed(data, length);
            }
            if (ledNotificationManager != null) {
                ledNotificationManager.onDataReceived(length);
            }
            // Active streaming output: keep screen awake, but schedule dimming when output pauses
            refreshScreenAwake();
        } catch (Throwable t) {
            android.util.Log.e(TAG, "Error in onDataReceived", t);
        }
    }

    // TerminalView.ModifierChangeListener
    @Override
    public void onModifierChanged(boolean ctrlActive, boolean ctrlLocked, boolean altActive, boolean altLocked) {
        if (btnCtrl != null) {
            btnCtrl.setSelected(ctrlActive);
            btnCtrl.setText(ctrlLocked ? "CTRL*" : "CTRL");
        }
        if (btnAlt != null) {
            btnAlt.setSelected(altActive);
            btnAlt.setText(altLocked ? "ALT*" : "ALT");
        }
    }
}
