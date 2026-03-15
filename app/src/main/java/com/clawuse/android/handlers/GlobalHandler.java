package com.clawuse.android.handlers;

import android.accessibilityservice.AccessibilityService;

import com.clawuse.android.AccessibilityBridge;

import org.json.JSONObject;

import java.util.Map;

/**
 * Handles POST /global — system-wide actions: back, home, recents, notifications, etc.
 */
public class GlobalHandler implements RouteHandler {

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        AccessibilityBridge bridge = AccessibilityBridge.getInstance();
        if (bridge == null) {
            return "{\"error\":\"accessibility service not running\"}";
        }

        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        String action = req.optString("action", "");

        int globalAction;
        switch (action) {
            case "back":
                globalAction = AccessibilityService.GLOBAL_ACTION_BACK;
                break;
            case "home":
                globalAction = AccessibilityService.GLOBAL_ACTION_HOME;
                break;
            case "recents":
                globalAction = AccessibilityService.GLOBAL_ACTION_RECENTS;
                break;
            case "notifications":
                globalAction = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
                break;
            case "quick_settings":
                globalAction = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
                break;
            case "power_dialog":
                globalAction = AccessibilityService.GLOBAL_ACTION_POWER_DIALOG;
                break;
            default:
                return "{\"error\":\"unknown action\",\"valid\":[\"back\",\"home\",\"recents\",\"notifications\",\"quick_settings\",\"power_dialog\"]}";
        }

        boolean performed = bridge.doGlobalAction(globalAction);
        return new JSONObject()
                .put("performed", performed).put("action", action).toString();
    }
}
