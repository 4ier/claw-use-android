package com.clawuse.android;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * Handles notification action buttons (Copy URL, Copy Token).
 */
public class NotificationActionReceiver extends BroadcastReceiver {
    public static final String ACTION_COPY_URL = "com.clawuse.android.COPY_URL";
    public static final String ACTION_COPY_TOKEN = "com.clawuse.android.COPY_TOKEN";
    public static final String EXTRA_TEXT = "copy_text";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        String text = intent.getStringExtra(EXTRA_TEXT);
        if (text == null) return;

        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;

        String label;
        if (ACTION_COPY_URL.equals(action)) {
            label = "Bridge URL";
        } else if (ACTION_COPY_TOKEN.equals(action)) {
            label = "Bridge Token";
        } else {
            return;
        }

        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(context, label + " copied", Toast.LENGTH_SHORT).show();
    }
}
