package com.quotadesk.mobile;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Bundle;
import android.provider.Settings;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.google.mlkit.vision.barcode.BarcodeScanner;
import com.google.mlkit.vision.barcode.BarcodeScannerOptions;
import com.google.mlkit.vision.barcode.BarcodeScanning;
import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.common.InputImage;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScannerActivity extends ComponentActivity {
    public static final String EXTRA_RESULT = "com.quotadesk.mobile.QR_RESULT";
    private static final int REQUEST_CAMERA = 412;
    private static final String PREFS = "quota_desk_mobile";
    private static final String PREF_LIGHT_THEME = "light_theme_v1";

    private final AtomicBoolean resultSent = new AtomicBoolean(false);
    private ExecutorService cameraExecutor;
    private BarcodeScanner scanner;
    private ProcessCameraProvider cameraProvider;
    private Camera camera;
    private PreviewView previewView;
    private TextView statusLabel;
    private TextView flashButton;
    private TextView zoomLabel;
    private boolean torchEnabled;
    private float zoomRatio = 1f;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        boolean light = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_LIGHT_THEME, false);
        setTheme(light ? R.style.AppThemeLight : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        // targetSdk 35 在 Android 15+ 强制边到边，setStatusBarColor/setNavigationBarColor 已失效，
        // 相机预览铺满全屏，系统栏区域由浮层各自消费 insets
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        if (android.os.Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, window.getDecorView());
        bars.setAppearanceLightStatusBars(false);
        bars.setAppearanceLightNavigationBars(false);
        cameraExecutor = Executors.newSingleThreadExecutor();
        scanner = BarcodeScanning.getClient(new BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .build());
        buildScannerUi();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA);
        }
    }

    private void buildScannerUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        previewView = new PreviewView(this);
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        previewView.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        root.addView(previewView, new FrameLayout.LayoutParams(-1, -1));

        root.addView(new ScanOverlay(this), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout topBar = new LinearLayout(this);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(3), dp(10), dp(3));
        topBar.setMinimumHeight(dp(60));
        topBar.setBackgroundColor(0xB9000000);
        TextView back = scannerLabel("‹", 34, Color.WHITE, false);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription("返回");
        back.setOnClickListener(view -> finish());
        topBar.addView(back, new LinearLayout.LayoutParams(dp(48), dp(50)));
        LinearLayout titleBlock = new LinearLayout(this);
        titleBlock.setOrientation(LinearLayout.VERTICAL);
        titleBlock.setPadding(dp(5), 0, 0, 0);
        titleBlock.addView(scannerLabel("扫描配对二维码", 15, Color.WHITE, true));
        titleBlock.addView(scannerLabel("Quota Desk", 10, 0xFFCBD1CF, false));
        topBar.addView(titleBlock, new LinearLayout.LayoutParams(0, -2, 1));
        flashButton = scannerLabel("闪光灯", 11, Color.WHITE, true);
        flashButton.setGravity(Gravity.CENTER);
        flashButton.setBackground(background(0x44FFFFFF, 8));
        flashButton.setOnClickListener(view -> toggleTorch());
        topBar.addView(flashButton, new LinearLayout.LayoutParams(dp(67), dp(34)));
        root.addView(topBar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));

        LinearLayout bottomPanel = new LinearLayout(this);
        bottomPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanel.setPadding(dp(20), dp(13), dp(20), dp(12));
        bottomPanel.setBackgroundColor(0xC9000000);
        statusLabel = scannerLabel("将电脑端二维码放入取景框", 13, Color.WHITE, true);
        statusLabel.setGravity(Gravity.CENTER);
        bottomPanel.addView(statusLabel);
        TextView hint = scannerLabel("识别后会自动添加这台电脑", 10, 0xFFD0D6D4, false);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(-1, -2);
        hintParams.topMargin = dp(4);
        bottomPanel.addView(hint, hintParams);

        LinearLayout zoomControls = new LinearLayout(this);
        zoomControls.setGravity(Gravity.CENTER);
        TextView zoomOut = zoomButton("−");
        zoomOut.setOnClickListener(view -> changeZoom(-0.5f));
        zoomLabel = zoomButton("1×");
        zoomLabel.setOnClickListener(view -> setZoom(1f));
        TextView zoomIn = zoomButton("+");
        zoomIn.setOnClickListener(view -> changeZoom(0.5f));
        zoomControls.addView(zoomOut);
        LinearLayout.LayoutParams zoomLabelParams = new LinearLayout.LayoutParams(dp(72), dp(34));
        zoomLabelParams.setMargins(dp(9), 0, dp(9), 0);
        zoomControls.addView(zoomLabel, zoomLabelParams);
        zoomControls.addView(zoomIn);
        LinearLayout.LayoutParams zoomParams = new LinearLayout.LayoutParams(-1, dp(36));
        zoomParams.topMargin = dp(7);
        bottomPanel.addView(zoomControls, zoomParams);

        root.addView(bottomPanel, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            topBar.setPadding(dp(8), dp(3) + safe.top, dp(10), dp(3));
            bottomPanel.setPadding(dp(20), dp(13), dp(20), dp(12) + safe.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
        setContentView(root);
    }

    private void startCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (previewView != null) bindCamera(previewView);
    }

    private void bindCamera(PreviewView previewView) {
        if (isFinishing() || ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                cameraProvider = ProcessCameraProvider.getInstance(this).get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setTargetResolution(new android.util.Size(1280, 720))
                        .build();
                analysis.setAnalyzer(cameraExecutor, imageProxy -> {
                    android.media.Image mediaImage = imageProxy.getImage();
                    if (mediaImage == null || resultSent.get()) { imageProxy.close(); return; }
                    InputImage input = InputImage.fromMediaImage(mediaImage, imageProxy.getImageInfo().getRotationDegrees());
                    scanner.process(input)
                            .addOnSuccessListener(ContextCompat.getMainExecutor(this), barcodes -> {
                                for (Barcode barcode : barcodes) {
                                    String value = barcode.getRawValue();
                                    if (value != null && !value.trim().isEmpty() && resultSent.compareAndSet(false, true)) {
                                        if (cameraProvider != null) cameraProvider.unbindAll();
                                        setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT, value));
                                        finish();
                                        break;
                                    }
                                }
                            })
                            .addOnCompleteListener(task -> imageProxy.close());
                });
                cameraProvider.unbindAll();
                camera = cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis);
                updateCameraControls();
            } catch (Exception error) {
                if (statusLabel != null) statusLabel.setText("无法启动相机，请重试或检查权限");
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void updateCameraControls() {
        boolean hasFlash = camera != null && camera.getCameraInfo().hasFlashUnit();
        flashButton.setEnabled(hasFlash);
        flashButton.setAlpha(hasFlash ? 1f : .45f);
        zoomRatio = 1f;
        zoomLabel.setText("1×");
    }

    private void toggleTorch() {
        if (camera == null || !camera.getCameraInfo().hasFlashUnit()) return;
        torchEnabled = !torchEnabled;
        camera.getCameraControl().enableTorch(torchEnabled);
        flashButton.setText(torchEnabled ? "关闭闪光灯" : "闪光灯");
    }

    private void changeZoom(float delta) {
        float max = camera == null || camera.getCameraInfo().getZoomState().getValue() == null
                ? 1f : camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
        setZoom(Math.max(1f, Math.min(max, zoomRatio + delta)));
    }

    private void setZoom(float ratio) {
        if (camera == null) return;
        float max = camera.getCameraInfo().getZoomState().getValue() == null
                ? 1f : camera.getCameraInfo().getZoomState().getValue().getMaxZoomRatio();
        zoomRatio = Math.max(1f, Math.min(max, ratio));
        camera.getCameraControl().setZoomRatio(zoomRatio);
        zoomLabel.setText(String.format(java.util.Locale.ROOT, "%.1f×", zoomRatio));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startCamera();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("需要相机权限")
                .setMessage("扫描电脑端的配对二维码需要使用相机。你可以在系统设置中为 Quota Desk 开启相机权限。")
                .setNegativeButton("返回", (dialog, which) -> finish())
                .setPositiveButton("打开设置", (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", getPackageName(), null));
                    startActivity(intent);
                    finish();
                })
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    @Override
    protected void onDestroy() {
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (scanner != null) scanner.close();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        super.onDestroy();
    }

    private TextView zoomButton(String text) {
        TextView button = scannerLabel(text, 14, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setBackground(background(0x44FFFFFF, 8));
        return button;
    }

    private TextView scannerLabel(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(android.graphics.Typeface.create("sans-serif", bold ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        return view;
    }

    private android.graphics.drawable.GradientDrawable background(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), 0x55FFFFFF);
        return drawable;
    }

    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private static final class ScanOverlay extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mask = new Path();
        private final RectF scanRect = new RectF();
        private final float density;

        ScanOverlay(ScannerActivity context) {
            super(context);
            density = context.getResources().getDisplayMetrics().density;
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float side = Math.min(getWidth() - 52 * density, 286 * density);
            float centerY = getHeight() * .47f;
            scanRect.set((getWidth() - side) / 2f, centerY - side / 2f, (getWidth() + side) / 2f, centerY + side / 2f);
            mask.reset();
            mask.setFillType(Path.FillType.EVEN_ODD);
            mask.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            mask.addRoundRect(scanRect, 18 * density, 18 * density, Path.Direction.CW);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x99000000);
            canvas.drawPath(mask, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3 * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(0xFF78E0B1);
            float length = 27 * density;
            float inset = 1.5f * density;
            Path corners = new Path();
            corners.moveTo(scanRect.left + inset, scanRect.top + length); corners.lineTo(scanRect.left + inset, scanRect.top + 18 * density); corners.quadTo(scanRect.left + inset, scanRect.top + inset, scanRect.left + 18 * density, scanRect.top + inset); corners.lineTo(scanRect.left + length, scanRect.top + inset);
            corners.moveTo(scanRect.right - length, scanRect.top + inset); corners.lineTo(scanRect.right - 18 * density, scanRect.top + inset); corners.quadTo(scanRect.right - inset, scanRect.top + inset, scanRect.right - inset, scanRect.top + 18 * density); corners.lineTo(scanRect.right - inset, scanRect.top + length);
            corners.moveTo(scanRect.left + inset, scanRect.bottom - length); corners.lineTo(scanRect.left + inset, scanRect.bottom - 18 * density); corners.quadTo(scanRect.left + inset, scanRect.bottom - inset, scanRect.left + 18 * density, scanRect.bottom - inset); corners.lineTo(scanRect.left + length, scanRect.bottom - inset);
            corners.moveTo(scanRect.right - length, scanRect.bottom - inset); corners.lineTo(scanRect.right - 18 * density, scanRect.bottom - inset); corners.quadTo(scanRect.right - inset, scanRect.bottom - inset, scanRect.right - inset, scanRect.bottom - 18 * density); corners.lineTo(scanRect.right - inset, scanRect.bottom - length);
            canvas.drawPath(corners, paint);
        }
    }
}
