package com.waforwarder;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

import de.robv.android.xposed.XposedBridge;

/**
 * Forwards a message to a target WhatsApp group by launching
 * WA's Conversation activity with the target JID pre-filled,
 * then signalling the AccessibilityService to auto-tap Send.
 */
public class MessageForwarder {

    private static final String TAG = "WaForwarder/Forwarder";

    public static void forward(Context context, String targetJid, String message) {
        try {
            // Open WhatsApp's Conversation activity for the target group
            // "jid" extra is a well-known WA internal extra used by many WA mods
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.setPackage("com.whatsapp");
            intent.setComponent(new ComponentName("com.whatsapp", "com.whatsapp.Conversation"));
            intent.putExtra("jid", targetJid);
            intent.putExtra(Intent.EXTRA_TEXT, message);
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_SINGLE_TOP |
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            );

            context.startActivity(intent);

            // Tell accessibility service to click Send after WA opens
            WaAutoSendService.scheduleSend(message);

            XposedBridge.log(TAG + ": Activity started for target=" + targetJid);

        } catch (Exception e) {
            XposedBridge.log(TAG + ": Error forwarding message: " + e.getMessage());
        }
    }
}
