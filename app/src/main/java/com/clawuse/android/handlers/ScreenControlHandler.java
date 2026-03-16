package com.clawuse.android.handlers;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import com.clawuse.android.AccessibilityBridge;
import com.clawuse.android.SafeA11y;

import org.json.JSONObject;

import java.util.Map;

/**
 * Handles screen lock/unlock/wake/state endpoints.
 * All accessibility tree operations go through SafeA11y for timeout protection.
 */
public class ScreenControlHandler implements RouteHandler {
    private static final int A11Y_TIMEOUT = 3000;
    private final Context context;
    private final AccessibilityBridge bridge;

    public ScreenControlHandler(Context context) {
        this.context = context;
        this.bridge = (context instanceof AccessibilityBridge) ? (AccessibilityBridge) context : null;
    }

    private AccessibilityBridge getBridge() {
        return bridge != null ? bridge : AccessibilityBridge.getInstance();
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
        AccessibilityBridge b = getBridge();
        if (b == null) return "{\"error\":\"accessibility service not running\"}";

        if (Build.VERSION.SDK_INT >= 28) {
            boolean locked = b.doGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN);
            return new JSONObject().put("locked", locked).toString();
        } else {
            return "{\"error\":\"lock requires API 28+\"}";
        }
    }

    private String handleUnlock(String body) throws Exception {
        AccessibilityBridge b = getBridge();
        if (b == null) return "{\"error\":\"accessibility service not running\"}";

        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        String pin = req.optString("pin", "");

        // Get screen dimensions for accurate swipe coordinates
        int screenW = 1080, screenH = 2400; // safe defaults
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                DisplayMetrics dm = new DisplayMetrics();
                wm.getDefaultDisplay().getMetrics(dm);
                screenW = dm.widthPixels;
                screenH = dm.heightPixels;
            }
        } catch (Exception ignored) {}
        int centerX = screenW / 2;

        // Step 1: Wake screen
        wakeScreen();
        Thread.sleep(500);

        // Step 2: Swipe up to dismiss lock screen (from 75% height to 25% height)
        b.swipe(centerX, screenH * 3 / 4, centerX, screenH / 4, 300);
        Thread.sleep(800);

        // Step 3: Enter PIN if provided
        if (!pin.isEmpty()) {
            AccessibilityNodeInfo root = SafeA11y.getRootSafe(b, A11Y_TIMEOUT);
            if (root == null) {
                return new JSONObject().put("unlocked", false).put("error", "no window after swipe (timed out)").toString();
            }

            try {
                for (char digit : pin.toCharArray()) {
                    String digitStr = String.valueOf(digit);
                    AccessibilityNodeInfo digitBtn = SafeA11y.findByTextSafe(b, root, digitStr, A11Y_TIMEOUT);
                    if (digitBtn != null) {
                        b.clickNode(digitBtn);
                        digitBtn.recycle();
                        Thread.sleep(100);
                    }
                    root.recycle();
                    root = SafeA11y.getRootSafe(b, A11Y_TIMEOUT);
                    if (root == null) break;
                }

                if (root != null) {
                    Thread.sleep(300);
                    root.recycle();
                    root = SafeA11y.getRootSafe(b, A11Y_TIMEOUT);
                    if (root != null) {
                        AccessibilityNodeInfo enter = SafeA11y.findByDescSafe(b, root, "Enter", A11Y_TIMEOUT);
                        if (enter == null) enter = SafeA11y.findByTextSafe(b, root, "OK", A11Y_TIMEOUT);
                        if (enter == null) enter = SafeA11y.findByDescSafe(b, root, "确认", A11Y_TIMEOUT);
                        if (enter != null) {
                            b.clickNode(enter);
                            enter.recycle();
                        }
                    }
                }
            } finally {
                if (root != null) root.recycle();
            }

            Thread.sleep(500);
        }

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
