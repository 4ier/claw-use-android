package com.clawuse.android.handlers;

import android.app.Notification;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;

import com.clawuse.android.NotificationBridge;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Map;

/**
 * Handles notification endpoints:
 * GET  /notifications         — list all notifications
 * POST /notifications/click   — click a notification by key
 * POST /notifications/dismiss — dismiss a notification by key
 * POST /notifications/dismiss-all — dismiss all
 */
public class NotificationHandler implements RouteHandler {

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        NotificationBridge bridge = NotificationBridge.getInstance();

        if (path.equals("/notifications")) {
            if (bridge == null || !NotificationBridge.isRunning()) {
                return new JSONObject()
                        .put("error", "notification listener not running")
                        .put("hint", "enable Notification Listener in Settings for Claw Use")
                        .put("notifications", new JSONArray())
                        .toString();
            }

            List<StatusBarNotification> notifications = bridge.getNotifications();
            JSONArray arr = new JSONArray();
            for (StatusBarNotification sbn : notifications) {
                JSONObject n = new JSONObject();
                n.put("key", sbn.getKey());
                n.put("package", sbn.getPackageName());
                n.put("postTime", sbn.getPostTime());
                n.put("isOngoing", sbn.isOngoing());
                n.put("id", sbn.getId());

                Notification notif = sbn.getNotification();
                Bundle extras = notif.extras;
                if (extras != null) {
                    CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE);
                    CharSequence text = extras.getCharSequence(Notification.EXTRA_TEXT);
                    CharSequence subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
                    CharSequence bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

                    if (title != null) n.put("title", title.toString());
                    if (text != null) n.put("text", text.toString());
                    if (subText != null) n.put("subText", subText.toString());
                    if (bigText != null) n.put("bigText", bigText.toString());
                }

                // Actions
                if (notif.actions != null && notif.actions.length > 0) {
                    JSONArray actions = new JSONArray();
                    for (int i = 0; i < notif.actions.length; i++) {
                        JSONObject a = new JSONObject();
                        a.put("index", i);
                        a.put("title", notif.actions[i].title.toString());
                        actions.put(a);
                    }
                    n.put("actions", actions);
                }

                arr.put(n);
            }

            return new JSONObject()
                    .put("notifications", arr)
                    .put("count", arr.length())
                    .toString();
        }

        if (path.equals("/notifications/dismiss-all")) {
            if (bridge == null) return "{\"error\":\"notification listener not running\"}";
            bridge.dismissAll();
            return "{\"dismissed\":\"all\"}";
        }

        // POST with key
        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        String key = req.optString("key", "");
        if (key.isEmpty()) {
            return "{\"error\":\"provide 'key' from GET /notifications\"}";
        }

        if (path.equals("/notifications/dismiss")) {
            if (bridge == null) return "{\"error\":\"notification listener not running\"}";
            bridge.dismissNotification(key);
            return new JSONObject().put("dismissed", key).toString();
        }

        if (path.equals("/notifications/click")) {
            if (bridge == null) return "{\"error\":\"notification listener not running\"}";
            // Find the notification and fire its contentIntent
            List<StatusBarNotification> notifications = bridge.getNotifications();
            for (StatusBarNotification sbn : notifications) {
                if (sbn.getKey().equals(key)) {
                    Notification notif = sbn.getNotification();
                    if (notif.contentIntent != null) {
                        notif.contentIntent.send();
                        return new JSONObject().put("clicked", key).toString();
                    }
                    return new JSONObject().put("error", "notification has no content intent").toString();
                }
            }
            return new JSONObject().put("error", "notification not found").put("key", key).toString();
        }

        // POST /notifications/action — trigger a specific action button
        if (path.equals("/notifications/action")) {
            if (bridge == null) return "{\"error\":\"notification listener not running\"}";
            int actionIndex = req.optInt("actionIndex", -1);
            if (actionIndex < 0) return "{\"error\":\"provide 'actionIndex'\"}";

            List<StatusBarNotification> notifications = bridge.getNotifications();
            for (StatusBarNotification sbn : notifications) {
                if (sbn.getKey().equals(key)) {
                    Notification notif = sbn.getNotification();
                    if (notif.actions != null && actionIndex < notif.actions.length) {
                        notif.actions[actionIndex].actionIntent.send();
                        return new JSONObject()
                                .put("triggered", true)
                                .put("action", notif.actions[actionIndex].title.toString())
                                .toString();
                    }
                    return "{\"error\":\"action index out of range\"}";
                }
            }
            return new JSONObject().put("error", "notification not found").put("key", key).toString();
        }

        return "{\"error\":\"unknown notification endpoint\"}";
    }
}
