package com.clawuse.android.handlers;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles POST /audio/record — records audio from the device microphone.
 *
 * Uses AudioRecord for raw PCM capture, then encodes to WAV or AAC.
 * Runs in the main process (a11y server side) for full access.
 *
 * TODO: /audio/listen (passive audio capture of what the phone is PLAYING)
 * requires AudioPlaybackCapture API (Android 10+) which needs MediaProjection.
 * MediaProjection requires user consent via an Activity + onActivityResult flow,
 * making it impractical for a headless HTTP endpoint. A future implementation
 * could add a one-time consent flow in MainActivity that stores the projection
 * token, then AudioRecordHandler could reuse it for playback capture.
 */
public class AudioRecordHandler implements RouteHandler {
    private static final String TAG = "ClawAudioRecord";
    private static final int MAX_DURATION_MS = 30000;
    private static final int DEFAULT_DURATION_MS = 5000;
    private static final int DEFAULT_SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    private final AtomicBoolean recording = new AtomicBoolean(false);

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if (!method.equals("POST")) {
            return "{\"error\":\"POST required\"}";
        }

        if (recording.get()) {
            return "{\"error\":\"recording already in progress\"}";
        }

        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        int durationMs = req.optInt("durationMs", DEFAULT_DURATION_MS);
        String format = req.optString("format", "wav");
        int sampleRate = req.optInt("sampleRate", DEFAULT_SAMPLE_RATE);

        // Validate
        if (durationMs < 100 || durationMs > MAX_DURATION_MS) {
            return "{\"error\":\"durationMs must be 100-" + MAX_DURATION_MS + "\"}";
        }
        if (!"wav".equals(format) && !"aac".equals(format)) {
            return "{\"error\":\"format must be 'wav' or 'aac'\"}";
        }
        if (sampleRate < 8000 || sampleRate > 48000) {
            return "{\"error\":\"sampleRate must be 8000-48000\"}";
        }

        if (!recording.compareAndSet(false, true)) {
            return "{\"error\":\"recording already in progress\"}";
        }

