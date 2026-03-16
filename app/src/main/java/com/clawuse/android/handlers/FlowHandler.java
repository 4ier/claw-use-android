package com.clawuse.android.handlers;

import android.graphics.Rect;
import android.os.SystemClock;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;

import com.clawuse.android.AccessibilityBridge;
import com.clawuse.android.SafeA11y;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * Phone-side scripted automation engine.
 *
 * Executes a sequence of wait→act steps locally on the device with zero LLM calls.
 * The a11y tree is polled at high frequency (configurable, default 100ms) to detect
 * UI state changes and react immediately.
 *
 * <h3>API</h3>
 * <pre>
 * POST /flow
 * {
 *   "steps": [
 *     {"wait": "继续安装", "then": "tap", "timeout": 10000},
 *     {"wait": "继续更新", "then": "tap", "timeout": 10000},
 *     {"wait": "完成",     "then": "tap", "timeout": 60000, "optional": true}
 *   ],
 *   "pollMs": 100       // polling interval (default 100ms)
 * }
 * </pre>
 *
 * <h3>Step fields</h3>
 * <ul>
 *   <li>{@code wait} — text to find in a11y tree (case-insensitive partial match)</li>
 *   <li>{@code waitId} — resource ID to find (alternative to text)</li>
 *   <li>{@code waitDesc} — content description to find</li>
 *   <li>{@code waitGone} — wait for this text to DISAPPEAR</li>
 *   <li>{@code then} — action: "tap", "click", "longpress", "back", "home", "none"</li>
 *   <li>{@code timeout} — per-step timeout in ms (default 10000)</li>
 *   <li>{@code optional} — if true, timeout doesn't fail the flow (default false)</li>
 *   <li>{@code pauseMs} — pause after action before next step (default 500)</li>
 * </ul>
 *
 * Response:
 * <pre>
 * {
 *   "completed": true,
 *   "stepsRun": 3,
 *   "results": [
 *     {"step": 0, "found": "继续安装", "action": "tap", "x": 610, "y": 2100, "elapsedMs": 1234},
 *     {"step": 1, "found": "继续更新", "action": "tap", "x": 610, "y": 2100, "elapsedMs": 567},
 *     {"step": 2, "skipped": true, "reason": "timeout (optional)"}
 *   ],
 *   "totalMs": 12345
 * }
 * </pre>
 */
public class FlowHandler implements RouteHandler {
    private static final String TAG = "FlowHandler";
    private static final int DEFAULT_POLL_MS = 100;
    private static final int DEFAULT_STEP_TIMEOUT = 10_000;
    private static final int DEFAULT_PAUSE_MS = 500;
    private static final int MAX_TOTAL_TIMEOUT = 300_000; // 5 min absolute max
    private static final int A11Y_TIMEOUT = 3000;

    private final AccessibilityBridge bridge;

    public FlowHandler(AccessibilityBridge bridge) {
        this.bridge = bridge;
    }

    private AccessibilityBridge getBridge() {
        return bridge != null ? bridge : AccessibilityBridge.getInstance();
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if (!"POST".equals(method)) {
            return "{\"error\":\"POST required\"}";
        }

        AccessibilityBridge b = getBridge();
        if (b == null) {
            return "{\"error\":\"accessibility service not running\"}";
        }

        JSONObject req = new JSONObject(body);
        JSONArray steps = req.getJSONArray("steps");
        int pollMs = req.optInt("pollMs", DEFAULT_POLL_MS);
        pollMs = Math.max(50, Math.min(pollMs, 2000)); // clamp 50-2000ms

        JSONArray results = new JSONArray();
        long flowStart = SystemClock.elapsedRealtime();
        boolean allCompleted = true;

        for (int i = 0; i < steps.length(); i++) {
            JSONObject step = steps.getJSONObject(i);
            long stepStart = SystemClock.elapsedRealtime();

            // Guard against runaway flows
            if (stepStart - flowStart > MAX_TOTAL_TIMEOUT) {
                JSONObject r = new JSONObject();
                r.put("step", i);
                r.put("error", "flow timeout exceeded (5 min max)");
                results.put(r);
                allCompleted = false;
                break;
            }

            JSONObject result = executeStep(b, step, i, pollMs);
            results.put(result);

            if (result.has("error") && !step.optBoolean("optional", false)) {
                allCompleted = false;
                break;
            }

            // Pause between steps
            int pauseMs = step.optInt("pauseMs", DEFAULT_PAUSE_MS);
            if (pauseMs > 0 && i < steps.length() - 1) {
                Thread.sleep(pauseMs);
            }
        }

        long totalMs = SystemClock.elapsedRealtime() - flowStart;

        JSONObject response = new JSONObject();
        response.put("completed", allCompleted);
        response.put("stepsRun", results.length());
        response.put("results", results);
        response.put("totalMs", totalMs);
        return response.toString();
    }

