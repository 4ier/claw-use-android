package com.clawuse.android;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

/**
 * Overlay manager for AI operation visual feedback.
 *
 * Shows a glowing border around the screen when AI is actively controlling the device,
 * and a floating "takeover" pill button that lets the user pause AI control.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY (no extra permission needed for AccessibilityService).
 */
public class OverlayManager {
    private static final String TAG = "OverlayManager";
    private static OverlayManager instance;

    private final Context context;
    private final WindowManager wm;
    private final Handler mainHandler;

    // Border overlay
    private GlowBorderView borderView;
    private WindowManager.LayoutParams borderParams;
    private boolean borderShowing = false;

    // Takeover pill
    private FrameLayout pillContainer;
    private TextView pillText;
    private WindowManager.LayoutParams pillParams;
    private boolean pillShowing = false;

    // State
    private volatile boolean takenOver = false;
    private ValueAnimator pulseAnimator;
    private Runnable hideRunnable;
    private long lastActivityMs = 0;
    private static final long BORDER_LINGER_MS = 1500; // border stays 1.5s after last activity

    public static synchronized OverlayManager getInstance(Context context) {
        if (instance == null) {
            instance = new OverlayManager(context.getApplicationContext());
        }
        return instance;
    }

    public static synchronized OverlayManager getInstanceOrNull() {
        return instance;
    }

    private OverlayManager(Context context) {
        this.context = context;
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /** Check if user has taken over control. API handlers should check this. */
    public boolean isTakenOver() {
        return takenOver;
    }

    /** Called when an API request comes in. Shows border + resets hide timer. */
    public void onApiActivity() {
        if (takenOver) return;
        lastActivityMs = System.currentTimeMillis();
        mainHandler.post(() -> {
            showBorder();
            showPill();
            resetHideTimer();
        });
    }

    /** Called when AI operation completes or after linger timeout. */
    private void scheduleHide() {
        mainHandler.post(() -> {
            hideBorder();
            // Pill stays visible until explicitly dismissed or timeout
        });
    }

    private void resetHideTimer() {
        if (hideRunnable != null) mainHandler.removeCallbacks(hideRunnable);
        hideRunnable = () -> {
            long elapsed = System.currentTimeMillis() - lastActivityMs;
            if (elapsed >= BORDER_LINGER_MS && !takenOver) {
                hideBorder();
                hidePill();
            }
        };
        mainHandler.postDelayed(hideRunnable, BORDER_LINGER_MS);
    }

    // ── Border ──

    private void showBorder() {
        if (borderShowing) return;
        try {
            if (borderView == null) {
                borderView = new GlowBorderView(context);
                borderParams = new WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                        PixelFormat.TRANSLUCENT
                );
                borderParams.gravity = Gravity.TOP | Gravity.LEFT;
            }
            wm.addView(borderView, borderParams);
            borderShowing = true;
            startPulse();
        } catch (Exception e) {
            Log.e(TAG, "Failed to show border", e);
        }
    }

    private void hideBorder() {
        if (!borderShowing || borderView == null) return;
        try {
            stopPulse();
            wm.removeView(borderView);
            borderShowing = false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to hide border", e);
        }
    }

    private void startPulse() {
        if (pulseAnimator != null) pulseAnimator.cancel();
        pulseAnimator = ValueAnimator.ofFloat(0.3f, 0.8f);
        pulseAnimator.setDuration(1200);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.addUpdateListener(anim -> {
            if (borderView != null) {
                borderView.setAlphaLevel((float) anim.getAnimatedValue());
            }
        });
        pulseAnimator.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }

    // ── Takeover Pill ──

    private void showPill() {
        if (pillShowing) return;
        try {
            if (pillContainer == null) {
                createPill();
            }
            updatePillAppearance();
            wm.addView(pillContainer, pillParams);
            pillShowing = true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to show pill", e);
        }
    }

