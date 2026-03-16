package com.clawuse.android.handlers;

import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Looper;

import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * GET /location — current GPS/network location
 */
public class LocationHandler implements RouteHandler {
    private final Context context;

    public LocationHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null) return "{\"error\":\"location service unavailable\"}";

        // Try last known from multiple providers
        String[] providers = {LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER};
        Location best = null;

        for (String provider : providers) {
            try {
                Location loc = lm.getLastKnownLocation(provider);
                if (loc != null && (best == null || loc.getTime() > best.getTime())) {
                    best = loc;
                }
            } catch (SecurityException ignored) {}
        }

        // If no cached location, request a fresh one with 10s timeout
        if (best == null) {
            best = requestFreshLocation(lm);
        }

        if (best == null) {
            return "{\"error\":\"location unavailable, check GPS/location permissions\"}";
        }

        return locationToJson(best).toString();
    }

    private Location requestFreshLocation(LocationManager lm) {
        final CountDownLatch latch = new CountDownLatch(1);
        final Location[] result = {null};

        LocationListener listener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                result[0] = location;
                latch.countDown();
            }
            @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
            @Override public void onProviderEnabled(String provider) {}
            @Override public void onProviderDisabled(String provider) {}
        };

        try {
            String provider = LocationManager.NETWORK_PROVIDER;
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                provider = LocationManager.GPS_PROVIDER;
            }
            lm.requestSingleUpdate(provider, listener, Looper.getMainLooper());
            latch.await(10, TimeUnit.SECONDS);
            lm.removeUpdates(listener);
        } catch (SecurityException | InterruptedException ignored) {}

        return result[0];
    }

    private JSONObject locationToJson(Location loc) throws Exception {
        JSONObject json = new JSONObject();
        json.put("lat", loc.getLatitude());
        json.put("lon", loc.getLongitude());
        json.put("accuracy", loc.getAccuracy());
        json.put("altitude", loc.getAltitude());
        json.put("speed", loc.getSpeed());
        json.put("bearing", loc.getBearing());
        json.put("provider", loc.getProvider());
        json.put("timestamp", loc.getTime());
        return json;
    }
}
