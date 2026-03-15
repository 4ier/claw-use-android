package com.clawuse.android;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Listens for all notifications across apps, caching them for the HTTP API.
 */
public class NotificationBridge extends NotificationListenerService {
    private static final String TAG = "ClawNotify";
    private static volatile NotificationBridge instance;
    private final List<StatusBarNotification> cachedNotifications =
            Collections.synchronizedList(new ArrayList<>());

    public static NotificationBridge getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        refreshCache();
        Log.i(TAG, "NotificationBridge connected");
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        instance = null;
        Log.i(TAG, "NotificationBridge disconnected");
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        refreshCache();
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        refreshCache();
    }

    public List<StatusBarNotification> getNotifications() {
        return new ArrayList<>(cachedNotifications);
    }

    public void dismissNotification(String key) {
        cancelNotification(key);
    }

    public void dismissAll() {
        cancelAllNotifications();
    }

    private void refreshCache() {
        try {
            StatusBarNotification[] active = getActiveNotifications();
            cachedNotifications.clear();
            if (active != null) {
                Collections.addAll(cachedNotifications, active);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to refresh notifications", e);
        }
    }
}
