package com.clawuse.android.handlers;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Base64;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * POST /camera — capture a photo and return as base64 JPEG
 */
public class CameraHandler implements RouteHandler {
    private static final String TAG = "CameraHandler";
    private final Context context;

    public CameraHandler(Context context) {
        this.context = context;
    }

    @Override
    public String handle(String method, String path, Map<String, String> params, String body) throws Exception {
        if (!"POST".equals(method)) return "{\"error\":\"POST required\"}";

        JSONObject req = body != null && !body.isEmpty() ? new JSONObject(body) : new JSONObject();
        String facing = req.optString("facing", "back");
        int quality = req.optInt("quality", 80);
        int maxWidth = req.optInt("maxWidth", 1080);

        return capturePhoto(facing, quality, maxWidth);
    }

    private String capturePhoto(String facing, int quality, int maxWidth) throws Exception {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return "{\"error\":\"camera service unavailable\"}";

        // Find camera
        int targetFacing = "front".equals(facing) ?
                CameraCharacteristics.LENS_FACING_FRONT :
                CameraCharacteristics.LENS_FACING_BACK;

        String cameraId = null;
        for (String id : cm.getCameraIdList()) {
            CameraCharacteristics chars = cm.getCameraCharacteristics(id);
            Integer lensFacing = chars.get(CameraCharacteristics.LENS_FACING);
            if (lensFacing != null && lensFacing == targetFacing) {
                cameraId = id;
                break;
            }
        }
        if (cameraId == null) return "{\"error\":\"camera not found for facing: " + facing + "\"}";

        HandlerThread thread = new HandlerThread("CameraCapture");
        thread.start();
        Handler handler = new Handler(thread.getLooper());

        // ImageReader for JPEG
        ImageReader reader = ImageReader.newInstance(1920, 1080, ImageFormat.JPEG, 1);
        AtomicReference<byte[]> jpegRef = new AtomicReference<>(null);
        CountDownLatch imageLatch = new CountDownLatch(1);

        reader.setOnImageAvailableListener(r -> {
            try {
                Image image = r.acquireLatestImage();
                if (image != null) {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] data = new byte[buffer.remaining()];
                    buffer.get(data);
                    jpegRef.set(data);
                    image.close();
                }
            } catch (Exception e) {
                Log.w(TAG, "Image acquire failed", e);
            }
            imageLatch.countDown();
        }, handler);

        // Open camera
        CountDownLatch openLatch = new CountDownLatch(1);
        AtomicReference<CameraDevice> deviceRef = new AtomicReference<>(null);
        AtomicReference<String> errorRef = new AtomicReference<>(null);

        try {
            cm.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    deviceRef.set(camera);
                    openLatch.countDown();
                }
                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    errorRef.set("camera disconnected");
                    openLatch.countDown();
                }
                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    errorRef.set("camera error: " + error);
                    openLatch.countDown();
                }
            }, handler);
        } catch (SecurityException e) {
            thread.quitSafely();
            reader.close();
            return "{\"error\":\"camera permission denied\"}";
        }

        if (!openLatch.await(10, TimeUnit.SECONDS) || deviceRef.get() == null) {
            thread.quitSafely();
            reader.close();
            return "{\"error\":\"" + (errorRef.get() != null ? errorRef.get() : "camera open timeout") + "\"}";
        }

        CameraDevice device = deviceRef.get();

        // Create capture session
        CountDownLatch sessionLatch = new CountDownLatch(1);
        AtomicReference<CameraCaptureSession> sessionRef = new AtomicReference<>(null);

        try {
            device.createCaptureSession(Arrays.asList(reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            sessionRef.set(session);
                            sessionLatch.countDown();
                        }
                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            errorRef.set("capture session config failed");
                            sessionLatch.countDown();
                        }
                    }, handler);
        } catch (CameraAccessException e) {
            device.close();
            thread.quitSafely();
            reader.close();
            return "{\"error\":\"camera access: " + e.getMessage() + "\"}";
        }

        if (!sessionLatch.await(10, TimeUnit.SECONDS) || sessionRef.get() == null) {
            device.close();
            thread.quitSafely();
            reader.close();
            return "{\"error\":\"" + (errorRef.get() != null ? errorRef.get() : "session timeout") + "\"}";
        }

        // Capture
        try {
            CaptureRequest.Builder builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(reader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            builder.set(CaptureRequest.JPEG_QUALITY, (byte) quality);

            sessionRef.get().capture(builder.build(), null, handler);
        } catch (CameraAccessException e) {
            device.close();
            thread.quitSafely();
            reader.close();
            return "{\"error\":\"capture failed: " + e.getMessage() + "\"}";
        }

        // Wait for image
        boolean gotImage = imageLatch.await(10, TimeUnit.SECONDS);

        // Cleanup
        device.close();
        reader.close();
        thread.quitSafely();

        if (!gotImage || jpegRef.get() == null) {
            return "{\"error\":\"capture timeout\"}";
        }

        byte[] jpeg = jpegRef.get();

        // Scale down if needed
        if (maxWidth > 0 && maxWidth < 1920) {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);

            if (opts.outWidth > maxWidth) {
                float scale = (float) maxWidth / opts.outWidth;
                opts.inJustDecodeBounds = false;
                opts.inSampleSize = Math.max(1, Math.round(1f / scale));
                Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
                if (bmp != null) {
                    int newW = maxWidth;
                    int newH = (int) (bmp.getHeight() * ((float) maxWidth / bmp.getWidth()));
                    Bitmap scaled = Bitmap.createScaledBitmap(bmp, newW, newH, true);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, baos);
                    jpeg = baos.toByteArray();
                    if (scaled != bmp) scaled.recycle();
                    bmp.recycle();
                }
            }
        }

        JSONObject json = new JSONObject();
        json.put("image", Base64.encodeToString(jpeg, Base64.NO_WRAP));
        json.put("sizeBytes", jpeg.length);
        json.put("facing", facing);

        BitmapFactory.Options info = new BitmapFactory.Options();
        info.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, info);
        json.put("width", info.outWidth);
        json.put("height", info.outHeight);

        return json.toString();
    }
}
