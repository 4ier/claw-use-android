package com.clawuse.android.handlers;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

import org.json.JSONObject;

import java.util.Map;

/**
 * GET /battery — battery status, level, temperature
 */
public class BatteryHandler implements RouteHandler {
    private final Context context;

    public BatteryHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        Intent battery = context.registerReceiver(null, filter);

        JSONObject json = new JSONObject();
        if (battery == null) {
            json.put("error", "battery info unavailable");
            return json.toString();
        }

        int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int pct = (level >= 0 && scale > 0) ? (level * 100 / scale) : -1;

        int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
        boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;

        int plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
        String pluggedStr = "none";
        if (plugged == BatteryManager.BATTERY_PLUGGED_AC) pluggedStr = "ac";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_USB) pluggedStr = "usb";
        else if (plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS) pluggedStr = "wireless";

        int temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        int voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);

        int health = battery.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
        String healthStr = "unknown";
        if (health == BatteryManager.BATTERY_HEALTH_GOOD) healthStr = "good";
        else if (health == BatteryManager.BATTERY_HEALTH_OVERHEAT) healthStr = "overheat";
        else if (health == BatteryManager.BATTERY_HEALTH_DEAD) healthStr = "dead";
        else if (health == BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE) healthStr = "over_voltage";

        json.put("level", pct);
        json.put("charging", charging);
        json.put("plugged", pluggedStr);
        json.put("temperature", temp > 0 ? temp / 10.0 : -1);
        json.put("voltage", voltage);
        json.put("health", healthStr);
        return json.toString();
    }
}
