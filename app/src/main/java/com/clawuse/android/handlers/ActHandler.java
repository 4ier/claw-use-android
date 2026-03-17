package com.clawuse.android.handlers;

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
 * Unified action endpoint: POST /act
 *
 * Supports all operations through a single entry point:
 *   {"click": [3, 5, 8]}         → click refs in sequence
 *   {"click": "继续"}            → click by text (fallback)
 *   {"tap": {"x": 540, "y": 960}} → coordinate tap
 *   {"type": "hello"}             → type into focused field
 *   {"type": {"ref": 3, "text": "hello"}} → focus ref then type
 *   {"swipe": "up"}               → directional swipe
 *   {"swipe": {"x1":..., "y1":..., "x2":..., "y2":...}} → raw swipe
 *   {"back": true}                → global back
 *   {"home": true}                → global home
 *   {"scroll": "down"}            → scroll nearest scrollable
 *   {"longpress": 3}              → long press ref
 *   {"launch": "com.duolingo"}    → launch app
 *
 * Multiple actions in one request are executed in order.
 * Response includes results for each action.
 */
public class ActHandler implements RouteHandler {
    private static final int A11Y_TIMEOUT = 3000;
    private final AccessibilityBridge bridge;

    public ActHandler(AccessibilityBridge bridge) {
        this.bridge = bridge;
    }

