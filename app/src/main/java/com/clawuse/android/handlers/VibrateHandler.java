package com.clawuse.android.handlers;

import android.content.Context;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Map;

/**
 * POST /vibrate — vibrate the device
 */
public class VibrateHandler implements RouteHandler {
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

        if (req.has("pattern")) {
            JSONArray arr = req.getJSONArray("pattern");
            long[] pattern = new long[arr.length()];
            for (int i = 0; i < arr.length(); i++) pattern[i] = arr.getLong(i);
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1));
            } else {
                vibrator.vibrate(pattern, -1);
            }
        } else {
            long ms = req.optLong("ms", 200);
            if (ms > 10000) ms = 10000;
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(ms);
            }
        }

        return new JSONObject().put("vibrated", true).toString();
    }
}