        try {
            byte[] audioData;
            if ("aac".equals(format)) {
                audioData = recordAndEncodeAac(durationMs, sampleRate);
            } else {
                audioData = recordAndEncodeWav(durationMs, sampleRate);
            }

            String base64 = Base64.encodeToString(audioData, Base64.NO_WRAP);
            return new JSONObject()
                    .put("audio", base64)
                    .put("format", format)
                    .put("durationMs", durationMs)
                    .put("sampleRate", sampleRate)
                    .put("sizeBytes", audioData.length)
                    .toString();
        } catch (Exception e) {
            Log.e(TAG, "Recording failed", e);
            return new JSONObject().put("error", "recording failed: " + e.getMessage()).toString();
        } finally {
            recording.set(false);
        }
    }

    private byte[] recordAndEncodeWav(int durationMs, int sampleRate) throws Exception {
        int bufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            throw new RuntimeException("Invalid AudioRecord buffer size: " + bufferSize);
        }
        // Use at least 4KB buffer
        bufferSize = Math.max(bufferSize, 4096);

        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize);

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new RuntimeException("AudioRecord initialization failed");
        }

        ByteArrayOutputStream pcmStream = new ByteArrayOutputStream();
        byte[] readBuffer = new byte[bufferSize];

        try {
            recorder.startRecording();
            long startTime = System.currentTimeMillis();

            while (System.currentTimeMillis() - startTime < durationMs) {
                int read = recorder.read(readBuffer, 0, readBuffer.length);
                if (read > 0) {
                    pcmStream.write(readBuffer, 0, read);
                } else if (read < 0) {
                    Log.w(TAG, "AudioRecord.read() returned " + read);
                    break;
                }
            }

            recorder.stop();
        } finally {
            recorder.release();
        }

        byte[] pcmData = pcmStream.toByteArray();
        return createWav(pcmData, sampleRate, 1, 16);
    }

    private byte[] recordAndEncodeAac(int durationMs, int sampleRate) throws Exception {
        int bufferSize = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            throw new RuntimeException("Invalid AudioRecord buffer size: " + bufferSize);
        }
        bufferSize = Math.max(bufferSize, 4096);

        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize);

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            throw new RuntimeException("AudioRecord initialization failed");
        }

        // Set up AAC encoder
        MediaFormat mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
        mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize);

        MediaCodec encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        encoder.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();

        ByteArrayOutputStream aacStream = new ByteArrayOutputStream();
        byte[] readBuffer = new byte[bufferSize];
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        try {
            recorder.startRecording();
            long startTime = System.currentTimeMillis();
            boolean inputDone = false;

            while (true) {
                // Feed PCM to encoder
                if (!inputDone) {
                    int inputIndex = encoder.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuffer = encoder.getInputBuffer(inputIndex);
                        if (System.currentTimeMillis() - startTime >= durationMs) {
                            encoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            int read = recorder.read(readBuffer, 0, Math.min(readBuffer.length, inputBuffer.remaining()));
                            if (read > 0) {
                                inputBuffer.put(readBuffer, 0, read);
                                encoder.queueInputBuffer(inputIndex, 0, read,
                                        (System.currentTimeMillis() - startTime) * 1000, 0);
                            }
                        }
                    }
                }

                // Drain encoded output
                int outputIndex = encoder.dequeueOutputBuffer(bufferInfo, 10000);
                if (outputIndex >= 0) {
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                            && bufferInfo.size > 0) {
                        ByteBuffer outputBuffer = encoder.getOutputBuffer(outputIndex);
                        byte[] aacData = new byte[bufferInfo.size];
                        outputBuffer.get(aacData);
                        // Write ADTS header + AAC data
                        aacStream.write(createAdtsHeader(aacData.length, sampleRate));
                        aacStream.write(aacData);
                    }
                    encoder.releaseOutputBuffer(outputIndex, false);
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                } else if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER && inputDone) {
                    // Keep draining
                    continue;
                }
            }

            recorder.stop();
        } finally {
            recorder.release();
            encoder.stop();
            encoder.release();
        }

        return aacStream.toByteArray();
    }

    /**
     * Create a WAV file from raw PCM data.
     * WAV format: 44-byte RIFF header + raw PCM data.
     */
    private byte[] createWav(byte[] pcmData, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        int dataSize = pcmData.length;
        int totalSize = 44 + dataSize;

        byte[] wav = new byte[totalSize];

        // RIFF header
        wav[0] = 'R'; wav[1] = 'I'; wav[2] = 'F'; wav[3] = 'F';
        writeInt32LE(wav, 4, totalSize - 8);
        wav[8] = 'W'; wav[9] = 'A'; wav[10] = 'V'; wav[11] = 'E';

        // fmt subchunk
        wav[12] = 'f'; wav[13] = 'm'; wav[14] = 't'; wav[15] = ' ';
        writeInt32LE(wav, 16, 16);           // subchunk1 size (PCM = 16)
        writeInt16LE(wav, 20, 1);            // audio format (PCM = 1)
        writeInt16LE(wav, 22, channels);
        writeInt32LE(wav, 24, sampleRate);
        writeInt32LE(wav, 28, byteRate);
        writeInt16LE(wav, 32, blockAlign);
        writeInt16LE(wav, 34, bitsPerSample);

        // data subchunk
        wav[36] = 'd'; wav[37] = 'a'; wav[38] = 't'; wav[39] = 'a';
        writeInt32LE(wav, 40, dataSize);

        System.arraycopy(pcmData, 0, wav, 44, dataSize);
        return wav;
    }

    /** Create ADTS header for a single AAC frame. */
    private byte[] createAdtsHeader(int aacDataLength, int sampleRate) {
        int frameLength = aacDataLength + 7; // ADTS header is 7 bytes
        int freqIndex = getFreqIndex(sampleRate);

        byte[] header = new byte[7];
        header[0] = (byte) 0xFF;
        header[1] = (byte) 0xF9; // MPEG-4, Layer 0, no CRC
        header[2] = (byte) (((2 - 1) << 6) | (freqIndex << 2) | (0 << 1) | ((1 >> 2) & 0x01)); // AAC-LC, freq, channels
        header[3] = (byte) (((1 & 0x03) << 6) | ((frameLength >> 11) & 0x03));
        header[4] = (byte) ((frameLength >> 3) & 0xFF);
        header[5] = (byte) (((frameLength & 0x07) << 5) | 0x1F);
        header[6] = (byte) 0xFC;
        return header;
    }

    private int getFreqIndex(int sampleRate) {
        switch (sampleRate) {
            case 96000: return 0;
            case 88200: return 1;
            case 64000: return 2;
            case 48000: return 3;
            case 44100: return 4;
            case 32000: return 5;
            case 24000: return 6;
            case 22050: return 7;
            case 16000: return 8;
            case 12000: return 9;
            case 11025: return 10;
            case 8000:  return 11;
            default:    return 8; // default to 16kHz
        }
    }

    private static void writeInt32LE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeInt16LE(byte[] buf, int offset, int value) {
        buf[offset]     = (byte) (value & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }
}