    private AccessibilityBridge getBridge() {
        return bridge != null ? bridge : AccessibilityBridge.getInstance();
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        AccessibilityBridge b = getBridge();
        if (b == null) return "{\"error\":\"accessibility service not running\"}";

        JSONObject req = (body != null && !body.isEmpty()) ? new JSONObject(body) : new JSONObject();
        JSONArray results = new JSONArray();

        // Process each action type present in the request
        if (req.has("click")) {
            results.put(handleClick(b, req.get("click")));
        }
        if (req.has("tap")) {
            results.put(handleTap(b, req.get("tap")));
        }
        if (req.has("type")) {
            results.put(handleType(b, req.get("type")));
        }
        if (req.has("swipe")) {
            results.put(handleSwipe(b, req.get("swipe")));
        }
        if (req.has("scroll")) {
            results.put(handleScroll(b, req.getString("scroll")));
        }
        if (req.has("back")) {
            boolean ok = b.doGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            results.put(new JSONObject().put("back", ok));
        }
        if (req.has("home")) {
            boolean ok = b.doGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME);
            results.put(new JSONObject().put("home", ok));
        }
        if (req.has("recents")) {
            boolean ok = b.doGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS);
            results.put(new JSONObject().put("recents", ok));
        }
        if (req.has("longpress")) {
            results.put(handleLongPress(b, req.get("longpress")));
        }
        if (req.has("launch")) {
            results.put(handleLaunch(b, req.getString("launch")));
        }

        // If single action, return its result directly; else return array
        if (results.length() == 1) {
            return results.getJSONObject(0).toString();
        }
        return new JSONObject().put("results", results).toString();
    }

    // ── Click ───────────────────────────────────────────────────

    private JSONObject handleClick(AccessibilityBridge b, Object clickVal) throws Exception {
        JSONObject result = new JSONObject();

        if (clickVal instanceof JSONArray) {
            // Click multiple refs in sequence: {"click": [3, 5, 8]}
            JSONArray refs = (JSONArray) clickVal;
            JSONArray clicked = new JSONArray();
            for (int i = 0; i < refs.length(); i++) {
                int ref = refs.getInt(i);
                clicked.put(clickRef(b, ref));
                if (i < refs.length() - 1) Thread.sleep(150); // brief pause between clicks
            }
            result.put("clicked", clicked);
        } else if (clickVal instanceof Number) {
            // Click single ref: {"click": 3}
            result = clickRef(b, ((Number) clickVal).intValue());
        } else if (clickVal instanceof String) {
            // Click by text (fallback): {"click": "继续"}
            result = clickByText(b, (String) clickVal);
        } else {
            result.put("error", "click value must be ref (int), ref array, or text string");
        }
        return result;
    }

    private JSONObject clickRef(AccessibilityBridge b, int ref) throws Exception {
        // First try ref resolution from last /screen call
        ScreenHandler.RefEntry entry = ScreenHandler.resolveRefEntry(ref);
        if (entry != null) {
            boolean ok = b.tap(entry.cx, entry.cy);
            JSONObject r = new JSONObject().put("ref", ref).put("ok", ok)
                    .put("x", entry.cx).put("y", entry.cy);
            if (entry.text != null) r.put("text", entry.text);
            if (entry.desc != null) r.put("desc", entry.desc);
            return r;
        }
        return new JSONObject().put("ref", ref).put("error", "ref not found, call /screen first");
    }

    private JSONObject clickByText(AccessibilityBridge b, String text) throws Exception {
        AccessibilityNodeInfo root = SafeA11y.getRootSafe(b, A11Y_TIMEOUT);
        if (root == null) return new JSONObject().put("error", "no active window");

        try {
            AccessibilityNodeInfo target = SafeA11y.findByTextSafe(b, root, text, A11Y_TIMEOUT);
            if (target == null) {
                // Try desc
                target = SafeA11y.findByDescSafe(b, root, text, A11Y_TIMEOUT);
            }
            if (target != null) {
                Rect bounds = new Rect();
                target.getBoundsInScreen(bounds);
                boolean ok = b.clickNode(target);
                target.recycle();
                return new JSONObject().put("ok", ok).put("text", text)
                        .put("x", bounds.centerX()).put("y", bounds.centerY());
            }
            return new JSONObject().put("error", "not found").put("text", text);
        } finally {
            root.recycle();
        }
    }

    // ── Tap ─────────────────────────────────────────────────────

    private JSONObject handleTap(AccessibilityBridge b, Object tapVal) throws Exception {
        if (tapVal instanceof JSONObject) {
            JSONObject t = (JSONObject) tapVal;
            int x = t.getInt("x"), y = t.getInt("y");
            return new JSONObject().put("ok", b.tap(x, y)).put("x", x).put("y", y);
        }
        return new JSONObject().put("error", "tap requires {x, y}");
    }

    // ── Type ────────────────────────────────────────────────────

    private JSONObject handleType(AccessibilityBridge b, Object typeVal) throws Exception {
        String text;
        int targetRef = -1;

        if (typeVal instanceof String) {
            text = (String) typeVal;
        } else if (typeVal instanceof JSONObject) {
            JSONObject t = (JSONObject) typeVal;
            text = t.getString("text");
            targetRef = t.optInt("ref", -1);
        } else {
            return new JSONObject().put("error", "type requires string or {ref, text}");
        }

        // If ref specified, tap it first to focus
        if (targetRef > 0) {
            ScreenHandler.RefEntry entry = ScreenHandler.resolveRefEntry(targetRef);
            if (entry != null) {
                b.tap(entry.cx, entry.cy);
                Thread.sleep(300);
            }
        }

        // Clipboard paste
        final String finalText = text;
        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] success = {false};

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) b.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("claw", finalText));
                AccessibilityNodeInfo root = SafeA11y.getRootSafe(b, A11Y_TIMEOUT);
                if (root != null) {
                    AccessibilityNodeInfo focused = SafeA11y.run(() -> findFocusedEditable(root), A11Y_TIMEOUT);
                    if (focused != null) {
                        focused.performAction(AccessibilityNodeInfo.ACTION_PASTE);
                        success[0] = true;
                        focused.recycle();
                    }
                    root.recycle();
                }
            } catch (Exception ignored) {}
            latch.countDown();
        });

        latch.await(5, TimeUnit.SECONDS);
        return new JSONObject().put("typed", success[0]).put("text", text);
    }

    // ── Swipe ───────────────────────────────────────────────────

    private JSONObject handleSwipe(AccessibilityBridge b, Object swipeVal) throws Exception {
        int x1, y1, x2, y2;
        long dur = 300;

        if (swipeVal instanceof String) {
            // Direction: "up", "down", "left", "right"
            android.util.DisplayMetrics dm = b.getResources().getDisplayMetrics();
            int w = dm.widthPixels, h = dm.heightPixels;
            int cx = w / 2, cy = h / 2, m = Math.min(w, h) / 4;
            switch (((String) swipeVal).toLowerCase()) {
                case "up":    x1=cx; y1=cy+m; x2=cx; y2=cy-m; break;
                case "down":  x1=cx; y1=cy-m; x2=cx; y2=cy+m; break;
                case "left":  x1=cx+m; y1=cy; x2=cx-m; y2=cy; break;
                case "right": x1=cx-m; y1=cy; x2=cx+m; y2=cy; break;
                default: return new JSONObject().put("error", "direction: up/down/left/right");
            }
        } else if (swipeVal instanceof JSONObject) {
            JSONObject s = (JSONObject) swipeVal;
            x1 = s.getInt("x1"); y1 = s.getInt("y1");
            x2 = s.getInt("x2"); y2 = s.getInt("y2");
            dur = s.optLong("duration", 300);
        } else {
            return new JSONObject().put("error", "swipe requires direction string or {x1,y1,x2,y2}");
        }

        boolean ok = b.swipe(x1, y1, x2, y2, dur);
        return new JSONObject().put("swiped", ok)
                .put("from", x1+","+y1).put("to", x2+","+y2);
    }

    // ── Scroll ──────────────────────────────────────────────────

    private JSONObject handleScroll(AccessibilityBridge b, String direction) throws Exception {
        AccessibilityNodeInfo root = SafeA11y.getRootSafe(b, A11Y_TIMEOUT);
        if (root == null) return new JSONObject().put("error", "no active window");

        try {
            AccessibilityNodeInfo scrollable = SafeA11y.run(() -> findFirstScrollable(root), A11Y_TIMEOUT);
            if (scrollable == null) {
                return new JSONObject().put("error", "no scrollable container");
            }
            int action = "up".equals(direction) || "left".equals(direction)
                    ? AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    : AccessibilityNodeInfo.ACTION_SCROLL_FORWARD;
            boolean ok = scrollable.performAction(action);
            scrollable.recycle();
            return new JSONObject().put("scrolled", ok).put("direction", direction);
        } finally {
            root.recycle();
        }
    }

    // ── Long Press ──────────────────────────────────────────────

    private JSONObject handleLongPress(AccessibilityBridge b, Object val) throws Exception {
        int x, y;
        long dur = 1000;

        if (val instanceof Number) {
            int ref = ((Number) val).intValue();
            int[] pos = ScreenHandler.resolveRef(ref);
            if (pos == null) return new JSONObject().put("error", "ref not found");
            x = pos[0]; y = pos[1];
        } else if (val instanceof JSONObject) {
            JSONObject j = (JSONObject) val;
            x = j.getInt("x"); y = j.getInt("y");
            dur = j.optLong("duration", 1000);
        } else {
            return new JSONObject().put("error", "longpress requires ref or {x,y}");
        }

        boolean ok = b.longPress(x, y, dur);
        return new JSONObject().put("longPressed", ok).put("x", x).put("y", y);
    }

    // ── Launch ──────────────────────────────────────────────────

    private JSONObject handleLaunch(AccessibilityBridge b, String pkg) throws Exception {
        try {
            android.content.Intent intent = b.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent == null) {
                return new JSONObject().put("error", "package not found").put("package", pkg);
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            b.startActivity(intent);
            return new JSONObject().put("launched", true).put("package", pkg);
        } catch (Exception e) {
            return new JSONObject().put("error", e.getMessage());
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
                if (found != null) { if (found != child) child.recycle(); return found; }
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
                if (found != null) { if (found != child) child.recycle(); return found; }
                child.recycle();
            }
        }
        return null;
    }
}
