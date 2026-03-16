package com.clawuse.android.handlers;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import com.clawuse.android.AccessibilityBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Handles /launch — start an app by package name using Intent.
 * Does NOT depend on the accessibility tree, so it always works even
 * when the UI tree is pathological.
 *
 * GET  /launch          — list launchable apps
 * POST /launch          — {"package":"com.ss.android.ugc.aweme"} — launch app
 */
public class LaunchHandler implements RouteHandler {
    private final Context context;

    public LaunchHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if ("GET".equalsIgnoreCase(method)) {
            return listApps();
        }
        // POST — launch app
        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        String pkg = req.optString("package", "");
        if (pkg.isEmpty()) {
            return "{\"error\":\"missing 'package' field\"}";
        }
        return launchApp(pkg);
    }

    private String launchApp(String pkg) throws Exception {
        PackageManager pm = context.getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) {
            return new JSONObject()
                    .put("error", "app not found or not launchable")
                    .put("package", pkg)
                    .toString();
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        // Prefer AccessibilityService context — higher privilege, bypasses MIUI restrictions
        Context launchContext = context;
        AccessibilityBridge bridge = AccessibilityBridge.getInstance();
        if (bridge != null) {
            launchContext = bridge;
        }
        launchContext.startActivity(intent);

        return new JSONObject()
                .put("launched", true)
                .put("package", pkg)
                .put("via", bridge != null ? "accessibility" : "service")
                .toString();
    }

    private String listApps() throws Exception {
        PackageManager pm = context.getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(mainIntent, 0);

        JSONArray arr = new JSONArray();
        for (ResolveInfo ri : apps) {
            JSONObject obj = new JSONObject();
            obj.put("package", ri.activityInfo.packageName);
            obj.put("name", ri.loadLabel(pm).toString());
            arr.put(obj);
        }

        return new JSONObject()
                .put("apps", arr)
                .put("count", arr.length())
                .toString();
    }
}
