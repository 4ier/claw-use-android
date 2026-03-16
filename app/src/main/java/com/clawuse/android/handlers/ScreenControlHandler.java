package com.clawuse.android.handlers;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.PowerManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import com.clawuse.android.AccessibilityBridge;
import com.clawuse.android.ConfigStore;
import com.clawuse.android.SafeA11y;

import java.util.List;

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
        String pattern = req.optString("pattern", "");

        // Auto-detect from config if not provided in request
        if (pin.isEmpty() && pattern.isEmpty()) {
            ConfigStore config = ConfigStore.get(context);
            String type = config.getUnlockType();
            if ("pattern".equals(type) && config.hasPattern()) {
                pattern = config.getPattern();
            } else if (config.hasPin()) {
                pin = config.getPin();
            }
        }

        // Get screen dimensions
        int screenW = 1080, screenH = 2400;
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

        // Step 2: Swipe up to dismiss lock screen
        b.swipe(centerX, screenH * 3 / 4, centerX, screenH / 4, 300);
        Thread.sleep(800);

        // Step 3: Enter credential
        if (!pattern.isEmpty()) {
            // Pattern unlock — draw gesture on 3x3 grid
            int calCenterX = req.optInt("gridCenterX", -1);
            int calCenterY = req.optInt("gridCenterY", -1);
            int calGridSize = req.optInt("gridSize", -1);
            unlockWithPattern(b, pattern, screenW, screenH, calCenterX, calCenterY, calGridSize);
        } else if (!pin.isEmpty()) {
            // PIN/password unlock — tap digits
            unlockWithPin(b, pin);
        }

        Thread.sleep(500);

        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        boolean isLocked = km != null && km.isKeyguardLocked();

        return new JSONObject()
                .put("unlocked", !isLocked)
                .put("screenOn", isScreenOn())
                .toString();
    }

    /**
     * Draw pattern on 3x3 grid. Pattern is a string of digits 1-9 like "256398".
     * Grid layout:
     *   1  2  3
     *   4  5  6
     *   7  8  9
     *
     * The pattern grid is centered horizontally and typically occupies the middle
     * portion of the lock screen.
     */
    private void unlockWithPattern(AccessibilityBridge b, String pattern, int screenW, int screenH) throws Exception {
        unlockWithPattern(b, pattern, screenW, screenH, -1, -1, -1);
    }

    /**
     * Draw pattern on 3x3 grid. Pattern is a string of digits 1-9 like "256398".
     * Grid layout:
     *   1  2  3
     *   4  5  6
     *   7  8  9
     *
     * Optional calibration params: gridCenterX, gridCenterY, gridSize
     * If all -1, auto-detect based on screen dimensions.
     */
    private void unlockWithPattern(AccessibilityBridge b, String pattern, int screenW, int screenH,
                                    int calCenterX, int calCenterY, int calGridSize) throws Exception {
        int gridLeft, gridTop, gridSize;

        if (calGridSize > 0 && calCenterX > 0 && calCenterY > 0) {
            // Manual calibration
            gridSize = calGridSize;
            gridLeft = calCenterX - gridSize / 2;
            gridTop = calCenterY - gridSize / 2;
            android.util.Log.i("ClawUse", "Pattern: using manual calibration");
        } else {
            // Try to find LockPatternView from all windows (including lock screen)
            Rect patternBounds = findPatternViewBounds(b);
            if (patternBounds != null) {
                gridLeft = patternBounds.left;
                gridTop = patternBounds.top;
                gridSize = Math.min(patternBounds.width(), patternBounds.height());
                android.util.Log.i("ClawUse", "Pattern: found LockPatternView bounds=" + patternBounds);
            } else {
                // Fallback: estimate grid position
                gridSize = (int) (screenW * 0.55);
                gridLeft = (screenW - gridSize) / 2;
                int gridCenterY = (int) (screenH * 0.55);
                gridTop = gridCenterY - gridSize / 2;
                android.util.Log.i("ClawUse", "Pattern: using estimated position (no view found)");
            }
        }

        int cellSize = gridSize / 2;  // 3 dots → 2 gaps
        // Dots are at center of each cell, so offset by cellSize/2... no, dots ARE at grid intersections
        // Actually: 3 dots evenly placed means spacing = gridSize/2, first dot at gridLeft, last at gridLeft+gridSize

        android.util.Log.i("ClawUse", "Pattern grid: left=" + gridLeft + " top=" + gridTop
                + " size=" + gridSize + " cellSize=" + cellSize);

        // Map digit 1-9 to grid coordinates (dot centers)
        int[][] dotXY = new int[10][2];
        for (int d = 1; d <= 9; d++) {
            int col = (d - 1) % 3;
            int row = (d - 1) / 3;
            dotXY[d][0] = gridLeft + col * cellSize;
            dotXY[d][1] = gridTop + row * cellSize;
        }

        // Log path
        StringBuilder sb = new StringBuilder("Pattern path: ");
        for (char c : pattern.toCharArray()) {
            int digit = c - '0';
            if (digit >= 1 && digit <= 9) {
                sb.append(digit).append("=(").append(dotXY[digit][0]).append(",").append(dotXY[digit][1]).append(") ");
            }
        }
        android.util.Log.i("ClawUse", sb.toString());

        // Build path
        Path path = new Path();
        boolean first = true;
        for (char c : pattern.toCharArray()) {
            int digit = c - '0';
            if (digit < 1 || digit > 9) continue;
            if (first) {
                path.moveTo(dotXY[digit][0], dotXY[digit][1]);
                first = false;
            } else {
                path.lineTo(dotXY[digit][0], dotXY[digit][1]);
            }
        }

        // Dispatch gesture with callback to verify execution
        GestureDescription.Builder builder = new GestureDescription.Builder();
        long duration = Math.max(pattern.length() * 150L, 600);
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, duration));

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] success = {false};
        b.dispatchGesture(builder.build(), new AccessibilityService.GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                android.util.Log.i("ClawUse", "Pattern gesture COMPLETED");
                success[0] = true;
                latch.countDown();
            }
            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                android.util.Log.w("ClawUse", "Pattern gesture CANCELLED");
                latch.countDown();
            }
        }, null);

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        android.util.Log.i("ClawUse", "Pattern gesture result: " + (success[0] ? "completed" : "failed/cancelled"));
        Thread.sleep(1500);
    }

    /**
     * Search all windows for a LockPatternView or similar pattern input view.
     * Returns the bounds of the pattern view, or null if not found.
     */
    private Rect findPatternViewBounds(AccessibilityBridge b) {
        List<AccessibilityNodeInfo> roots = b.getAllWindowRoots();
        try {
            for (AccessibilityNodeInfo root : roots) {
                Rect found = findPatternViewInTree(root);
                if (found != null) return found;
            }
        } finally {
            for (AccessibilityNodeInfo root : roots) {
                try { root.recycle(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private Rect findPatternViewInTree(AccessibilityNodeInfo node) {
        if (node == null) return null;

        String cls = node.getClassName() != null ? node.getClassName().toString() : "";

        // Log all nodes in lock screen for debugging
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        android.util.Log.d("ClawUse", "Lock tree: class=" + cls + " bounds=" + bounds
                + " desc=" + node.getContentDescription());

        // Look for pattern views by class name
        if (cls.contains("LockPatternView") || cls.contains("PatternView")
                || cls.contains("lockpattern") || cls.contains("patternview")) {
            node.getBoundsInScreen(bounds);
            if (bounds.width() > 100 && bounds.height() > 100) {
                return bounds;
            }
        }

        // Also look for large square-ish views that could be the pattern area
        // (MIUI might use custom class names)
        if (bounds.width() > 200 && bounds.height() > 200
                && Math.abs(bounds.width() - bounds.height()) < bounds.width() * 0.3) {
            // Candidate: large roughly-square view
            CharSequence desc = node.getContentDescription();
            if (desc != null && (desc.toString().contains("图案") || desc.toString().contains("pattern")
                    || desc.toString().contains("Pattern"))) {
                return bounds;
            }
        }

        // Recurse into children
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                Rect found = findPatternViewInTree(child);
                child.recycle();
                if (found != null) return found;
            }
        }
        return null;
    }

    private void unlockWithPin(AccessibilityBridge b, String pin) throws Exception {
        AccessibilityNodeInfo root = SafeA11y.getRootSafe(b, A11Y_TIMEOUT);
        if (root == null) return;

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
