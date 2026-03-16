package com.clawuse.android;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

/**
 * Auto-restart BridgeService after app update (MY_PACKAGE_REPLACED).
 * This closes the self-update loop: agent can build → send APK → install → service auto-restarts.
 */
public class UpdateReceiver extends BroadcastReceiver {
    private static final String TAG = "ClawUpdate";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            Log.i(TAG, "Package updated — restarting BridgeService");
            Intent serviceIntent = new Intent(context, BridgeService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
