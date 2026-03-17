package com.clawuse.android;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.Locale;

/**
 * Floating HUD showing AI operation status with live reasoning steps.
 *
 * Features:
 * - Semi-transparent floating card with live operation log
 * - Each API call logged with icon + description + timestamp
 * - Scrolling updates, auto-scroll to bottom
 * - Takeover button to pause AI control
 * - Draggable, auto-collapse after inactivity
 * - TYPE_ACCESSIBILITY_OVERLAY (no extra permission)
 */
public class OverlayManager {
    private static final String TAG = "OverlayManager";
    private static OverlayManager instance;

    private final Context context;
    private WindowManager wm;
    private final Handler mainHandler;

    // HUD views
    private FrameLayout hudContainer;
    private LinearLayout hudCard;
    private TextView titleView;
    private LinearLayout logContainer;
    private ScrollView logScroll;
    private TextView takeoverBtn;
    private WindowManager.LayoutParams hudParams;
    private boolean hudShowing = false;

    // State
    private volatile boolean takenOver = false;
    private final LinkedList<String> logEntries = new LinkedList<>();
    private static final int MAX_LOG_ENTRIES = 20;
    private Runnable hideRunnable;
    private long lastActivityMs = 0;
    private static final long HUD_LINGER_MS = 4000;
    private String lastError = null;

    // Pulse indicator
    private View pulseIndicator;
    private ValueAnimator pulseAnimator;

    public static synchronized OverlayManager getInstance(Context context) {
        // Always recreate with fresh service context (service may have been restarted)
        if (instance != null) {
            instance.removeAll();
        }
        instance = new OverlayManager(context);
        return instance;
    }

    public static synchronized OverlayManager getInstanceOrNull() {
        return instance;
    }

    private OverlayManager(Context context) {
        this.context = context;
        // Get WindowManager from the service context (not applicationContext)
        // This is critical for TYPE_ACCESSIBILITY_OVERLAY token
        this.wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.mainHandler = new Handler(Looper.getMainLooper());
        Log.i(TAG, "OverlayManager created with context: " + context.getClass().getSimpleName());
    }

    public boolean isTakenOver() {
        return takenOver;
    }

    public String getLastError() {
        return lastError;
    }

    /** Log an operation and show HUD. Called from any thread. */
    public void logOperation(String description) {
        if (takenOver) return;
        lastActivityMs = System.currentTimeMillis();
        String time = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
        String entry = time + "  " + description;

        mainHandler.post(() -> {
            synchronized (logEntries) {
                logEntries.addLast(entry);
                while (logEntries.size() > MAX_LOG_ENTRIES) logEntries.removeFirst();
            }
            showHud();
            refreshLog();
            resetHideTimer();
        });
    }

    /** Called when API request comes in. */
    public void onApiActivity() {
        // Just refresh timer, actual log is added by logOperation
        lastActivityMs = System.currentTimeMillis();
        mainHandler.post(() -> {
            if (hudShowing) resetHideTimer();
        });
    }

    private void resetHideTimer() {
        if (hideRunnable != null) mainHandler.removeCallbacks(hideRunnable);
        hideRunnable = () -> {
            long elapsed = System.currentTimeMillis() - lastActivityMs;
            if (elapsed >= HUD_LINGER_MS && !takenOver) {
                hideHud();
            }
        };
        mainHandler.postDelayed(hideRunnable, HUD_LINGER_MS);
    }

    // ── HUD ──

    private void showHud() {
        if (hudShowing) return;
        try {
            if (hudContainer == null) createHud();
            // Check overlay permission
            if (!android.provider.Settings.canDrawOverlays(context)) {
                lastError = "SYSTEM_ALERT_WINDOW permission not granted. Go to Settings → Apps → Claw Use → Display over other apps";
                Log.w(TAG, lastError);
                return;
            }
            wm.addView(hudContainer, hudParams);
            hudShowing = true;
            startPulse();
            lastError = null;
            Log.i(TAG, "HUD shown");
        } catch (Exception e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            Log.e(TAG, "Failed to show HUD", e);
        }
    }

    private void hideHud() {
        if (!hudShowing || hudContainer == null) return;
        try {
            stopPulse();
            wm.removeView(hudContainer);
            hudShowing = false;
            Log.i(TAG, "HUD hidden");
        } catch (Exception e) {
            Log.e(TAG, "Failed to hide HUD", e);
        }
    }

