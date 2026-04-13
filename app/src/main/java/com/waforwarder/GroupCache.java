package com.waforwarder;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XposedBridge;

/**
 * Reads WhatsApp's SQLite databases (msgstore.db, wa.db) to build
 * an in-memory cache of groups and their admin members.
 *
 * Supports both old schema (key_remote_jid in chat table) and
 * new schema (jid table + foreign key).
 */
public class GroupCache {

    private static final String TAG = "WaForwarder/GroupCache";

    // groupName -> JID
    private static final Map<String, String> nameToJid = new HashMap<>();
    // JID -> groupName
    private static final Map<String, String> jidToName = new HashMap<>();
    // groupJID -> set of admin display names
    private static final Map<String, Set<String>> groupAdminNames = new HashMap<>();

    public static synchronized void load(Context context) {
        loadGroups(context);
        loadAdmins(context);
        XposedBridge.log(TAG + ": Loaded " + nameToJid.size() + " groups");
    }

    private static void loadGroups(Context context) {
        String dbPath = "/data/data/com.whatsapp/databases/msgstore.db";
        if (!new File(dbPath).exists()) {
            XposedBridge.log(TAG + ": msgstore.db not found at " + dbPath);
            return;
        }

        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)) {

            // Try new schema first (WA 2.22+): jid table with raw_string
            boolean newSchema = tableExists(db, "jid");

            Cursor c;
            if (newSchema) {
                c = db.rawQuery(
                    "SELECT j.raw_string, c.subject " +
                    "FROM chat c JOIN jid j ON c.jid_row_id = j._id " +
                    "WHERE j.raw_string LIKE '%@g.us' AND c.subject IS NOT NULL " +
                    "ORDER BY c.subject",
                    null
                );
            } else {
                // Old schema
                c = db.rawQuery(
                    "SELECT key_remote_jid, subject FROM chat " +
                    "WHERE key_remote_jid LIKE '%@g.us' AND subject IS NOT NULL " +
                    "ORDER BY subject",
                    null
                );
            }

            nameToJid.clear();
            jidToName.clear();
            while (c.moveToNext()) {
                String jid = c.getString(0);
                String name = c.getString(1);
                if (jid != null && name != null) {
                    nameToJid.put(name, jid);
                    jidToName.put(jid, name);
                }
            }
            c.close();

        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error loading groups: " + e.getMessage());
        }
    }

    private static void loadAdmins(Context context) {
        // Admins may be in wa.db or in msgstore.db depending on WA version
        String[] dbPaths = {
            "/data/data/com.whatsapp/databases/wa.db",
            "/data/data/com.whatsapp/databases/msgstore.db"
        };

        for (String dbPath : dbPaths) {
            if (!new File(dbPath).exists()) continue;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)) {

                // Try new schema with jid table
                if (tableExists(db, "group_members") && tableExists(db, "jid")) {
                    loadAdminsNewSchema(db);
                    return;
                }
                // Try old schema
                if (tableExists(db, "group_participants")) {
                    loadAdminsOldSchema(db);
                    return;
                }
            } catch (Exception e) {
                XposedBridge.log(TAG + ": Admin load error in " + dbPath + ": " + e.getMessage());
            }
        }
    }

    private static void loadAdminsNewSchema(SQLiteDatabase db) {
        // New WA schema: group_members table with jid foreign keys
        try (Cursor c = db.rawQuery(
            "SELECT gj.raw_string, COALESCE(wc.display_name, mj.raw_string) " +
            "FROM group_members gm " +
            "JOIN jid gj ON gm.group_jid_row_id = gj._id " +
            "JOIN jid mj ON gm.jid_row_id = mj._id " +
            "LEFT JOIN wa_contacts wc ON mj.raw_string = wc.jid " +
            "WHERE gm.admin > 0",
            null
        )) {
            groupAdminNames.clear();
            while (c.moveToNext()) {
                String gjid = c.getString(0);
                String displayName = c.getString(1);
                if (gjid != null && displayName != null) {
                    groupAdminNames.computeIfAbsent(gjid, k -> new HashSet<>()).add(displayName);
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": New schema admin load error: " + e.getMessage());
        }
    }

    private static void loadAdminsOldSchema(SQLiteDatabase db) {
        // Old WA schema: group_participants table
        try (Cursor c = db.rawQuery(
            "SELECT gp.gjid, COALESCE(wc.display_name, gp.jid) " +
            "FROM group_participants gp " +
            "LEFT JOIN wa_contacts wc ON gp.jid = wc.jid " +
            "WHERE gp.admin > 0",
            null
        )) {
            groupAdminNames.clear();
            while (c.moveToNext()) {
                String gjid = c.getString(0);
                String displayName = c.getString(1);
                if (gjid != null && displayName != null) {
                    groupAdminNames.computeIfAbsent(gjid, k -> new HashSet<>()).add(displayName);
                }
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + ": Old schema admin load error: " + e.getMessage());
        }
    }

    private static boolean tableExists(SQLiteDatabase db, String tableName) {
        try (Cursor c = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name=?",
            new String[]{tableName}
        )) {
            return c.moveToFirst();
        } catch (Exception e) {
            return false;
        }
    }

    public static synchronized String getJidByName(String name) {
        return nameToJid.get(name);
    }

    public static synchronized String getNameByJid(String jid) {
        return jidToName.get(jid);
    }

    public static synchronized boolean isAdmin(String groupJid, String senderDisplayName) {
        Set<String> admins = groupAdminNames.get(groupJid);
        if (admins == null || admins.isEmpty()) return false;
        return admins.contains(senderDisplayName);
    }

    /** Returns list of all groups as [name, jid] pairs, sorted by name */
    public static synchronized List<String[]> getAllGroups() {
        List<String[]> result = new ArrayList<>();
        for (Map.Entry<String, String> e : nameToJid.entrySet()) {
            result.add(new String[]{e.getKey(), e.getValue()});
        }
        result.sort((a, b) -> a[0].compareToIgnoreCase(b[0]));
        return result;
    }
}
