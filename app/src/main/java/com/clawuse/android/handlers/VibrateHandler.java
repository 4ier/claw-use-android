package com.clawuse.android.handlers;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * POST /vibrate — vibrate the device
 */
public class VibrateHandler implements RouteHandler {
    private static final String TAG = "VibrateHandler";
    private final Context context;

    public VibrateHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if (!"POST".equals(method)) return "{\"error\":\"POST required\"}";

        Vibrator vibrator;
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager vm = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            vibrator = vm != null ? vm.getDefaultVibrator() : null;
        } else {
            vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }

        if (vibrator == null || !vibrator.hasVibrator()) {
            return "{\"error\":\"device has no vibrator hardware\"}";
        }

        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();

        JSONObject result = new JSONObject();
        result.put("hasVibrator", vibrator.hasVibrator());
        result.put("apiLevel", Build.VERSION.SDK_INT);

        try {
            if (req.has("pattern")) {
                JSONArray arr = req.getJSONArray("pattern");
                long[] pattern = new long[arr.length()];
                for (int i = 0; i < arr.length(); i++) pattern[i] = arr.getLong(i);
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                long ms = req.optLong("ms", 200);
                if (ms > 10000) ms = 10000;
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            }
            result.put("vibrated", true);
        } catch (Exception e) {
            Log.e(TAG, "vibrate failed", e);
            result.put("vibrated", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return result.toString();
    }
}
