package com.clawuse.android.handlers;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import com.clawuse.android.AccessibilityBridge;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Map;

/**
 * Handles POST /intent — fire any Android Intent.
 *
 * Examples:
 *   Call:  {"action":"android.intent.action.CALL","uri":"tel:+1234567890"}
 *   SMS:  {"action":"android.intent.action.SENDTO","uri":"smsto:+1234567890","extras":{"sms_body":"Hello"}}
 *   URL:  {"action":"android.intent.action.VIEW","uri":"https://github.com"}
 *   Share: {"action":"android.intent.action.SEND","type":"text/plain","extras":{"android.intent.extra.TEXT":"Hello world"}}
 *   Deep link: {"action":"android.intent.action.VIEW","uri":"tg://resolve?domain=yangfourier"}
 */
public class IntentHandler implements RouteHandler {

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if (!"POST".equalsIgnoreCase(method)) {
            return "{\"error\":\"use POST\"}";
        }

        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        String action = req.optString("action", "");
        if (action.isEmpty()) {
            return "{\"error\":\"provide 'action' (e.g. android.intent.action.VIEW)\"}";
        }

        Intent intent = new Intent(action);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // URI
        String uri = req.optString("uri", "");
        if (!uri.isEmpty()) {
            intent.setData(Uri.parse(uri));
        }

        // MIME type
        String type = req.optString("type", "");
        if (!type.isEmpty()) {
            if (!uri.isEmpty()) {
                intent.setDataAndType(Uri.parse(uri), type);
            } else {
                intent.setType(type);
            }
        }

        // Package (target specific app)
        String pkg = req.optString("package", "");
        if (!pkg.isEmpty()) {
            intent.setPackage(pkg);
        }

        // Component (specific activity)
        String component = req.optString("component", "");
        if (!component.isEmpty()) {
            String[] parts = component.split("/");
            if (parts.length == 2) {
                intent.setClassName(parts[0], parts[1]);
            }
        }

        // Categories
        if (req.has("category")) {
            intent.addCategory(req.getString("category"));
        }

        // Extras
        if (req.has("extras")) {
            JSONObject extras = req.getJSONObject("extras");
            Iterator<String> keys = extras.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = extras.get(key);
                if (val instanceof String) {
                    intent.putExtra(key, (String) val);
                } else if (val instanceof Integer) {
                    intent.putExtra(key, (int) val);
                } else if (val instanceof Long) {
                    intent.putExtra(key, (long) val);
                } else if (val instanceof Boolean) {
                    intent.putExtra(key, (boolean) val);
                } else if (val instanceof Double) {
                    intent.putExtra(key, (double) val);
                } else {
                    intent.putExtra(key, val.toString());
                }
            }
        }

        // Launch via AccessibilityService context if available (higher privilege)
        Context launchContext;
        AccessibilityBridge bridge = AccessibilityBridge.getInstance();
        launchContext = bridge != null ? bridge : null;

        if (launchContext == null) {
            return "{\"error\":\"accessibility service not running, cannot launch intent\"}";
        }

        try {
            launchContext.startActivity(intent);
            JSONObject result = new JSONObject();
            result.put("sent", true);
            result.put("action", action);
            if (!uri.isEmpty()) result.put("uri", uri);
            if (!pkg.isEmpty()) result.put("package", pkg);
            return result.toString();
        } catch (Exception e) {
            return new JSONObject()
                    .put("error", "intent failed: " + e.getMessage())
                    .put("action", action)
                    .toString();
        }
    }
}
