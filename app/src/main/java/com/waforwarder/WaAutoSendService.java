package com.waforwarder;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

/**
 * Accessibility Service that automatically taps WhatsApp's Send button
 * after the Conversation activity opens with pre-filled text.
 *
 * Setup: Android Settings → Accessibility → WaForwarder Auto-Send → Enable
 * (Can also be enabled via root: `settings put secure enabled_accessibility_services com.waforwarder/.WaAutoSendService`)
 */
public class WaAutoSendService extends AccessibilityService {

    private static WaAutoSendService instance;
    private static volatile boolean pendingSend = false;
    private static volatile String pendingMessage = null;
    private static final int SEND_DELAY_MS = 1200; // wait for WA to fully load

    private final Handler handler = new Handler(Looper.getMainLooper());

    public static void scheduleSend(String message) {
        pendingMessage = message;
        pendingSend = true;
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!pendingSend) return;
        if (event.getPackageName() == null) return;
        if (!event.getPackageName().toString().equals("com.whatsapp")) return;

        // Only act on window content changed or focused events (WA fully loaded)
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

        // Schedule send attempt after a short delay for WA to render the compose area
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(this::attemptSend, SEND_DELAY_MS);
    }

    private void attemptSend() {
        if (!pendingSend) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        // Check we're actually in WhatsApp
        if (root.getPackageName() == null ||
            !root.getPackageName().toString().equals("com.whatsapp")) return;

        // Strategy 1: Find send button by resource ID (most reliable)
        String[] sendButtonIds = {
            "com.whatsapp:id/send",
            "com.whatsapp:id/send_button",
            "com.whatsapp:id/conversation_entry_sms_button"
        };

        for (String resId : sendButtonIds) {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(resId);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    if (node != null && node.isEnabled() && node.isClickable()) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                        pendingSend = false;
                        pendingMessage = null;
                        return;
                    }
                }
            }
        }

        // Strategy 2: Find by content description "Send"
        List<AccessibilityNodeInfo> sendNodes = root.findAccessibilityNodeInfosByText("Send");
        if (sendNodes != null) {
            for (AccessibilityNodeInfo node : sendNodes) {
                if (node != null && node.isClickable()) {
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    pendingSend = false;
                    pendingMessage = null;
                    return;
                }
            }
        }

        // Retry once more after additional delay if not found yet
        handler.postDelayed(() -> {
            if (pendingSend) {
                pendingSend = false; // give up after second try
                pendingMessage = null;
            }
        }, 2000);
    }

    @Override
    public void onInterrupt() {
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
    }

    public static boolean isRunning() {
        return instance != null;
    }
}
