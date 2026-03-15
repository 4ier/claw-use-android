package com.clawuse.android.handlers;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.clawuse.android.AccessibilityBridge;
import com.clawuse.android.NotificationBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * Handles system info endpoints:
 * GET /ping        — health check (unauthenticated)
 * GET /info        — device details
 * GET /permissions — granted/denied permissions
 */
public class InfoHandler implements RouteHandler {
    private final Context context;

    // Permissions we care about
    private static final String[][] PERMISSION_MAP = {
            {"camera", "android.permission.CAMERA"},
            {"microphone", "android.permission.RECORD_AUDIO"},
            {"location", "android.permission.ACCESS_FINE_LOCATION"},
            {"phone", "android.permission.CALL_PHONE"},
            {"sms_send", "android.permission.SEND_SMS"},
            {"sms_read", "android.permission.READ_SMS"},
            {"contacts", "android.permission.READ_CONTACTS"},
            {"calendar", "android.permission.READ_CALENDAR"},
            {"storage", "android.permission.READ_EXTERNAL_STORAGE"},
    };

    public InfoHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        switch (path) {
            case "/ping": return handlePing();
            case "/info": return handleInfo();
            case "/permissions": return handlePermissions();
            default: return "{\"error\":\"unknown info endpoint\"}";
        }
    }

    private String handlePing() throws Exception {
        return new JSONObject()
                .put("status", "ok")
                .put("service", "claw-use-android")
                .put("version", "1.0.0")
                .toString();
    }

    private String handleInfo() throws Exception {
        JSONObject info = new JSONObject();

        // Device
        info.put("manufacturer", Build.MANUFACTURER);
        info.put("model", Build.MODEL);
        info.put("device", Build.DEVICE);
        info.put("androidVersion", Build.VERSION.RELEASE);
        info.put("apiLevel", Build.VERSION.SDK_INT);

        // Screen
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(dm);
            info.put("screenWidth", dm.widthPixels);
            info.put("screenHeight", dm.heightPixels);
            info.put("density", dm.density);
        }

        // Battery
        BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
        if (bm != null) {
            int level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            boolean charging = bm.isCharging();
            info.put("batteryLevel", level);
            info.put("batteryCharging", charging);
        }

        // Storage
        StatFs stat = new StatFs(Environment.getDataDirectory().getPath());
        long totalBytes = stat.getTotalBytes();
        long freeBytes = stat.getAvailableBytes();
        info.put("storageTotalMB", totalBytes / (1024 * 1024));
        info.put("storageFreeMB", freeBytes / (1024 * 1024));

        // Services
        info.put("accessibilityRunning", AccessibilityBridge.isRunning());
        info.put("notificationListenerRunning", NotificationBridge.isRunning());

        return info.toString();
    }

    private String handlePermissions() throws Exception {
        JSONArray permissions = new JSONArray();
        PackageManager pm = context.getPackageManager();

        for (String[] entry : PERMISSION_MAP) {
            JSONObject p = new JSONObject();
            p.put("name", entry[0]);
            p.put("permission", entry[1]);
            p.put("granted", pm.checkPermission(entry[1], context.getPackageName())
                    == PackageManager.PERMISSION_GRANTED);
            permissions.put(p);
        }

        JSONObject result = new JSONObject();
        result.put("permissions", permissions);
        result.put("accessibilityService", AccessibilityBridge.isRunning());
        result.put("notificationListener", NotificationBridge.isRunning());
        return result.toString();
    }
}
