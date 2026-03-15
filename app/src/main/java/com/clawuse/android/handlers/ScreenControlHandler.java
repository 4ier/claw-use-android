package com.clawuse.android.handlers;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;

import com.clawuse.android.AccessibilityBridge;

import org.json.JSONObject;

import java.util.Map;

/**
 * Handles screen lock/unlock/wake/state endpoints:
 * POST /screen/lock     — lock screen
 * POST /screen/unlock   — wake + swipe + enter PIN via UI automation
 * POST /screen/wake     — wake screen without unlocking
 * GET  /screen/state    — current screen/lock state
 */
public class ScreenControlHandler implements RouteHandler {
    private final Context context;

    public ScreenControlHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        switch (path) {
            case "/screen/lock":   return handleLock();
            case "/screen/unlock": return handleUnlock(body);
            case "/screen/wake":   return handleWake();
            case "/screen/state":  return handleState();
            default: return "{\"error\":\"unknown screen endpoint\"}";
        }
    }

    private String handleLock() throws Exception {
        AccessibilityBridge bridge = AccessibilityBridge.getInstance();
        if (bridge == null) return "{\"error\":\"accessibility service not running\"}";

        if (Build.VERSION.SDK_INT >= 28) {
            boolean locked = bridge.doGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
            return new JSONObject().put("locked", locked).toString();
        } else {
            return "{\"error\":\"lock requires API 28+\"}";
        }
    }

    private String handleUnlock(String body) throws Exception {
        AccessibilityBridge bridge = AccessibilityBridge.getInstance();
        if (bridge == null) return "{\"error\":\"accessibility service not running\"}";

        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        String pin = req.optString("pin", "");

        // Step 1: Wake screen
        wakeScreen();
        Thread.sleep(500);

        // Step 2: Swipe up to dismiss lock screen
        // Standard Android: swipe from bottom center to top center
        bridge.swipe(540, 1800, 540, 600, 300);
        Thread.sleep(800);

        // Step 3: Enter PIN if provided
        if (!pin.isEmpty()) {
            AccessibilityNodeInfo root = bridge.getRootNode();
            if (root == null) {
                return new JSONObject().put("unlocked", false).put("error", "no window after swipe").toString();
            }

            try {
                // Enter each digit by finding and clicking the PIN pad buttons
                for (char digit : pin.toCharArray()) {
                    String digitStr = String.valueOf(digit);
                    AccessibilityNodeInfo digitBtn = bridge.findByText(root, digitStr);
                    if (digitBtn != null) {
                        bridge.clickNode(digitBtn);
                        digitBtn.recycle();
                        Thread.sleep(100);
                    }
                    // Refresh root for next digit (PIN pad may re-render)
                    root.recycle();
                    root = bridge.getRootNode();
                    if (root == null) break;
                }

                // Try to click Enter/OK/confirm button
                if (root != null) {
                    Thread.sleep(300);
                    root.recycle();
                    root = bridge.getRootNode();
                    if (root != null) {
                        // Look for enter/confirm button
                        AccessibilityNodeInfo enter = bridge.findByDesc(root, "Enter");
                        if (enter == null) enter = bridge.findByText(root, "OK");
                        if (enter == null) enter = bridge.findByDesc(root, "확인"); // Korean
                        if (enter == null) enter = bridge.findByDesc(root, "确认"); // Chinese
                        if (enter != null) {
                            bridge.clickNode(enter);
                            enter.recycle();
                        }
                    }
                }
            } finally {
                if (root != null) root.recycle();
            }

            Thread.sleep(500);
        }

        // Check final state
        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        boolean isLocked = km != null && km.isKeyguardLocked();

        return new JSONObject()
                .put("unlocked", !isLocked)
                .put("screenOn", isScreenOn())
                .toString();
    }

    private String handleWake() throws Exception {
        wakeScreen();
        Thread.sleep(200);
        return new JSONObject()
                .put("woke", true)
                .put("screenOn", isScreenOn())
                .toString();
    }

    private String handleState() throws Exception {
        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        boolean isLocked = km != null && km.isKeyguardLocked();
        boolean isSecure = km != null && km.isDeviceSecure();

        return new JSONObject()
                .put("screenOn", isScreenOn())
                .put("locked", isLocked)
                .put("secure", isSecure)
                .toString();
    }

    private void wakeScreen() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isInteractive()) {
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wl = pm.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "clawuse:wake");
            wl.acquire(3000);
            wl.release();
        }
    }

    private boolean isScreenOn() {
        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return pm != null && pm.isInteractive();
    }
}
