package com.waforwarder;

import android.app.Application;
import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    public static final String TAG = "WaForwarder";
    public static XSharedPreferences prefs;
    private static Context waContext;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.whatsapp")) return;

        XposedBridge.log(TAG + ": Loading into WhatsApp process");

        prefs = new XSharedPreferences("com.waforwarder", "wa_forwarder_prefs");
        prefs.makeWorldReadable();

        // Step 1: Capture WA's application context when WA starts
        XposedHelpers.findAndHookMethod(
            Application.class,
            "onCreate",
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Application app = (Application) param.thisObject;
                    if (app.getPackageName().equals("com.whatsapp")) {
                        waContext = app.getApplicationContext();
                        XposedBridge.log(TAG + ": Captured WA context");
                        // Pre-load group list for the admin-check cache
                        new Thread(() -> GroupCache.load(waContext)).start();
                    }
                }
            }
        );

        // Step 2: Hook NotificationManager.notify to intercept group message notifications
        // This is Android framework class, version-independent — always works
        XposedHelpers.findAndHookMethod(
            "android.app.NotificationManager",
            null,
            "notify",
            String.class,   // tag  (WA uses group JID as tag)
            int.class,      // id
            Notification.class,
            new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (waContext == null) return;
                    try {
                        String tag = (String) param.args[0];
                        Notification notif = (Notification) param.args[2];
                        handleNotification(tag, notif);
                    } catch (Exception e) {
                        XposedBridge.log(TAG + " notify hook error: " + e.getMessage());
                    }
                }
            }
        );

        XposedBridge.log(TAG + ": Hooks installed successfully");
    }

    private static void handleNotification(String tag, Notification notif) {
        if (notif == null || notif.extras == null) return;

        // Reload prefs to pick up latest settings
        prefs.reload();

        // Check if module is enabled
        if (!prefs.getBoolean("enabled", false)) return;

        String sourceJid = prefs.getString("source_group_jid", "");
        String targetJid = prefs.getString("target_group_jid", "");
        boolean adminOnly = prefs.getBoolean("admin_only", false);

        if (sourceJid.isEmpty() || targetJid.isEmpty()) return;

        Bundle extras = notif.extras;

        // EXTRA_CONVERSATION_TITLE is set for GROUP messages; null for 1-on-1
        CharSequence convTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        if (convTitle == null) return; // Not a group message

        String groupName = convTitle.toString();

        // Sender name (the person who sent the message in the group)
        CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
        String senderName = titleCs != null ? titleCs.toString() : "";

        // Message text
        CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
        String messageText = textCs != null ? textCs.toString() : "";

        if (messageText.isEmpty()) return;

        // Match source group: WA sometimes uses JID as notification tag
        boolean isSourceGroup = false;

        // Strategy 1: Direct JID match via notification tag
        if (tag != null && (tag.equals(sourceJid) || tag.startsWith(sourceJid))) {
            isSourceGroup = true;
        }

        // Strategy 2: Match by group name from our cached group list
        if (!isSourceGroup) {
            String cachedJid = GroupCache.getJidByName(groupName);
            if (sourceJid.equals(cachedJid)) {
                isSourceGroup = true;
            }
        }

        if (!isSourceGroup) return;

        // Admin-only filter
        if (adminOnly && !GroupCache.isAdmin(sourceJid, senderName)) {
            XposedBridge.log(TAG + ": Skipped (sender not admin): " + senderName);
            return;
        }

        // Forward the message
        String forwardText = senderName + ": " + messageText;
        XposedBridge.log(TAG + ": Forwarding message from " + groupName + " to " + targetJid);

        // Post to main thread for Activity start
        new Handler(Looper.getMainLooper()).post(() ->
            MessageForwarder.forward(waContext, targetJid, forwardText)
        );
    }

    public static Context getWaContext() {
        return waContext;
    }
}
