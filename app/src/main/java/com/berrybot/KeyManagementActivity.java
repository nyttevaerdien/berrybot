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

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_key_management);

        keyStorage = new KeyStorage(this);

        etPublicKey = (EditText) findViewById(R.id.etPublicKey);
        etPrivateKeyInput = (EditText) findViewById(R.id.etPrivateKeyInput);

        Button btnCopyPubkey = (Button) findViewById(R.id.btnCopyPubkey);
        Button btnGenerateKey = (Button) findViewById(R.id.btnGenerateKey);
        Button btnImportKey = (Button) findViewById(R.id.btnImportKey);
        Button btnClose = (Button) findViewById(R.id.btnClose);

        refreshPublicKey();

        btnCopyPubkey.setOnClickListener(v -> copyPublicKeyToClipboard());

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

        btnClose.setOnClickListener(v -> finish());
    }

    private void refreshPublicKey() {
        String pub = keyStorage.getOpenSSHPublicKey();
        etPublicKey.setText(pub);
    }

    private void copyPublicKeyToClipboard() {
        String pub = etPublicKey.getText().toString();
        if (pub.isEmpty()) return;

        ClipboardHelper.copyToClipboard(this, "BerryBot Public Key", pub);
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
    }
}
