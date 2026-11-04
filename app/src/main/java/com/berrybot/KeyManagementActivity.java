package com.berrybot;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.berrybot.ssh.KeyStorage;
import com.berrybot.util.ClipboardHelper;

public class KeyManagementActivity extends Activity {
    private KeyStorage keyStorage;
    private EditText etPublicKey;
    private EditText etPrivateKeyInput;
    private EditText etServerHost;
    private EditText etServerPort;
    private EditText etServerUser;
    private android.widget.TextView tvFingerprint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_key_management);

        keyStorage = KeyStorage.getInstance(this);

        android.graphics.Typeface monoFont = com.berrybot.util.FontHelper.getMonospaceTypeface(this);

        etPublicKey = (EditText) findViewById(R.id.etPublicKey);
        etPrivateKeyInput = (EditText) findViewById(R.id.etPrivateKeyInput);
        tvFingerprint = (android.widget.TextView) findViewById(R.id.tvFingerprint);

        etPublicKey.setTypeface(monoFont);
        etPrivateKeyInput.setTypeface(monoFont);
        if (tvFingerprint != null) {
            tvFingerprint.setTypeface(monoFont);
        }

        final float[] keyFontSize = new float[] { 13.0f };
        etPublicKey.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, keyFontSize[0]);
        etPrivateKeyInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, keyFontSize[0]);

        android.widget.TextView tvFontLegend = (android.widget.TextView) findViewById(R.id.tvFontLegend);
        if (tvFontLegend != null) {
            tvFontLegend.setTypeface(monoFont);
        }

        Button btnFontMinus = (Button) findViewById(R.id.btnFontMinus);
        Button btnFontPlus = (Button) findViewById(R.id.btnFontPlus);
        if (btnFontMinus != null) {
            btnFontMinus.setOnClickListener(v -> {
                if (keyFontSize[0] > 9.0f) {
                    keyFontSize[0] -= 1.0f;
                    etPublicKey.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, keyFontSize[0]);
                    etPrivateKeyInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, keyFontSize[0]);
                }
            });
        }
        if (btnFontPlus != null) {
            btnFontPlus.setOnClickListener(v -> {
                if (keyFontSize[0] < 22.0f) {
                    keyFontSize[0] += 1.0f;
                    etPublicKey.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, keyFontSize[0]);
                    etPrivateKeyInput.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, keyFontSize[0]);
                }
            });
        }

        Button btnCopyPubkey = (Button) findViewById(R.id.btnCopyPubkey);
        Button btnGenerateKey = (Button) findViewById(R.id.btnGenerateKey);
        Button btnPassphraseKey = (Button) findViewById(R.id.btnPassphraseKey);
        Button btnImportKey = (Button) findViewById(R.id.btnImportKey);
        Button btnClose = (Button) findViewById(R.id.btnClose);
        Button btnConnectNow = (Button) findViewById(R.id.btnConnectNow);

        etServerHost = (EditText) findViewById(R.id.etServerHost);
        etServerPort = (EditText) findViewById(R.id.etServerPort);
        etServerUser = (EditText) findViewById(R.id.etServerUser);

        android.content.SharedPreferences prefs = getSharedPreferences("berrybot_prefs", MODE_PRIVATE);
        String savedHost = prefs.getString("host", "");
        int savedPort = prefs.getInt("port", 2222);
        String savedUser = prefs.getString("user", "");

        if (etServerHost != null && !savedHost.isEmpty()) etServerHost.setText(savedHost);
        if (etServerPort != null) etServerPort.setText(String.valueOf(savedPort > 0 ? savedPort : 2222));
        if (etServerUser != null && !savedUser.isEmpty()) etServerUser.setText(savedUser);

        android.widget.TextView tvSetupNotice = (android.widget.TextView) findViewById(R.id.tvSetupNotice);
        boolean isFirstRun = getIntent().getBooleanExtra("is_first_run", false);
        if (tvSetupNotice != null && (isFirstRun || savedHost.isEmpty() || savedUser.isEmpty())) {
            tvSetupNotice.setVisibility(android.view.View.VISIBLE);
        }

        refreshPublicKey();

        btnCopyPubkey.setOnClickListener(v -> copyPublicKeyToClipboard());
        btnPassphraseKey.setOnClickListener(v -> showPassphraseDialog());

        if (btnConnectNow != null) {
            if (isFirstRun || savedHost.isEmpty()) {
                btnConnectNow.setText("Save & Connect to Server");
            } else {
                btnConnectNow.setText("Save & Reconnect");
            }
            btnConnectNow.setOnClickListener(v -> {
                String hostInput = etServerHost != null ? etServerHost.getText().toString().trim() : "";
                String userInput = etServerUser != null ? etServerUser.getText().toString().trim() : "";
                String portStr = etServerPort != null ? etServerPort.getText().toString().trim() : "2222";

                if (hostInput.isEmpty()) {
                    Toast.makeText(this, "Please enter your Server Host / IP", Toast.LENGTH_SHORT).show();
                    if (etServerHost != null) etServerHost.requestFocus();
                    return;
                }
                if (userInput.isEmpty()) {
                    Toast.makeText(this, "Please enter your SSH Username", Toast.LENGTH_SHORT).show();
                    if (etServerUser != null) etServerUser.requestFocus();
                    return;
                }

                int portInput = 2222;
                try {
                    portInput = Integer.parseInt(portStr);
                } catch (NumberFormatException ignored) {}

                getSharedPreferences("berrybot_prefs", MODE_PRIVATE)
                    .edit()
                    .putString("host", hostInput)
                    .putInt("port", portInput)
                    .putString("user", userInput)
                    .putBoolean("key_setup_settled", true)
                    .commit();

                setResult(RESULT_OK);
                finish();
            });
        }

        btnGenerateKey.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                .setTitle("Generate New Key")
                .setMessage("Generate a new Ed25519 keypair? This will replace your current key.")
                .setPositiveButton("Generate", (dialog, which) -> {
                    boolean success = keyStorage.generateNewEd25519Key("berrybot@blackberry");
                    if (success) {
                        refreshPublicKey();
                        Toast.makeText(this, R.string.key_generated_success, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
        });

        btnImportKey.setOnClickListener(v -> {
            String input = etPrivateKeyInput.getText().toString();
            if (input.trim().isEmpty()) {
                Toast.makeText(this, "Please paste private key text first", Toast.LENGTH_SHORT).show();
                return;
            }
            boolean success = keyStorage.importPrivateKey(input, null, "imported@blackberry");
            etPrivateKeyInput.setText(""); // Always clear sensitive private key from UI field
            if (success) {
                refreshPublicKey();
                Toast.makeText(this, R.string.key_import_success, Toast.LENGTH_SHORT).show();
            } else {
                String err = keyStorage.getLastImportError();
                String headerInfo = input.trim().startsWith("-----") ? "PEM format error" : "Unrecognized key format";
                new AlertDialog.Builder(this)
                    .setTitle("Import Failed")
                    .setMessage((err != null ? err : "Format not recognized") + "\n\n(" + headerInfo + ")")
                    .setPositiveButton("OK", null)
                    .show();
            }
        });

        btnClose.setOnClickListener(v -> {
            saveDraftServerParams();
            if (isFirstRun) {
                setResult(RESULT_CANCELED);
            }
            finish();
        });
    }

    private void saveDraftServerParams() {
        if (etServerHost == null || etServerUser == null || etServerPort == null) return;
        String h = etServerHost.getText().toString().trim();
        String u = etServerUser.getText().toString().trim();
        String pStr = etServerPort.getText().toString().trim();
        int p = 2222;
        try {
            p = Integer.parseInt(pStr);
        } catch (NumberFormatException ignored) {}

        getSharedPreferences("berrybot_prefs", MODE_PRIVATE)
            .edit()
            .putString("host", h)
            .putInt("port", p)
            .putString("user", u)
            .commit();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveDraftServerParams();
    }

    private void refreshPublicKey() {
        String pub = keyStorage.getOpenSSHPublicKey();
        etPublicKey.setText(pub);
        if (tvFingerprint != null) {
            tvFingerprint.setText("Active Key Fingerprint:\n" + keyStorage.getFingerprint());
        }
    }

    private void copyPublicKeyToClipboard() {
        String pub = etPublicKey.getText().toString();
        if (pub.isEmpty()) return;

        ClipboardHelper.copyToClipboard(this, "BerryBot Public Key", pub);
        Toast.makeText(this, "Public key copied to clipboard! Paste into server's ~/.ssh/authorized_keys, then tap Connect.", Toast.LENGTH_LONG).show();
    }

    private void showPassphraseDialog() {
        final EditText input = new EditText(this);
        input.setTypeface(com.berrybot.util.FontHelper.getMonospaceTypeface(this));
        String saved = keyStorage.getSavedPassphrase();
        if (saved != null) {
            input.setText(saved);
        } else {
            input.setHint("12 words or a memorable secret sentence");
        }
        input.setMinLines(3);
        input.setGravity(android.view.Gravity.TOP | android.view.Gravity.LEFT);

        Button btnGen12 = new Button(this);
        btnGen12.setText("Generate 12 Random Words");
        btnGen12.setOnClickListener(v -> {
            String words = com.berrybot.ssh.MnemonicKeyDerivation.generate12WordMnemonic(this);
            input.setText(words);
        });

        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(24, 16, 24, 8);
        layout.addView(input);
        layout.addView(btnGen12);

        new AlertDialog.Builder(this)
            .setTitle("Deterministic Key / Seed Phrase")
            .setMessage("Derive your SSH key from a seed phrase or custom phrase. The exact same phrase will always generate the exact same public key across reinstalls.")
            .setView(layout)
            .setPositiveButton("Apply Key", (dialog, which) -> {
                String phrase = input.getText().toString().trim();
                if (phrase.isEmpty()) {
                    Toast.makeText(this, "Phrase cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                boolean success = keyStorage.generateDeterministicKeyFromPhrase(phrase, "berrybot@classic");
                if (success) {
                    refreshPublicKey();
                    copyPublicKeyToClipboard();
                    Toast.makeText(this, "Permanent key applied and copied to clipboard!", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Failed to derive key from phrase", Toast.LENGTH_SHORT).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