    private void createHud() {
        float density = context.getResources().getDisplayMetrics().density;
        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;

        // Container
        hudContainer = new FrameLayout(context);

        // Card — frosted glass aesthetic
        hudCard = new LinearLayout(context);
        hudCard.setOrientation(LinearLayout.VERTICAL);
        int cardWidth = (int) (screenWidth * 0.88f);
        hudCard.setPadding(dp(16), dp(12), dp(16), dp(12));

        // Gradient background: deep navy → dark indigo
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                new int[]{0xF0111827, 0xF01E1B4B} // slate-900 → indigo-950
        );
        cardBg.setCornerRadius(dp(20));
        cardBg.setStroke(dp(1), 0x30A5B4FC); // ghost indigo border
        hudCard.setBackground(cardBg);
        hudCard.setElevation(dp(12));

        // ── Header row ──
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        // Pulse dot — larger, with glow effect
        pulseIndicator = new View(context);
        android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
        dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dotBg.setColor(0xFF6366F1); // indigo-500
        pulseIndicator.setBackground(dotBg);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7), dp(7));
        dotLp.rightMargin = dp(10);
        header.addView(pulseIndicator, dotLp);

        // Title — clean, weighted
        titleView = new TextView(context);
        titleView.setText("AI 操作中");
        titleView.setTextColor(0xFFF1F5F9); // slate-100
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        titleView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleView.setLetterSpacing(0.02f);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        header.addView(titleView, titleLp);

        // Takeover button — pill shape, subtle
        takeoverBtn = new TextView(context);
        updateTakeoverBtn();
        takeoverBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        takeoverBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        takeoverBtn.setPadding(dp(14), dp(5), dp(14), dp(5));
        android.graphics.drawable.GradientDrawable btnBg = new android.graphics.drawable.GradientDrawable();
        btnBg.setCornerRadius(dp(14));
        btnBg.setColor(0x226366F1); // very subtle indigo fill
        btnBg.setStroke(dp(1), 0x40818CF8);
        takeoverBtn.setBackground(btnBg);
        takeoverBtn.setOnClickListener(v -> toggleTakeover());
        header.addView(takeoverBtn);

        hudCard.addView(header);

        // ── Divider — gradient line ──
        View divider = new View(context);
        android.graphics.drawable.GradientDrawable divBg = new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0x006366F1, 0x406366F1, 0x006366F1} // fade in/out
        );
        divider.setBackground(divBg);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.topMargin = dp(10);
        divLp.bottomMargin = dp(8);
        hudCard.addView(divider, divLp);

        // ── Log scroll ──
        logScroll = new ScrollView(context);
        logScroll.setVerticalScrollBarEnabled(false);
        logScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        logScroll.setFadingEdgeLength(dp(20));
        logScroll.setVerticalFadingEdgeEnabled(true);

        logContainer = new LinearLayout(context);
        logContainer.setOrientation(LinearLayout.VERTICAL);
        logContainer.setPadding(0, dp(2), 0, dp(2));
        logScroll.addView(logContainer);

        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(100));
        hudCard.addView(logScroll, scrollLp);

        // Card in container with margin
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardWidth, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER_HORIZONTAL;
        hudContainer.addView(hudCard, cardLp);

        // Window params
        hudParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        hudParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        hudParams.y = dp(48);

        // Drag support
        hudContainer.setOnTouchListener(new View.OnTouchListener() {
            private float startY, startRawY;
            private boolean isDragging = false;
            private long downTime;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = hudParams.y;
                        startRawY = event.getRawY();
                        isDragging = false;
                        downTime = System.currentTimeMillis();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float dy = startRawY - event.getRawY();
                        if (Math.abs(dy) > dp(8)) isDragging = true;
                        if (isDragging) {
                            hudParams.y = (int) Math.max(dp(20), Math.min(dp(600), startY + dy));
                            try { wm.updateViewLayout(hudContainer, hudParams); } catch (Exception ignored) {}
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        return true;
                }
                return false;
            }
        });
    }

    private void refreshLog() {
        if (logContainer == null) return;
        logContainer.removeAllViews();
        synchronized (logEntries) {
            int total = logEntries.size();
            int idx = 0;
            for (String entry : logEntries) {
                idx++;
                // Each entry: "HH:mm:ss  📍 description"
                // Split time from description
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(4), dp(3), dp(4), dp(3));

                // Fade older entries
                float alpha = 0.4f + 0.6f * ((float) idx / total);

                // Time part (first 8 chars)
                String timePart = entry.length() > 10 ? entry.substring(0, 8) : "";
                String descPart = entry.length() > 10 ? entry.substring(10) : entry;

                TextView timeView = new TextView(context);
                timeView.setText(timePart);
                timeView.setTextColor(0xFF64748B); // slate-500
                timeView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
                timeView.setTypeface(Typeface.MONOSPACE);
                timeView.setAlpha(alpha);
                LinearLayout.LayoutParams timeLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                timeLp.rightMargin = dp(8);
                row.addView(timeView, timeLp);

                // Description
                TextView descView = new TextView(context);
                descView.setText(descPart);
                descView.setTextColor(0xFFC7D2FE); // indigo-200
                descView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                descView.setSingleLine(true);
                descView.setEllipsize(TextUtils.TruncateAt.END);
                descView.setAlpha(alpha);
                row.addView(descView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

                // Latest entry highlight
                if (idx == total) {
                    android.graphics.drawable.GradientDrawable rowBg = new android.graphics.drawable.GradientDrawable();
                    rowBg.setCornerRadius(dp(6));
                    rowBg.setColor(0x156366F1); // very faint indigo highlight
                    row.setBackground(rowBg);
                    descView.setTextColor(0xFFE0E7FF); // brighter for latest
                }

                logContainer.addView(row);
            }
        }
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void updateTakeoverBtn() {
        if (takeoverBtn == null) return;
        if (takenOver) {
            takeoverBtn.setText("▶ 恢复");
            takeoverBtn.setTextColor(0xFF10B981);
        } else {
            takeoverBtn.setText("✋ 接管");
            takeoverBtn.setTextColor(0xFFE0E7FF);
        }
    }

    private void toggleTakeover() {
        takenOver = !takenOver;
        updateTakeoverBtn();
        if (takenOver) {
            if (titleView != null) titleView.setText("已接管");
            stopPulse();
            // Auto-restore after 60s
            mainHandler.postDelayed(() -> {
                if (takenOver) {
                    takenOver = false;
                    updateTakeoverBtn();
                    if (titleView != null) titleView.setText("AI 操作中");
                    hideHud();
                }
            }, 60_000);
        } else {
            if (titleView != null) titleView.setText("AI 操作中");
            hideHud();
        }
    }

    private void startPulse() {
        if (pulseAnimator != null) pulseAnimator.cancel();
        if (pulseIndicator == null) return;
        pulseAnimator = ValueAnimator.ofFloat(0.3f, 1f);
        pulseAnimator.setDuration(800);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.addUpdateListener(a -> {
            if (pulseIndicator != null) pulseIndicator.setAlpha((float) a.getAnimatedValue());
        });
        pulseAnimator.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }

    public void removeAll() {
        mainHandler.post(() -> {
            hideHud();
            takenOver = false;
        });
    }

    private int dp(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density);
    }

    // ── Operation description mapping ──

    /** Convert API path + method to human-readable description. */
    public static String describeOperation(String method, String path) {
        if (path == null) path = "";
        // Strip query params
        int q = path.indexOf('?');
        String clean = q >= 0 ? path.substring(0, q) : path;

        switch (clean) {
            case "/screen": return "👁 读取屏幕";
            case "/screenshot": return "📸 截取屏幕";
            case "/click": return "👆 点击元素";
            case "/type": return "⌨️ 输入文字";
            case "/swipe": return "👆 滑动屏幕";
            case "/scroll": return "📜 滚动页面";
            case "/global": return "🏠 全局操作";
            case "/launch": return "🚀 启动应用";
            case "/intent": return "📤 发送 Intent";
            case "/install": return "📦 安装应用";
            case "/battery": return "🔋 查询电量";
            case "/wifi": return "📶 查询网络";
            case "/location": return "📍 获取位置";
            case "/camera": return "📷 拍照";
            case "/volume": return "POST".equals(method) ? "🔊 调整音量" : "🔈 查询音量";
            case "/vibrate": return "📳 振动";
            case "/clipboard": return "POST".equals(method) ? "📋 写入剪贴板" : "📋 读取剪贴板";
            case "/contacts": return "👤 查询通讯录";
            case "/sms": return "POST".equals(method) ? "✉️ 发送短信" : "📨 读取短信";
            case "/tts": return "🔊 语音播报";
            case "/audio/record": return "🎙 录音";
            case "/notifications": return "🔔 查询通知";
            case "/file": return "POST".equals(method) ? "💾 写入文件" : "📄 读取文件";
            case "/file/list": return "📂 列出文件";
            case "/file/upload": return "📤 上传文件";
            case "/unlock": return "🔓 解锁屏幕";
            default:
                if (clean.startsWith("/screen/")) return "👁 " + clean;
                return "⚡ " + clean;
        }
    }
}
