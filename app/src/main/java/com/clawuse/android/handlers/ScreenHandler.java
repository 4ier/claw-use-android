package com.clawuse.android.handlers;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.clawuse.android.AccessibilityBridge;
import com.clawuse.android.SafeA11y;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles /screen and /screenshot endpoints.
 *
 * Tree traversal runs through SafeA11y (single-thread executor with timeout).
 * If the worker gets stuck on a pathological UI tree, subsequent requests
 * return a timeout error immediately — the HTTP server stays responsive.
 */
public class ScreenHandler implements RouteHandler {
    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private AccessibilityBridge bridge;

    /** Optional: set bridge instance directly (used in main process). */
    public void setBridge(AccessibilityBridge bridge) {
        this.bridge = bridge;
    }

    private AccessibilityBridge getBridge() {
        return bridge != null ? bridge : AccessibilityBridge.getInstance();
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if (path.startsWith("/screenshot")) {
            return handleScreenshot();
        }
        boolean compact = params.containsKey("compact");
        int timeoutMs = DEFAULT_TIMEOUT_MS;
        if (params.containsKey("timeout")) {
            try {
                timeoutMs = Integer.parseInt(params.get("timeout"));
                if (timeoutMs < 500) timeoutMs = 500;
                if (timeoutMs > 15000) timeoutMs = 15000;
            } catch (NumberFormatException ignored) {}
        }
        return getScreenJson(compact, timeoutMs);
    }

    private String getScreenJson(boolean compact, int timeoutMs) throws Exception {
        AccessibilityBridge b = getBridge();
        if (b == null) {
            return "{\"error\":\"accessibility service not running\",\"nodes\":[]}";
        }

        // Check if SafeA11y worker is already stuck
        if (SafeA11y.isWorkerBusy()) {
            return new JSONObject()
                    .put("error", "accessibility worker busy (previous request stuck)")
                    .put("nodes", new JSONArray())
                    .put("hint", "try again later or navigate away from current app")
                    .toString();
        }

        // Run entire tree read through SafeA11y's single-thread executor
        String result = SafeA11y.run(() -> {
            AccessibilityNodeInfo root = b.getRootNode();
            if (root == null) {
                return "{\"error\":\"no active window\",\"nodes\":[]}";
            }

            try {
                JSONObject json = new JSONObject();
                json.put("package", root.getPackageName());
                json.put("timestamp", System.currentTimeMillis());
                JSONArray nodes = new JSONArray();
                AtomicBoolean cancelled = new AtomicBoolean(false);
                traverseNode(root, nodes, 0, compact, cancelled);
                json.put("nodes", nodes);
                json.put("count", nodes.length());
                return json.toString();
            } finally {
                root.recycle();
            }
        }, timeoutMs);

        if (result == null) {
            return new JSONObject()
                    .put("error", "screen read timed out")
                    .put("nodes", new JSONArray())
                    .put("truncated", true)
                    .put("timeoutMs", timeoutMs)
                    .toString();
        }

        return result;
    }

    private void traverseNode(AccessibilityNodeInfo node, JSONArray nodes, int depth, boolean compact, AtomicBoolean cancelled) {
        if (node == null || cancelled.get() || Thread.currentThread().isInterrupted()) return;
        try {
            String text = node.getText() != null ? node.getText().toString() : "";
            String desc = node.getContentDescription() != null ? node.getContentDescription().toString() : "";
            String id = node.getViewIdResourceName() != null ? node.getViewIdResourceName() : "";
            String cls = node.getClassName() != null ? node.getClassName().toString() : "";
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            boolean hasContent = !text.isEmpty() || !desc.isEmpty() || node.isClickable()
                    || node.isEditable() || node.isScrollable() || node.isCheckable();

            if (!compact || hasContent) {
                JSONObject obj = new JSONObject();
                if (!text.isEmpty()) obj.put("text", text);
                if (!desc.isEmpty()) obj.put("desc", desc);
                if (!id.isEmpty()) obj.put("id", id);
                if (!compact) obj.put("cls", cls);
                obj.put("bounds", bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom);
                if (node.isClickable()) obj.put("click", true);
                if (node.isEditable()) obj.put("edit", true);
                if (node.isScrollable()) obj.put("scroll", true);
                if (node.isCheckable()) obj.put("checkable", true);
                if (node.isChecked()) obj.put("checked", true);
                if (node.isFocused()) obj.put("focused", true);
                if (node.isSelected()) obj.put("selected", true);
                if (!compact) obj.put("depth", depth);
                nodes.put(obj);
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) return;
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    traverseNode(child, nodes, depth + 1, compact, cancelled);
                    child.recycle();
                }
            }
        } catch (Exception ignored) {
            // Skip problematic nodes
        }
    }

    private String handleScreenshot() {
        return "{\"error\":\"screenshot not yet implemented\",\"hint\":\"coming in phase 2\"}";
    }
}
