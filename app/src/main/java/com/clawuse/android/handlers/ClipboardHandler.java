package com.clawuse.android.handlers;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * GET /clipboard — read clipboard text
 * POST /clipboard — write text to clipboard
 */
public class ClipboardHandler implements RouteHandler {
    private final Context context;

    public ClipboardHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if ("POST".equals(method)) {
            return writeClipboard(body);
        }
        return readClipboard();
    }

    private String readClipboard() throws Exception {
        final AtomicReference<String> result = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null && cm.hasPrimaryClip()) {
                    ClipData clip = cm.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence text = clip.getItemAt(0).coerceToText(context);
                        result.set(text != null ? text.toString() : "");
                    }
                }
            } catch (Exception ignored) {}
            latch.countDown();
        });

        latch.await(3, TimeUnit.SECONDS);
        String text = result.get();

        JSONObject json = new JSONObject();
        json.put("text", text != null ? text : "");
        json.put("hasContent", text != null && !text.isEmpty());
        return json.toString();
    }

    private String writeClipboard(String body) throws Exception {
        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        String text = req.optString("text", "");
        if (text.isEmpty()) return "{\"error\":\"text required\"}";

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Boolean> success = new AtomicReference<>(false);

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("claw-use", text));
                    success.set(true);
                }
            } catch (Exception ignored) {}
            latch.countDown();
        });

        latch.await(3, TimeUnit.SECONDS);
        return new JSONObject().put("copied", success.get()).toString();
    }
}