    private JSONObject executeStep(AccessibilityBridge b, JSONObject step, int index, int pollMs) throws Exception {
        JSONObject result = new JSONObject();
        result.put("step", index);

        String waitText = step.optString("wait", "");
        String waitId = step.optString("waitId", "");
        String waitDesc = step.optString("waitDesc", "");
        String waitGone = step.optString("waitGone", "");
        String action = step.optString("then", "tap");
        int timeout = step.optInt("timeout", DEFAULT_STEP_TIMEOUT);
        boolean optional = step.optBoolean("optional", false);

        boolean isWaitGone = !waitGone.isEmpty();
        String searchTerm = isWaitGone ? waitGone : (!waitText.isEmpty() ? waitText : (!waitDesc.isEmpty() ? waitDesc : waitId));

        if (searchTerm.isEmpty() && !isWaitGone) {
            result.put("error", "step must have 'wait', 'waitId', 'waitDesc', or 'waitGone'");
            return result;
        }

        long deadline = SystemClock.elapsedRealtime() + timeout;
        AccessibilityNodeInfo found = null;

        // Poll loop — this is the fast local feedback loop
        while (SystemClock.elapsedRealtime() < deadline) {
            AccessibilityNodeInfo root = null;
            try {
                root = SafeA11y.getRootSafe(b, A11Y_TIMEOUT);
                if (root == null) {
                    Thread.sleep(pollMs);
                    continue;
                }

                if (isWaitGone) {
                    // Wait for element to disappear
                    AccessibilityNodeInfo node = SafeA11y.findByTextSafe(b, root, waitGone, A11Y_TIMEOUT);
                    if (node == null) {
                        // Gone! Success
                        long elapsed = SystemClock.elapsedRealtime() - (deadline - timeout);
                        result.put("gone", waitGone);
                        result.put("action", "none");
                        result.put("elapsedMs", elapsed);
                        return result;
                    }
                    node.recycle();
                } else {
                    // Wait for element to appear
                    AccessibilityNodeInfo node = null;
                    if (!waitText.isEmpty()) {
                        node = SafeA11y.findByTextSafe(b, root, waitText, A11Y_TIMEOUT);
                    } else if (!waitDesc.isEmpty()) {
                        node = SafeA11y.findByDescSafe(b, root, waitDesc, A11Y_TIMEOUT);
                    } else if (!waitId.isEmpty()) {
                        node = SafeA11y.findByIdSafe(b, root, waitId, A11Y_TIMEOUT);
                    }

                    if (node != null) {
                        found = node;
                        break; // Found it, proceed to action
                    }
                }
            } finally {
                if (root != null && found == null) {
                    try { root.recycle(); } catch (Exception ignored) {}
                }
            }

            Thread.sleep(pollMs);
        }

        if (isWaitGone) {
            // Timed out waiting for element to disappear
            if (optional) {
                result.put("skipped", true);
                result.put("reason", "timeout waiting for '" + waitGone + "' to disappear (optional)");
            } else {
                result.put("error", "timeout waiting for '" + waitGone + "' to disappear");
            }
            return result;
        }

        if (found == null) {
            if (optional) {
                result.put("skipped", true);
                result.put("reason", "timeout (optional)");
            } else {
                result.put("error", "timeout waiting for '" + searchTerm + "'");
            }
            return result;
        }

        // Execute action
        long elapsed = SystemClock.elapsedRealtime() - (deadline - timeout);
        Rect bounds = new Rect();
        found.getBoundsInScreen(bounds);

        try {
            switch (action.toLowerCase()) {
                case "tap":
                    b.tap(bounds.centerX(), bounds.centerY());
                    result.put("found", searchTerm);
                    result.put("action", "tap");
                    result.put("x", bounds.centerX());
                    result.put("y", bounds.centerY());
                    break;

                case "click":
                    b.clickNode(found);
                    result.put("found", searchTerm);
                    result.put("action", "click");
                    result.put("x", bounds.centerX());
                    result.put("y", bounds.centerY());
                    break;

                case "longpress":
                    int duration = step.optInt("durationMs", 1000);
                    b.longPress(bounds.centerX(), bounds.centerY(), duration);
                    result.put("found", searchTerm);
                    result.put("action", "longpress");
                    result.put("x", bounds.centerX());
                    result.put("y", bounds.centerY());
                    break;

                case "back":
                    b.doGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
                    result.put("found", searchTerm);
                    result.put("action", "back");
                    break;

                case "home":
                    b.doGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
                    result.put("found", searchTerm);
                    result.put("action", "home");
                    break;

                case "none":
                    result.put("found", searchTerm);
                    result.put("action", "none");
                    result.put("x", bounds.centerX());
                    result.put("y", bounds.centerY());
                    break;

                default:
                    result.put("error", "unknown action: " + action);
                    break;
            }
        } finally {
            found.recycle();
        }

        result.put("elapsedMs", elapsed);
        return result;
    }
}
