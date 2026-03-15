package com.clawuse.android;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.clawuse.android.auth.TokenManager;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.util.List;

/**
 * Setup screen: shows service status, connection info, QR code, and permissions.
 */
public class MainActivity extends AppCompatActivity {
    private TokenManager tokenManager;
    private TextView tvAccessibility, tvNotification, tvUrl, tvToken, tvPermissions;
    private ImageView ivQr;
    private Button btnAccessibility, btnNotification, btnCopyUrl, btnCopyToken, btnResetToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tokenManager = new TokenManager(this);

        tvAccessibility = findViewById(R.id.tv_accessibility);
        tvNotification = findViewById(R.id.tv_notification);
        tvUrl = findViewById(R.id.tv_url);
        tvToken = findViewById(R.id.tv_token);
        tvPermissions = findViewById(R.id.tv_permissions);
        ivQr = findViewById(R.id.iv_qr);
        btnAccessibility = findViewById(R.id.btn_accessibility);
        btnNotification = findViewById(R.id.btn_notification);
        btnCopyUrl = findViewById(R.id.btn_copy_url);
        btnCopyToken = findViewById(R.id.btn_copy_token);
        btnResetToken = findViewById(R.id.btn_reset_token);

        btnAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        btnNotification.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });

        btnCopyUrl.setOnClickListener(v -> copyToClipboard("Bridge URL", getServerUrl()));
        btnCopyToken.setOnClickListener(v -> copyToClipboard("Bridge Token", tokenManager.getToken()));

        btnResetToken.setOnClickListener(v -> {
            tokenManager.regenerateToken();
            updateUI();
            Toast.makeText(this, "Token regenerated", Toast.LENGTH_SHORT).show();
        });

        // Start bridge service
        Intent serviceIntent = new Intent(this, BridgeService.class);
        startForegroundService(serviceIntent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    private void updateUI() {
        // Accessibility service status
        boolean a11yRunning = isAccessibilityServiceEnabled();
        tvAccessibility.setText(a11yRunning ? "● Accessibility Service: ON" : "○ Accessibility Service: OFF");
        tvAccessibility.setTextColor(a11yRunning ? 0xFF4CAF50 : 0xFFF44336);

        // Notification listener status
        boolean notifRunning = NotificationBridge.isRunning();
        tvNotification.setText(notifRunning ? "● Notification Listener: ON" : "○ Notification Listener: OFF");
        tvNotification.setTextColor(notifRunning ? 0xFF4CAF50 : 0xFFF44336);

        // URL and token
        String url = getServerUrl();
        tvUrl.setText(url);
        tvToken.setText(tokenManager.getToken());

        // QR code (encode URL + token for easy setup)
        try {
            String qrContent = url + "\n" + tokenManager.getToken();
            BarcodeEncoder encoder = new BarcodeEncoder();
            ivQr.setImageBitmap(encoder.encodeBitmap(qrContent, BarcodeFormat.QR_CODE, 400, 400));
        } catch (Exception e) {
            // QR generation failed, not critical
        }

        // Permissions summary
        tvPermissions.setText(buildPermissionSummary());
    }

    private String getServerUrl() {
        BridgeService service = BridgeService.getInstance();
        if (service != null) {
            return service.getServerUrl();
        }
        return "http://...:7333";
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        ComponentName myService = new ComponentName(this, AccessibilityBridge.class);
        for (AccessibilityServiceInfo info : services) {
            ComponentName component = new ComponentName(info.getResolveInfo().serviceInfo.packageName,
                    info.getResolveInfo().serviceInfo.name);
            if (component.equals(myService)) return true;
        }
        return false;
    }

    private String buildPermissionSummary() {
        StringBuilder sb = new StringBuilder();
        String[][] perms = {
                {"Camera", "android.permission.CAMERA"},
                {"Location", "android.permission.ACCESS_FINE_LOCATION"},
                {"Microphone", "android.permission.RECORD_AUDIO"},
                {"SMS", "android.permission.SEND_SMS"},
                {"Contacts", "android.permission.READ_CONTACTS"},
                {"Files", "android.permission.READ_EXTERNAL_STORAGE"},
                {"Phone", "android.permission.CALL_PHONE"},
                {"Calendar", "android.permission.READ_CALENDAR"},
        };
        for (String[] p : perms) {
            boolean granted = checkSelfPermission(p[1]) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            sb.append(granted ? "✅ " : "❌ ").append(p[0]).append("  ");
        }
        return sb.toString().trim();
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, label + " copied", Toast.LENGTH_SHORT).show();
    }
}
