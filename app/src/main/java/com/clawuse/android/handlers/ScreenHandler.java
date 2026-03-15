package com.clawuse.android.handlers;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import com.clawuse.android.AccessibilityBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * Handles /screen and /screenshot endpoints.
 * /screen returns the full UI tree as JSON. ?compact returns only interactive/text elements.
 * /screenshot returns a PNG screenshot (API 30+).
 */
public class ScreenHandler implements RouteHandler {

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if (path.startsWith("/screenshot")) {
            return handleScreenshot();
        }
        // /screen
        boolean compact = params.containsKey("compact");
        return getScreenJson(compact);
    }

    private String getScreenJson(boolean compact) throws Exception {
        AccessibilityBridge bridge = AccessibilityBridge.getInstance();
        if (bridge == null) {
            return "{\"error\":\"accessibility service not running\",\"nodes\":[]}";
        }

        AccessibilityNodeInfo root = bridge.getRootNode();
        if (root == null) {
            return "{\"error\":\"no active window\",\"nodes\":[]}";
        }

        try {
            JSONObject result = new JSONObject();
            result.put("package", root.getPackageName());
            result.put("timestamp", System.currentTimeMillis());
            JSONArray nodes = new JSONArray();
            traverseNode(root, nodes, 0, compact);
            result.put("nodes", nodes);
            result.put("count", nodes.length());
            return result.toString();
        } finally {
            root.recycle();
        }
    }

    private void traverseNode(AccessibilityNodeInfo node, JSONArray nodes, int depth, boolean compact) {
        if (node == null) return;
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
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    traverseNode(child, nodes, depth + 1, compact);
                    child.recycle();
                }
            }
        } catch (Exception ignored) {
            // Skip problematic nodes
        }
    }

    private String handleScreenshot() {
        // API 30+ takeScreenshot is async — for now return not-yet-implemented
        // Phase 2 will implement with CountDownLatch + callback
        return "{\"error\":\"screenshot not yet implemented\",\"hint\":\"coming in phase 2\"}";
    }
}