    private void hidePill() {
        if (!pillShowing || pillContainer == null) return;
        try {
            wm.removeView(pillContainer);
            pillShowing = false;
        } catch (Exception e) {
            Log.e(TAG, "Failed to hide pill", e);
        }
    }

    private void createPill() {
        pillContainer = new FrameLayout(context);

        pillText = new TextView(context);
        pillText.setTextSize(13);
        pillText.setPadding(dp(16), dp(8), dp(16), dp(8));
        pillText.setGravity(Gravity.CENTER);

        // Rounded background
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(20));
        bg.setColor(0xE6000000); // 90% opaque black
        bg.setStroke(dp(1), 0xFF6366F1); // indigo border
        pillText.setBackground(bg);

        pillContainer.addView(pillText);

        pillParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        pillParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        pillParams.y = dp(80);

        // Drag + tap handling
        pillContainer.setOnTouchListener(new View.OnTouchListener() {
            private float startY, startRawY;
            private boolean isDragging = false;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = pillParams.y;
                        startRawY = event.getRawY();
                        isDragging = false;
                        downTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = startRawY - event.getRawY();
                        if (Math.abs(dy) > dp(8)) isDragging = true;
                        if (isDragging) {
                            pillParams.y = (int) (startY + dy);
                            if (pillParams.y < dp(20)) pillParams.y = dp(20);
                            if (pillParams.y > dp(400)) pillParams.y = dp(400);
                            try { wm.updateViewLayout(pillContainer, pillParams); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!isDragging && System.currentTimeMillis() - downTime < 300) {
                            toggleTakeover();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void updatePillAppearance() {
        if (pillText == null) return;
        if (takenOver) {
            pillText.setText("🔓 恢复 AI 控制");
            pillText.setTextColor(0xFF10B981); // green
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(0xE6000000);
            bg.setStroke(dp(1), 0xFF10B981);
            pillText.setBackground(bg);
        } else {
            pillText.setText("🤖 AI 操作中 · 点击接管");
            pillText.setTextColor(0xFFE0E7FF); // light indigo
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setCornerRadius(dp(20));
            bg.setColor(0xE6000000);
            bg.setStroke(dp(1), 0xFF6366F1);
            pillText.setBackground(bg);
        }
    }

    private void toggleTakeover() {
        takenOver = !takenOver;
        updatePillAppearance();
        if (takenOver) {
            hideBorder();
            // Auto-restore after 60s
            mainHandler.postDelayed(() -> {
                if (takenOver) {
                    takenOver = false;
                    updatePillAppearance();
                    hidePill();
                }
            }, 60_000);
        } else {
            hidePill();
        }
    }

    /** Force remove all overlays (cleanup). */
    public void removeAll() {
        mainHandler.post(() -> {
            hideBorder();
            hidePill();
            takenOver = false;
        });
    }

    private int dp(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    // ── Glow Border View ──

    private static class GlowBorderView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float alphaLevel = 0.5f;
        private final float borderWidth;

        GlowBorderView(Context context) {
            super(context);
            borderWidth = 4 * context.getResources().getDisplayMetrics().density;
            setClickable(false);
            setFocusable(false);
        }

        void setAlphaLevel(float alpha) {
            this.alphaLevel = alpha;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w == 0 || h == 0) return;

            int alpha = (int) (alphaLevel * 255);

            // Colors: indigo → violet → indigo
            int c1 = Color.argb(alpha, 99, 102, 241);   // indigo-500
            int c2 = Color.argb(alpha, 139, 92, 246);    // violet-500
            int c3 = Color.argb(alpha, 99, 102, 241);    // indigo-500

            // Top
            paint.setShader(new LinearGradient(0, 0, w, 0, new int[]{c1, c2, c3}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, borderWidth, paint);

            // Bottom
            canvas.drawRect(0, h - borderWidth, w, h, paint);

            // Left
            paint.setShader(new LinearGradient(0, 0, 0, h, new int[]{c1, c2, c3}, null, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, borderWidth, h, paint);

            // Right
            canvas.drawRect(w - borderWidth, 0, w, h, paint);
        }
    }
}
