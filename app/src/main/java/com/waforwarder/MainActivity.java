package com.waforwarder;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.*;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private Spinner sourceSpinner, targetSpinner;
    private Switch adminOnlySwitch, enabledSwitch;
    private Button saveButton, refreshButton;
    private TextView statusText, accessibilityStatus;

    private final List<String> groupNames = new ArrayList<>();
    private final List<String> groupJids  = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sourceSpinner      = findViewById(R.id.source_spinner);
        targetSpinner      = findViewById(R.id.target_spinner);
        adminOnlySwitch    = findViewById(R.id.admin_only_switch);
        enabledSwitch      = findViewById(R.id.enabled_switch);
        saveButton         = findViewById(R.id.save_button);
        refreshButton      = findViewById(R.id.refresh_button);
        statusText         = findViewById(R.id.status_text);
        accessibilityStatus = findViewById(R.id.accessibility_status);

        refreshButton.setOnClickListener(v -> loadGroupsAsync());
        saveButton.setOnClickListener(v -> savePrefs());

        findViewById(R.id.accessibility_button).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        loadGroupsAsync();
        loadSavedSwitches();
        updateAccessibilityStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessibilityStatus();
    }

    private void updateAccessibilityStatus() {
        boolean running = WaAutoSendService.isRunning();
        accessibilityStatus.setText(running
            ? "✓ Accessibility Service: Active"
            : "✗ Accessibility Service: NOT active — tap button below");
        accessibilityStatus.setTextColor(running ? 0xFF2E7D32 : 0xFFC62828);
    }

    private void loadGroupsAsync() {
        statusText.setText("Loading groups from WhatsApp database...");
        saveButton.setEnabled(false);

        new AsyncTask<Void, Void, Boolean>() {
            @Override
            protected Boolean doInBackground(Void... v) {
                return loadGroupsFromDb();
            }

            @Override
            protected void onPostExecute(Boolean success) {
                if (!success || groupNames.isEmpty()) {
                    statusText.setText(
                        "Failed to load groups.\n" +
                        "Make sure:\n" +
                        "• WhatsApp is installed\n" +
                        "• Root access is granted to this app\n" +
                        "• You have opened at least one group chat in WA"
                    );
                    return;
                }

                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                    MainActivity.this,
                    android.R.layout.simple_spinner_item,
                    groupNames
                );
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                sourceSpinner.setAdapter(adapter);

                // Target needs a separate adapter instance
                ArrayAdapter<String> targetAdapter = new ArrayAdapter<>(
                    MainActivity.this,
                    android.R.layout.simple_spinner_item,
                    groupNames
                );
                targetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                targetSpinner.setAdapter(targetAdapter);

                statusText.setText("Loaded " + groupNames.size() + " groups.");
                saveButton.setEnabled(true);
                restoreSpinnerSelections();
            }
        }.execute();
    }

    private boolean loadGroupsFromDb() {
        groupNames.clear();
        groupJids.clear();

        // Try direct access first (we're in WA process or have root)
        String dbPath = "/data/data/com.whatsapp/databases/msgstore.db";

        // If not directly readable, copy via root
        File dbFile = new File(dbPath);
        if (!dbFile.canRead()) {
            dbPath = copyDbWithRoot(dbPath);
            if (dbPath == null) return false;
        }

        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)) {
            boolean newSchema = tableHasColumn(db, "chat", "jid_row_id");

            Cursor c;
            if (newSchema) {
                c = db.rawQuery(
                    "SELECT j.raw_string, c.subject FROM chat c " +
                    "JOIN jid j ON c.jid_row_id = j._id " +
                    "WHERE j.raw_string LIKE '%@g.us' AND c.subject IS NOT NULL " +
                    "ORDER BY c.subject",
                    null
                );
            } else {
                c = db.rawQuery(
                    "SELECT key_remote_jid, subject FROM chat " +
                    "WHERE key_remote_jid LIKE '%@g.us' AND subject IS NOT NULL " +
                    "ORDER BY subject",
                    null
                );
            }

            while (c.moveToNext()) {
                String jid  = c.getString(0);
                String name = c.getString(1);
                if (jid != null && name != null) {
                    groupJids.add(jid);
                    groupNames.add(name);
                }
            }
            c.close();
            return !groupNames.isEmpty();

        } catch (Exception e) {
            return false;
        }
    }

    private String copyDbWithRoot(String originalPath) {
        try {
            String dest = getFilesDir().getPath() + "/wa_msgstore_copy.db";
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("cp \"" + originalPath + "\" \"" + dest + "\"\n");
            os.writeBytes("chmod 644 \"" + dest + "\"\n");
            os.writeBytes("exit\n");
            os.flush();
            int exit = su.waitFor();
            if (exit != 0) return null;
            return new File(dest).exists() ? dest : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean tableHasColumn(SQLiteDatabase db, String table, String column) {
        try (Cursor c = db.rawQuery("PRAGMA table_info(" + table + ")", null)) {
            while (c.moveToNext()) {
                if (column.equals(c.getString(c.getColumnIndex("name")))) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void loadSavedSwitches() {
        SharedPreferences prefs = getSharedPreferences("wa_forwarder_prefs", MODE_PRIVATE);
        enabledSwitch.setChecked(prefs.getBoolean("enabled", false));
        adminOnlySwitch.setChecked(prefs.getBoolean("admin_only", false));
    }

    private void restoreSpinnerSelections() {
        SharedPreferences prefs = getSharedPreferences("wa_forwarder_prefs", MODE_PRIVATE);
        String savedSource = prefs.getString("source_group_jid", "");
        String savedTarget = prefs.getString("target_group_jid", "");

        int srcPos = groupJids.indexOf(savedSource);
        int tgtPos = groupJids.indexOf(savedTarget);

        if (srcPos >= 0) sourceSpinner.setSelection(srcPos);
        if (tgtPos >= 0) targetSpinner.setSelection(tgtPos);
    }

    private void savePrefs() {
        if (groupJids.isEmpty()) {
            Toast.makeText(this, "No groups loaded — tap Refresh first", Toast.LENGTH_SHORT).show();
            return;
        }

        int srcPos = sourceSpinner.getSelectedItemPosition();
        int tgtPos = targetSpinner.getSelectedItemPosition();

        if (srcPos == tgtPos) {
            Toast.makeText(this, "Source and target must be different groups!", Toast.LENGTH_SHORT).show();
            return;
        }

        String sourceJid = groupJids.get(srcPos);
        String targetJid = groupJids.get(tgtPos);

        SharedPreferences.Editor editor =
            getSharedPreferences("wa_forwarder_prefs", MODE_PRIVATE).edit();
        editor.putString("source_group_jid", sourceJid);
        editor.putString("target_group_jid", targetJid);
        editor.putBoolean("admin_only", adminOnlySwitch.isChecked());
        editor.putBoolean("enabled", enabledSwitch.isChecked());
        editor.apply();

        // Also write to world-readable location for XSharedPreferences
        writeWorldReadablePrefs(sourceJid, targetJid,
            adminOnlySwitch.isChecked(), enabledSwitch.isChecked());

        String msg = "Saved!\nSource: " + groupNames.get(srcPos) +
                     "\nTarget: " + groupNames.get(tgtPos);
        if (!WaAutoSendService.isRunning()) {
            msg += "\n\n⚠ Enable Accessibility Service to auto-send!";
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private void writeWorldReadablePrefs(String srcJid, String tgtJid,
                                          boolean adminOnly, boolean enabled) {
        // Write prefs to a file readable by the Xposed hook via XSharedPreferences
        try {
            File prefsDir = new File(getApplicationInfo().dataDir + "/shared_prefs");
            prefsDir.mkdirs();
            File prefsFile = new File(prefsDir, "wa_forwarder_prefs.xml");

            String xml = "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n" +
                "<map>\n" +
                "  <string name=\"source_group_jid\">" + srcJid + "</string>\n" +
                "  <string name=\"target_group_jid\">" + tgtJid + "</string>\n" +
                "  <boolean name=\"admin_only\" value=\"" + adminOnly + "\" />\n" +
                "  <boolean name=\"enabled\" value=\"" + enabled + "\" />\n" +
                "</map>";

            java.io.FileWriter fw = new java.io.FileWriter(prefsFile);
            fw.write(xml);
            fw.close();

            // Make world-readable via root so XSharedPreferences can read it
            Process su = Runtime.getRuntime().exec("su");
            java.io.DataOutputStream os = new java.io.DataOutputStream(su.getOutputStream());
            os.writeBytes("chmod 644 \"" + prefsFile.getAbsolutePath() + "\"\n");
            os.writeBytes("exit\n");
            os.flush();
            su.waitFor();

        } catch (Exception e) {
            // Non-fatal, preferences still saved via MODE_PRIVATE
        }
    }
}
