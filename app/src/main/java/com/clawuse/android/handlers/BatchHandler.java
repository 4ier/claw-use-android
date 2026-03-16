package com.clawuse.android.handlers;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Handles POST /batch — executes multiple operations in sequence with a single HTTP round trip.
 *
 * Delegates to existing handlers for each operation. If any op fails, the error is captured
 * in results but remaining ops continue executing.
 *
 * Runs in the main process (a11y server side).
 */
public class BatchHandler implements RouteHandler {
    private static final String TAG = "ClawBatch";
    private static final int MAX_OPS = 20;
    private static final int MAX_SLEEP_MS = 5000;
    private static final long MAX_BATCH_TIMEOUT_MS = 60000;

    private final ScreenHandler screenHandler;
    private final GestureHandler gestureHandler;
    private final GlobalHandler globalHandler;

    public BatchHandler(ScreenHandler screenHandler, GestureHandler gestureHandler, GlobalHandler globalHandler) {
        this.screenHandler = screenHandler;
        this.gestureHandler = gestureHandler;
        this.globalHandler = globalHandler;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if (!"POST".equals(method)) {
            return "{\"error\":\"POST required\"}";
        }

        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        JSONArray ops = req.optJSONArray("ops");
        if (ops == null || ops.length() == 0) {
            return "{\"error\":\"provide 'ops' array\"}";
        }
        if (ops.length() > MAX_OPS) {
            return "{\"error\":\"max " + MAX_OPS + " ops per batch, got " + ops.length() + "\"}";
        }

        JSONArray results = new JSONArray();
        long batchStart = System.currentTimeMillis();

        for (int i = 0; i < ops.length(); i++) {
            // Check total batch timeout
            if (System.currentTimeMillis() - batchStart > MAX_BATCH_TIMEOUT_MS) {
                results.put(new JSONObject().put("ok", false).put("error", "batch timeout exceeded"));
                for (int j = i + 1; j < ops.length(); j++) {
                    results.put(new JSONObject().put("ok", false).put("error", "skipped: batch timeout"));
                }
                break;
            }

            JSONObject op = ops.getJSONObject(i);
            String action = op.optString("action", "");

            try {
                JSONObject result = executeOp(action, op);
                results.put(result);
            } catch (Exception e) {
                Log.w(TAG, "Batch op " + i + " (" + action + ") failed", e);
                results.put(new JSONObject().put("ok", false).put("error", e.getMessage()));
            }
        }

        long totalMs = System.currentTimeMillis() - batchStart;
        return new JSONObject()
                .put("results", results)
                .put("totalMs", totalMs)
                .toString();
    }

    private JSONObject executeOp(String action, JSONObject op) throws Exception {
        switch (action) {
            case "sleep":      return executeSleep(op);
            case "tap":        return delegateToGesture("/tap", op);
            case "click":      return delegateToGesture("/click", op);
            case "longpress":  return delegateToGesture("/longpress", op);
            case "swipe":      return delegateToGesture("/swipe", op);
            case "type":       return delegateToGesture("/type", op);
            case "global":     return delegateToGlobal(op);
            case "screen":     return delegateToScreen(op);
            case "screenshot": return delegateToScreenshot(op);
            default:
                return new JSONObject().put("ok", false).put("error", "unknown action: " + action);
        }
    }

    private JSONObject executeSleep(JSONObject op) throws Exception {
        int ms = op.optInt("ms", 1000);
        if (ms < 0) ms = 0;
        if (ms > MAX_SLEEP_MS) ms = MAX_SLEEP_MS;
        Thread.sleep(ms);
        return new JSONObject().put("ok", true);
    }

    /**
     * Build a handler body from the batch op, stripping the "action" key
     * which is used for routing and not relevant to individual handlers.
     */
    private JSONObject buildHandlerBody(JSONObject op) throws Exception {
        JSONObject body = new JSONObject();
        java.util.Iterator<String> keys = op.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (!"action".equals(key)) {
                body.put(key, op.get(key));
            }
        }
        return body;
    }

    private JSONObject delegateToGesture(String path, JSONObject op) throws Exception {
        JSONObject body = buildHandlerBody(op);
        String response = gestureHandler.handle("POST", path, emptyParams(), body.toString());
        return tagOk(new JSONObject(response));
    }

    private JSONObject delegateToGlobal(JSONObject op) throws Exception {
        // GlobalHandler expects "action" in body (e.g. "back", "home").
        // In batch, the top-level "action" is "global", so the real global action
        // must be passed as "globalAction" to avoid JSON key collision.
        // Example: {"action": "global", "globalAction": "back"}
        JSONObject body = buildHandlerBody(op);
        if (op.has("globalAction")) {
            body.put("action", op.getString("globalAction"));
        }
        String response = globalHandler.handle("POST", "/global", emptyParams(), body.toString());
        return tagOk(new JSONObject(response));
    }

    private JSONObject delegateToScreen(JSONObject op) throws Exception {
        Map<String, String> params = new HashMap<>();
        if (op.optBoolean("compact", false)) {
            params.put("compact", "true");
        }
        String response = screenHandler.handle("GET", "/screen", params, "");
        return tagOk(new JSONObject(response));
    }

    private JSONObject delegateToScreenshot(JSONObject op) throws Exception {
        Map<String, String> params = new HashMap<>();
        if (op.has("quality")) {
            params.put("quality", String.valueOf(op.getInt("quality")));
        }
        if (op.has("maxWidth")) {
            params.put("maxWidth", String.valueOf(op.getInt("maxWidth")));
        }
        String response = screenHandler.handle("GET", "/screenshot", params, "");
        return tagOk(new JSONObject(response));
    }

    /** Add "ok" field based on whether the result contains an error. */
    private JSONObject tagOk(JSONObject result) throws Exception {
        result.put("ok", !result.has("error"));
        return result;
    }

    private static Map<String, String> emptyParams() {
        return new HashMap<>();
    }
}
