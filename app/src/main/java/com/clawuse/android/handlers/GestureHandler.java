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
import com.clawuse.android.SafeA11y;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Handles gesture/interaction endpoints:
 * POST /click, /tap, /longpress, /swipe, /type, /scroll
 *
 * All accessibility tree operations go through SafeA11y for timeout protection.
 */
public class GestureHandler implements RouteHandler {
    private static final int A11Y_TIMEOUT = 3000;
    private final Context context;
    private final AccessibilityBridge bridge;

    public GestureHandler(Context context) {
        this.context = context;
        this.bridge = (context instanceof AccessibilityBridge) ? (AccessibilityBridge) context : null;
    }

    private AccessibilityBridge getBridge() {
        return bridge != null ? bridge : AccessibilityBridge.getInstance();
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        AccessibilityBridge b = getBridge();
        if (b == null) {
            return "{\"error\":\"accessibility service not running\"}";
        }

        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();

        switch (path) {
            case "/click":     return handleClick(b, req);
            case "/tap":       return handleTap(b, req);
            case "/longpress": return handleLongPress(b, req);
            case "/swipe":     return handleSwipe(b, req);
            case "/type":      return handleType(b, req);
            case "/scroll":    return handleScroll(b, req);
            default:           return "{\"error\":\"unknown gesture endpoint\"}";
        }
    }

    private String handleClick(AccessibilityBridge bridge, JSONObject req) throws Exception {
        String text = req.optString("text", "");
        String id = req.optString("id", "");
        String desc = req.optString("desc", "");
        int retry = req.optInt("retry", 0);
        int retryMs = req.optInt("retryMs", 1000);

        if (text.isEmpty() && id.isEmpty() && desc.isEmpty()) {
            return "{\"error\":\"provide 'text', 'id', or 'desc'\"}";
        }

        // Retry loop: poll for element to appear
        for (int attempt = 0; attempt <= retry; attempt++) {
            if (attempt > 0) Thread.sleep(retryMs);

            AccessibilityNodeInfo root = SafeA11y.getRootSafe(bridge, A11Y_TIMEOUT);
            if (root == null) continue;

            try {
                AccessibilityNodeInfo target = null;
                if (!text.isEmpty()) target = SafeA11y.findByTextSafe(bridge, root, text, A11Y_TIMEOUT);
                if (target == null && !id.isEmpty()) target = SafeA11y.findByIdSafe(bridge, root, id, A11Y_TIMEOUT);
                if (target == null && !desc.isEmpty()) target = SafeA11y.findByDescSafe(bridge, root, desc, A11Y_TIMEOUT);

                if (target != null) {
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
                    if (attempt > 0) result.put("attempts", attempt + 1);
                    return maybeVerify(result, req, bridge);
                }
            } finally {
                root.recycle();
            }
        }

        return "{\"error\":\"element not found\",\"text\":\"" + escapeJson(text) +
                "\",\"id\":\"" + escapeJson(id) + "\",\"desc\":\"" + escapeJson(desc) +
                "\",\"attempts\":" + (retry + 1) + "}";
    }

    private String handleTap(AccessibilityBridge bridge, JSONObject req) throws Exception {
        int x = req.getInt("x");
        int y = req.getInt("y");
        boolean tapped = bridge.tap(x, y);
        JSONObject result = new JSONObject()
                .put("tapped", tapped).put("x", x).put("y", y);
        return maybeVerify(result, req, bridge);
    }

