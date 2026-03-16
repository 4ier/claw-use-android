package com.clawuse.android.handlers;

import android.content.Context;
import android.media.AudioManager;

import org.json.JSONObject;

import java.util.Map;

/**
 * GET /volume — read all volume levels
 * POST /volume — set volume or adjust up/down/mute
 */
public class VolumeHandler implements RouteHandler {
    private final Context context;

    private static final int[][] STREAMS = {
        {AudioManager.STREAM_MUSIC, 0},        // "media"
        {AudioManager.STREAM_RING, 1},          // "ring"
        {AudioManager.STREAM_ALARM, 2},         // "alarm"
        {AudioManager.STREAM_NOTIFICATION, 3},  // "notification"
        {AudioManager.STREAM_VOICE_CALL, 4}     // "call"
    };
    private static final String[] NAMES = {"media", "ring", "alarm", "notification", "call"};

    public VolumeHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return "{\"error\":\"audio service unavailable\"}";

        if ("POST".equals(method)) {
            return setVolume(am, body);
        }
        return getVolumes(am);
    }

    private String getVolumes(AudioManager am) throws Exception {
        JSONObject json = new JSONObject();
        JSONObject max = new JSONObject();
        for (int i = 0; i < STREAMS.length; i++) {
            int stream = STREAMS[i][0];
            json.put(NAMES[i], am.getStreamVolume(stream));
            max.put(NAMES[i], am.getStreamMaxVolume(stream));
        }
        json.put("max", max);
        return json.toString();
    }

    private String setVolume(AudioManager am, String body) throws Exception {
        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        String streamName = req.optString("stream", "media");
        int streamType = AudioManager.STREAM_MUSIC;
        for (int i = 0; i < NAMES.length; i++) {
            if (NAMES[i].equals(streamName)) {
                streamType = STREAMS[i][0];
                break;
            }
        }

        String action = req.optString("action", "");
        if (!action.isEmpty()) {
            switch (action) {
                case "up":
                    am.adjustStreamVolume(streamType, AudioManager.ADJUST_RAISE, 0);
                    break;
                case "down":
                    am.adjustStreamVolume(streamType, AudioManager.ADJUST_LOWER, 0);
                    break;
                case "mute":
                    am.adjustStreamVolume(streamType, AudioManager.ADJUST_MUTE, 0);
                    break;
                case "unmute":
                    am.adjustStreamVolume(streamType, AudioManager.ADJUST_UNMUTE, 0);
                    break;
            }
        } else if (req.has("level")) {
            int level = req.getInt("level");
            am.setStreamVolume(streamType, level, 0);
        } else {
            return "{\"error\":\"provide 'level' or 'action'\"}";
        }

        JSONObject json = new JSONObject();
        json.put("stream", streamName);
        json.put("volume", am.getStreamVolume(streamType));
        json.put("max", am.getStreamMaxVolume(streamType));
        return json.toString();
    }
}
