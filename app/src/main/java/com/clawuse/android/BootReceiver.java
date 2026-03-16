package com.clawuse.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Starts BridgeService automatically on device boot.
 * The AccessibilityService (AccessibilityBridge) is managed by the system
 * and will reconnect on its own if enabled in Settings.
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "ClawBoot";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Log.i(TAG, "Boot completed — starting BridgeService");
            Intent serviceIntent = new Intent(context, BridgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
