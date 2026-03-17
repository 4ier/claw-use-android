package com.clawuse.android.handlers;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityManager;
import android.content.Context;

import com.clawuse.android.AccessibilityBridge;
import com.clawuse.android.SafeA11y;

import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Handles app management endpoints:
 * POST /app/kill — force close a background app
 * POST /app/force-home — go home + return post-state
 */
public class AppHandler implements RouteHandler {
    private final Context context;
    private final AccessibilityBridge bridge;

    public AppHandler(Context context) {
        this.context = context;
        this.bridge = (context instanceof AccessibilityBridge) ? (AccessibilityBridge) context : null;
    }

    private AccessibilityBridge getBridge() {
        return bridge != null ? bridge : AccessibilityBridge.getInstance();
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        switch (path) {
            case "/app/kill":       return handleKill(body);
            case "/app/force-home": return handleForceHome();
            default:                return "{\"error\":\"unknown app endpoint\"}";
        }
    }

    private String handleKill(String body) throws Exception {
        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        String packageName = req.optString("package", "");
        if (packageName.isEmpty()) {
            return "{\"error\":\"provide 'package'\"}";
        }

        AccessibilityBridge b = getBridge();
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (am == null) {
            return "{\"error\":\"ActivityManager not available\"}";
        }

        // Check if the app is in foreground before killing
        boolean wasForeground = false;
        if (b != null) {
            String fgPkg = SafeA11y.run(() -> {
                android.view.accessibility.AccessibilityNodeInfo root = b.getRootNode();
                if (root == null) return "";
                try {
                    CharSequence pkg = root.getPackageName();
                    return pkg != null ? pkg.toString() : "";
                } finally {
                    root.recycle();
                }
            }, 1500);
            wasForeground = packageName.equals(fgPkg);
        }

        // Kill background processes
        am.killBackgroundProcesses(packageName);

        // If it was foreground, push to home first
        if (wasForeground && b != null) {
            b.doGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            Thread.sleep(500);
        }

        // Verify: check if process is still running
        boolean stillRunning = false;
        List<ActivityManager.RunningAppProcessInfo> procs = am.getRunningAppProcesses();
        if (procs != null) {
            for (ActivityManager.RunningAppProcessInfo proc : procs) {
                if (proc.processName != null && proc.processName.equals(packageName)) {
                    stillRunning = true;
                    break;
                }
            }
        }

        JSONObject result = new JSONObject();
        result.put("killed", !stillRunning);
        result.put("package", packageName);
        result.put("wasForeground", wasForeground);
        if (stillRunning) {
            result.put("hint", "app may still be running as foreground; killBackgroundProcesses only kills background");
        }
        return result.toString();
    }

    private String handleForceHome() throws Exception {
        AccessibilityBridge b = getBridge();
        if (b == null) {
            return "{\"error\":\"accessibility service not running\"}";
        }

        boolean performed = b.doGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        Thread.sleep(500);

        // Get post-state
        JSONObject result = new JSONObject();
        result.put("performed", performed);

        String stateJson = SafeA11y.run(() -> {
            return ScreenHandler.buildQuickState(b, 2, 10, 0);
        }, 1500);

        if (stateJson != null) {
            JSONObject full = new JSONObject(stateJson);
            JSONObject post = new JSONObject();
            post.put("package", full.optString("package", ""));
            post.put("topTexts", full.optJSONArray("topTexts"));
            result.put("post", post);
        }

        return result.toString();
    }
}
