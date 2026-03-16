package com.clawuse.android.handlers;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Handles audio endpoints:
 * POST /tts          — speak text through the phone speaker
 * GET  /tts/voices   — list available TTS voices
 */
public class AudioHandler implements RouteHandler {
    private static final String TAG = "ClawAudio";
    private final Context context;
    private volatile TextToSpeech tts;
    private volatile boolean ttsReady = false;
    private final CountDownLatch ttsInitLatch = new CountDownLatch(1);

    public AudioHandler(Context context) {
        this.context = context;
        // Initialize TTS engine
        tts = new TextToSpeech(context, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.CHINESE);
                ttsReady = true;
                Log.i(TAG, "TTS engine ready");
            } else {
                Log.e(TAG, "TTS init failed with status " + status);
            }
            ttsInitLatch.countDown();
        });
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if (path.equals("/tts/voices")) {
            return listVoices();
        }
        if (path.equals("/tts")) {
            return handleSpeak(body);
        }
        return "{\"error\":\"unknown audio endpoint\"}";
    }

    private String handleSpeak(String body) throws Exception {
        // Wait for TTS init (up to 3s)
        if (!ttsReady) {
            ttsInitLatch.await(3, TimeUnit.SECONDS);
        }
        if (!ttsReady) {
            return "{\"error\":\"TTS engine not ready\"}";
        }

        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        String text = req.optString("text", "");
        if (text.isEmpty()) {
            return "{\"error\":\"provide 'text'\"}";
        }

        // Optional language
        String lang = req.optString("language", "");
        if (!lang.isEmpty()) {
            Locale locale = Locale.forLanguageTag(lang);
            tts.setLanguage(locale);
        }

        // Optional speech rate (0.5 - 2.0, default 1.0)
        float rate = (float) req.optDouble("rate", 1.0);
        tts.setSpeechRate(Math.max(0.25f, Math.min(4.0f, rate)));

        // Optional pitch (0.5 - 2.0, default 1.0)
        float pitch = (float) req.optDouble("pitch", 1.0);
        tts.setPitch(Math.max(0.25f, Math.min(4.0f, pitch)));

        // Speak with completion callback
        String utteranceId = "claw-tts-" + System.currentTimeMillis();
        CountDownLatch speakLatch = new CountDownLatch(1);
        final boolean[] success = {false};

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onDone(String id) {
                if (utteranceId.equals(id)) { success[0] = true; speakLatch.countDown(); }
            }
            @Override public void onError(String id) {
                if (utteranceId.equals(id)) { speakLatch.countDown(); }
            }
        });

        int result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (result != TextToSpeech.SUCCESS) {
            return new JSONObject().put("error", "TTS speak failed").put("code", result).toString();
        }

        // Wait for speech to complete (up to 30s for long text)
        boolean completed = speakLatch.await(30, TimeUnit.SECONDS);

        return new JSONObject()
                .put("spoken", success[0])
                .put("text", text)
                .put("completed", completed)
                .toString();
    }

    private String listVoices() throws Exception {
        if (!ttsReady) {
            ttsInitLatch.await(3, TimeUnit.SECONDS);
        }
        if (!ttsReady) return "{\"error\":\"TTS engine not ready\"}";

        JSONArray voices = new JSONArray();
        if (tts.getVoices() != null) {
            for (android.speech.tts.Voice v : tts.getVoices()) {
                JSONObject voice = new JSONObject();
                voice.put("name", v.getName());
                voice.put("locale", v.getLocale().toString());
                voice.put("quality", v.getQuality());
                voice.put("requiresNetwork", v.isNetworkConnectionRequired());
                voices.put(voice);
            }
        }
        return new JSONObject().put("voices", voices).put("count", voices.length()).toString();
    }

    public void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }
}
