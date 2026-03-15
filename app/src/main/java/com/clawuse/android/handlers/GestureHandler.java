package com.clawuse.android.handlers;

import android.accessibilityservice.AccessibilityService;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityNodeInfo;

import com.clawuse.android.AccessibilityBridge;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Handles gesture/interaction endpoints:
 * POST /click, /tap, /longpress, /swipe, /type, /scroll
 */
public class GestureHandler implements RouteHandler {
    private final Context context;

    public GestureHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        AccessibilityBridge bridge = AccessibilityBridge.getInstance();
        if (bridge == null) {
            return "{\"error\":\"accessibility service not running\"}";
        }

        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();

        switch (path) {
            case "/click":   return handleClick(bridge, req);
            case "/tap":     return handleTap(bridge, req);
            case "/longpress": return handleLongPress(bridge, req);
            case "/swipe":   return handleSwipe(bridge, req);
            case "/type":    return handleType(bridge, req);
            case "/scroll":  return handleScroll(bridge, req);
            default:         return "{\"error\":\"unknown gesture endpoint\"}";
        }
    }

    private String handleClick(AccessibilityBridge bridge, JSONObject req) throws Exception {
        String text = req.optString("text", "");
        String id = req.optString("id", "");
        String desc = req.optString("desc", "");

        if (text.isEmpty() && id.isEmpty() && desc.isEmpty()) {
            return "{\"error\":\"provide 'text', 'id', or 'desc'\"}";
        }

        AccessibilityNodeInfo root = bridge.getRootNode();
        if (root == null) return "{\"error\":\"no active window\"}";

        try {
            AccessibilityNodeInfo target = null;
            if (!text.isEmpty()) target = bridge.findByText(root, text);
            if (target == null && !id.isEmpty()) target = bridge.findById(root, id);
            if (target == null && !desc.isEmpty()) target = bridge.findByDesc(root, desc);

            if (target == null) {
                return "{\"error\":\"element not found\",\"text\":\"" + escapeJson(text) +
                        "\",\"id\":\"" + escapeJson(id) + "\",\"desc\":\"" + escapeJson(desc) + "\"}";
            }

            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            boolean clicked = bridge.clickNode(target);
            target.recycle();

            JSONObject result = new JSONObject();
            result.put("clicked", clicked);
            result.put("x", bounds.centerX());
            result.put("y", bounds.centerY());
            if (!text.isEmpty()) result.put("matchedText", text);
            if (!id.isEmpty()) result.put("matchedId", id);
            if (!desc.isEmpty()) result.put("matchedDesc", desc);
            return result.toString();
        } finally {
            root.recycle();
        }
    }

    private String handleTap(AccessibilityBridge bridge, JSONObject req) throws Exception {
        int x = req.getInt("x");
        int y = req.getInt("y");
        boolean tapped = bridge.tap(x, y);
        return new JSONObject()
                .put("tapped", tapped).put("x", x).put("y", y).toString();
    }

    private String handleLongPress(AccessibilityBridge bridge, JSONObject req) throws Exception {
        long durationMs = req.optLong("durationMs", 1000);
        String text = req.optString("text", "");

        int x, y;
        if (!text.isEmpty()) {
            AccessibilityNodeInfo root = bridge.getRootNode();
            if (root == null) return "{\"error\":\"no active window\"}";
            AccessibilityNodeInfo target = bridge.findByText(root, text);
            if (target == null) {
                root.recycle();
                return "{\"error\":\"element not found\"}";
            }
            Rect bounds = new Rect();
            target.getBoundsInScreen(bounds);
            x = bounds.centerX();
            y = bounds.centerY();
            target.recycle();
            root.recycle();
        } else {
            x = req.getInt("x");
            y = req.getInt("y");
        }

        boolean pressed = bridge.longPress(x, y, durationMs);
        return new JSONObject()
                .put("longPressed", pressed).put("x", x).put("y", y)
                .put("durationMs", durationMs).toString();
    }

    private String handleSwipe(AccessibilityBridge bridge, JSONObject req) throws Exception {
        int x1 = req.getInt("x1");
        int y1 = req.getInt("y1");
        int x2 = req.getInt("x2");
        int y2 = req.getInt("y2");
        long durationMs = req.optLong("durationMs", 300);

        boolean swiped = bridge.swipe(x1, y1, x2, y2, durationMs);
        return new JSONObject()
                .put("swiped", swiped)
                .put("from", x1 + "," + y1)
                .put("to", x2 + "," + y2)
                .put("durationMs", durationMs).toString();
    }

    private String handleType(AccessibilityBridge bridge, JSONObject req) throws Exception {
        String text = req.optString("text", "");
        if (text.isEmpty()) return "{\"error\":\"provide 'text'\"}";

        // Use clipboard paste for reliability (works with all IMEs and languages)
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("claw-type", text);
                clipboard.setPrimaryClip(clip);

                // Find focused editable node and paste
                AccessibilityNodeInfo root = bridge.getRootNode();
                if (root != null) {
                    AccessibilityNodeInfo focused = findFocusedEditable(root);
                    if (focused != null) {
                        focused.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                        success[0] = true;
                        focused.recycle();
                    }
                    root.recycle();
                }
            } catch (Exception e) {
                // ignore
            }
            latch.countDown();
        });

        latch.await(3, TimeUnit.SECONDS);
        return new JSONObject()
                .put("typed", success[0]).put("text", text).put("method", "clipboard_paste").toString();
    }

    private String handleScroll(AccessibilityBridge bridge, JSONObject req) throws Exception {
        String direction = req.optString("direction", "down");
        String targetText = req.optString("text", "");

        AccessibilityNodeInfo root = bridge.getRootNode();
        if (root == null) return "{\"error\":\"no active window\"}";

        try {
            // Find scrollable container
            AccessibilityNodeInfo scrollable = null;
            if (!targetText.isEmpty()) {
                scrollable = bridge.findByText(root, targetText);
                if (scrollable != null && !scrollable.isScrollable()) {
                    scrollable.recycle();
                    scrollable = null;
                }
            }
            if (scrollable == null) {
                scrollable = findFirstScrollable(root);
            }

            if (scrollable == null) {
                return "{\"error\":\"no scrollable container found\"}";
            }

            int action = direction.equals("up") || direction.equals("left")
                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;

            boolean scrolled = scrollable.performAction(action);
            scrollable.recycle();

            return new JSONObject()
                    .put("scrolled", scrolled).put("direction", direction).toString();
        } finally {
            root.recycle();
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private AccessibilityNodeInfo findFocusedEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isFocused() && node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findFocusedEditable(child);
                if (found != null) {
                    if (found != child) child.recycle();
                    return found;
                }
                child.recycle();
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findFirstScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                AccessibilityNodeInfo found = findFirstScrollable(child);
                if (found != null) {
                    if (found != child) child.recycle();
                    return found;
                }
                child.recycle();
            }
        }
        return null;
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }
}