    private String handleLongPress(AccessibilityBridge bridge, JSONObject req) throws Exception {
        long durationMs = req.optLong("durationMs", 1000);
        String text = req.optString("text", "");

        int x, y;
        if (!text.isEmpty()) {
            AccessibilityNodeInfo root = SafeA11y.getRootSafe(bridge, A11Y_TIMEOUT);
            if (root == null) return "{\"error\":\"no active window (timed out)\"}";
            AccessibilityNodeInfo target = SafeA11y.findByTextSafe(bridge, root, text, A11Y_TIMEOUT);
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
        String direction = req.optString("direction", "");
        long durationMs = req.optLong("durationMs", 300);
        
        int x1, y1, x2, y2;
        
        if (!direction.isEmpty()) {
            // Direction-based swipe: auto-calculate coordinates from screen size
            android.util.DisplayMetrics dm = bridge.getResources().getDisplayMetrics();
            int w = dm.widthPixels;
            int h = dm.heightPixels;
            int cx = w / 2;
            int cy = h / 2;
            int margin = Math.min(w, h) / 6; // swipe distance = ~1/3 of screen
            
            switch (direction.toLowerCase()) {
                case "up":    x1 = cx; y1 = cy + margin; x2 = cx; y2 = cy - margin; break;
                case "down":  x1 = cx; y1 = cy - margin; x2 = cx; y2 = cy + margin; break;
                case "left":  x1 = cx + margin; y1 = cy; x2 = cx - margin; y2 = cy; break;
                case "right": x1 = cx - margin; y1 = cy; x2 = cx + margin; y2 = cy; break;
                default:
                    return "{\"error\":\"direction must be up/down/left/right\"}";
            }
            
            // Optional startX/startY override (swipe from a specific point)
            if (req.has("startX")) { x1 = req.getInt("startX"); x2 = x1 + (x2 - cx); }
            if (req.has("startY")) { y1 = req.getInt("startY"); y2 = y1 + (y2 - cy); }
        } else {
            // Raw coordinate swipe
            x1 = req.getInt("x1");
            y1 = req.getInt("y1");
            x2 = req.getInt("x2");
            y2 = req.getInt("y2");
        }

        boolean swiped = bridge.swipe(x1, y1, x2, y2, durationMs);
        JSONObject result = new JSONObject()
                .put("swiped", swiped)
                .put("from", x1 + "," + y1)
                .put("to", x2 + "," + y2)
                .put("durationMs", durationMs);
        return maybeVerify(result, req, bridge);
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

                // Find focused editable node and paste (with timeout protection)
                AccessibilityNodeInfo root = SafeA11y.getRootSafe(bridge, A11Y_TIMEOUT);
                if (root != null) {
                    AccessibilityNodeInfo focused = SafeA11y.run(
                            () -> findFocusedEditable(root), A11Y_TIMEOUT);
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

        latch.await(5, TimeUnit.SECONDS);
        return new JSONObject()
                .put("typed", success[0]).put("text", text).put("method", "clipboard_paste").toString();
    }

    private String handleScroll(AccessibilityBridge bridge, JSONObject req) throws Exception {
        String direction = req.optString("direction", "down");
        String targetText = req.optString("text", "");

        AccessibilityNodeInfo root = SafeA11y.getRootSafe(bridge, A11Y_TIMEOUT);
        if (root == null) return "{\"error\":\"no active window (timed out)\"}";

        try {
            // Find scrollable container
            AccessibilityNodeInfo scrollable = null;
            if (!targetText.isEmpty()) {
                scrollable = SafeA11y.findByTextSafe(bridge, root, targetText, A11Y_TIMEOUT);
                if (scrollable != null && !scrollable.isScrollable()) {
                    scrollable.recycle();
                    scrollable = null;
                }
            }
            if (scrollable == null) {
                scrollable = SafeA11y.run(() -> findFirstScrollable(root), A11Y_TIMEOUT);
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

    /**
     * Get post-action state for verification. Depth ≤ 2 traversal, 1500ms timeout.
     * Returns a JSONObject with {package, topTexts} or null on failure.
     */
    private JSONObject getPostState(AccessibilityBridge bridge) {
        try {
            String stateJson = SafeA11y.run(() -> {
                return ScreenHandler.buildQuickState(bridge, 2, 10, 0);
            }, 1500);
            if (stateJson == null) return null;
            JSONObject full = new JSONObject(stateJson);
            JSONObject post = new JSONObject();
            post.put("package", full.optString("package", ""));
            post.put("topTexts", full.optJSONArray("topTexts"));
            return post;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * If verify is requested, sleep 300ms then attach post-action state to result.
     */
    private String maybeVerify(JSONObject result, JSONObject req, AccessibilityBridge bridge) throws Exception {
        if (req.optBoolean("verify", false)) {
            Thread.sleep(300);
            JSONObject post = getPostState(bridge);
            if (post != null) {
                result.put("post", post);
            }
        }
        return result.toString();
    }

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
