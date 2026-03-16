package com.clawuse.android.handlers;

import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import org.json.JSONObject;

import java.util.Map;

/**
 * GET /wifi — WiFi connection info
 */
public class WifiHandler implements RouteHandler {
    private final Context context;

    public WifiHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        WifiManager wm = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        JSONObject json = new JSONObject();

        if (wm == null) {
            json.put("error", "wifi service unavailable");
            return json.toString();
        }

        json.put("enabled", wm.isWifiEnabled());

        WifiInfo info = wm.getConnectionInfo();
        if (info != null) {
            String ssid = info.getSSID();
            json.put("ssid", ssid != null ? ssid.replace("\"", "") : "");
            json.put("bssid", info.getBSSID() != null ? info.getBSSID() : "");
            json.put("rssi", info.getRssi());
            json.put("linkSpeed", info.getLinkSpeed());
            json.put("frequency", info.getFrequency());

            int ipInt = info.getIpAddress();
            String ip = String.format("%d.%d.%d.%d",
                    (ipInt & 0xff), (ipInt >> 8 & 0xff),
                    (ipInt >> 16 & 0xff), (ipInt >> 24 & 0xff));
            json.put("ip", ip);
        }

        return json.toString();
    }
}
