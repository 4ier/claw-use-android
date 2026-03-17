package com.clawuse.android;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.concurrent.CompletableFuture;

/**
 * Fullscreen transparent Activity with dialog-style UI.
 * Shows agent permission request. Result delivered via static CompletableFuture.
 */
public class PermissionGateActivity extends Activity {

    /** Result of the permission gate. */
    public static class GateResult {
        public final boolean allowed;
        public final boolean trustAgent;

        public GateResult(boolean allowed, boolean trustAgent) {
            this.allowed = allowed;
            this.trustAgent = trustAgent;
        }
    }

    // Static state for communication with BridgeService
    private static volatile CompletableFuture<GateResult> pendingFuture;
    private static volatile String pendingAgentName;
    private static volatile String pendingSourceIp;

    private static final long GATE_TIMEOUT_MS = 30_000;
    private Handler timeoutHandler;
    private Runnable timeoutRunnable;
    private CheckBox trustCheckBox;

    /**
     * Prepare the gate. Must be called before launching the activity.
     * Returns a CompletableFuture that will be completed with the user's decision.
     */
    public static CompletableFuture<GateResult> prepare(String agentName, String sourceIp) {
        pendingAgentName = agentName;
        pendingSourceIp = sourceIp;
        pendingFuture = new CompletableFuture<>();
        return pendingFuture;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
        // Show on lock screen
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Semi-transparent background
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        String agentName = pendingAgentName != null ? pendingAgentName : "Unknown Agent";
        String sourceIp = pendingSourceIp != null ? pendingSourceIp : "unknown";

        float density = getResources().getDisplayMetrics().density;

        // Root: dim overlay
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xAA000000);

        // Dialog card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (24 * density);
        card.setPadding(pad, pad, pad, pad);

        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setCornerRadius(20 * density);
        cardBg.setColor(0xF0111827); // slate-900
        cardBg.setStroke((int) (1 * density), 0x40A5B4FC);
        card.setBackground(cardBg);
        card.setElevation(16 * density);

        // Robot emoji
        TextView emoji = new TextView(this);
        emoji.setText("🤖");
        emoji.setTextSize(TypedValue.COMPLEX_UNIT_SP, 40);
        emoji.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams emojiLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        emojiLp.bottomMargin = (int) (12 * density);
        card.addView(emoji, emojiLp);

        // Title
        TextView title = new TextView(this);
        title.setText(getString(R.string.permission_title));
        title.setTextColor(0xFFF1F5F9);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        titleLp.bottomMargin = (int) (20 * density);
        card.addView(title, titleLp);

        // Source info
        addInfoRow(card, getString(R.string.permission_source), sourceIp, density);
        addInfoRow(card, getString(R.string.permission_agent), agentName, density);

        // Trust checkbox
        trustCheckBox = new CheckBox(this);
        trustCheckBox.setText(getString(R.string.permission_trust));
        trustCheckBox.setTextColor(0xFF94A3B8); // slate-400
        trustCheckBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        trustCheckBox.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFF6366F1));
        LinearLayout.LayoutParams cbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cbLp.topMargin = (int) (16 * density);
        cbLp.bottomMargin = (int) (20 * density);
        card.addView(trustCheckBox, cbLp);

        // Buttons row
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.CENTER);

        // Deny button
        TextView denyBtn = makeButton(getString(R.string.permission_deny),
                0xFF1E293B, 0xFFE2E8F0, density);
        denyBtn.setOnClickListener(v -> complete(false));
        LinearLayout.LayoutParams denyLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        denyLp.rightMargin = (int) (8 * density);
        btnRow.addView(denyBtn, denyLp);

        // Allow button
        TextView allowBtn = makeButton(getString(R.string.permission_allow),
                0xFF4F46E5, 0xFFFFFFFF, density);
        allowBtn.setOnClickListener(v -> complete(true));
        LinearLayout.LayoutParams allowLp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        allowLp.leftMargin = (int) (8 * density);
        btnRow.addView(allowBtn, allowLp);

        card.addView(btnRow);

        // Card layout params
        int cardWidth = (int) (getResources().getDisplayMetrics().widthPixels * 0.85f);
        FrameLayout.LayoutParams cardLp = new FrameLayout.LayoutParams(
                cardWidth, FrameLayout.LayoutParams.WRAP_CONTENT);
        cardLp.gravity = Gravity.CENTER;
        root.addView(card, cardLp);

        setContentView(root);

        // Timeout
        timeoutHandler = new Handler(Looper.getMainLooper());
        timeoutRunnable = () -> complete(false);
        timeoutHandler.postDelayed(timeoutRunnable, GATE_TIMEOUT_MS);
    }

    private void addInfoRow(LinearLayout parent, String label, String value, float density) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, (int) (4 * density), 0, (int) (4 * density));

        TextView labelView = new TextView(this);
        labelView.setText(label + ": ");
        labelView.setTextColor(0xFF64748B); // slate-500
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(0xFFE0E7FF); // indigo-100
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        valueView.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        row.addView(valueView);

        parent.addView(row);
    }

    private TextView makeButton(String text, int bgColor, int textColor, float density) {
        TextView btn = new TextView(this);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setGravity(Gravity.CENTER);
        btn.setPadding((int) (16 * density), (int) (12 * density),
                (int) (16 * density), (int) (12 * density));

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(12 * density);
        bg.setColor(bgColor);
        btn.setBackground(bg);
        return btn;
    }

    private void complete(boolean allowed) {
        if (timeoutHandler != null && timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
        }
        boolean trust = trustCheckBox != null && trustCheckBox.isChecked();
        CompletableFuture<GateResult> future = pendingFuture;
        if (future != null && !future.isDone()) {
            future.complete(new GateResult(allowed, trust));
        }
        pendingFuture = null;
        pendingAgentName = null;
        pendingSourceIp = null;
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        complete(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // If activity is destroyed without completing (e.g., system kill), deny
        CompletableFuture<GateResult> future = pendingFuture;
        if (future != null && !future.isDone()) {
            future.complete(new GateResult(false, false));
        }
    }
}
