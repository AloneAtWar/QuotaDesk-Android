package com.quotadesk.mobile;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends ComponentActivity {
    private int BG;
    private int SURFACE;
    private int RAISED;
    private int BORDER;
    private int INK;
    private int MUTED;
    private int ACCENT;
    private int ACCENT_INK;
    private static final String PREFS = "quota_desk_mobile";
    private static final String PREF_PROFILES = "device_profiles_v1";
    private static final String PREF_LIGHT_THEME = "light_theme_v1";
    private static final long CONNECTION_TIMEOUT_MS = 12_000L;
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/AloneAtWar/QuotaDesk-Android/releases/latest";

    private final List<DeviceProfile> devices = new ArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> scanLauncher;
    private WebView webView;
    private View webError;
    private TextView loadingLabel;
    private Runnable connectionTimeout;
    private Runnable deviceNamePrefill;
    private Runnable pairingCheck;
    private DeviceProfile activeProfile;
    private boolean showingDashboard;
    private boolean lightTheme;
    private boolean updateCheckInProgress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        lightTheme = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_LIGHT_THEME, false);
        setTheme(lightTheme ? R.style.AppThemeLight : R.style.AppTheme);
        super.onCreate(savedInstanceState);
        applyNativeThemeColors();
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        if (android.os.Build.VERSION.SDK_INT >= 29) window.setNavigationBarContrastEnforced(false);
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, window.getDecorView());
        bars.setAppearanceLightStatusBars(lightTheme);
        bars.setAppearanceLightNavigationBars(lightTheme);

        scanLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            onScanResult(result.getData().getStringExtra(ScannerActivity.EXTRA_RESULT));
        });
        loadProfiles();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (showingDashboard) {
                    if (webView != null && webView.canGoBack()) webView.goBack();
                    else returnToDevices();
                } else {
                    finish();
                }
            }
        });
        showDevices();
        mainHandler.postDelayed(() -> checkForUpdates(false), 800L);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) webView.onResume();
    }

    @Override
    protected void onPause() {
        if (webView != null) webView.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyWebView();
        super.onDestroy();
    }

    private void onScanResult(String contents) {
        if (contents == null) return;
        try {
            addDevice(parsePairingLink(contents));
        } catch (IllegalArgumentException error) {
            toast(error.getMessage());
        }
    }

    private void launchScanner() {
        scanLauncher.launch(new Intent(this, ScannerActivity.class));
    }

    private PairingData parsePairingLink(String value) {
        Uri uri = Uri.parse(value == null ? "" : value.trim());
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost();
        if (!("http".equals(scheme) || "https".equals(scheme)) || host == null || host.isEmpty()) {
            throw new IllegalArgumentException("这不是有效的 Quota Desk 配对链接，请扫描电脑端设置里的配对信息。");
        }
        if (uri.getUserInfo() != null || (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535))) {
            throw new IllegalArgumentException("配对地址格式无效，请重新扫描电脑端二维码。");
        }
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !"/".equals(path) && !"/remote.html".equals(path)) {
            throw new IllegalArgumentException("链接没有指向 Quota Desk 远程页面，请扫描电脑端的配对信息。");
        }
        if ("http".equals(scheme) && !isTrustedPrivateHttpHost(host)) {
            throw new IllegalArgumentException("局域网地址可以使用 HTTP；外部网络地址请使用 HTTPS。");
        }
        String token = accessToken(uri.getEncodedFragment());
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("配对信息里没有配对密钥，请从电脑端重新生成配对信息。");
        }
        String baseUrl = uri.buildUpon().encodedFragment(null).build().toString();
        String name = host + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
        return new PairingData(name, baseUrl, uri.toString());
    }

    private String accessToken(String encodedFragment) {
        if (encodedFragment == null) return null;
        for (String part : encodedFragment.split("&")) {
            int equals = part.indexOf('=');
            if (equals < 0) continue;
            String key = Uri.decode(part.substring(0, equals));
            if ("pair".equals(key) || "access".equals(key)) return Uri.decode(part.substring(equals + 1));
        }
        return null;
    }

    private boolean isTrustedPrivateHttpHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized) || normalized.endsWith(".local")) return true;
        String[] parts = normalized.split("\\.");
        if (parts.length != 4) return false;
        int[] octets = new int[4];
        try {
            for (int i = 0; i < parts.length; i++) {
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] < 0 || octets[i] > 255) return false;
            }
        } catch (NumberFormatException ignored) {
            return false;
        }
        return octets[0] == 10
                || (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31)
                || (octets[0] == 192 && octets[1] == 168)
                || octets[0] == 127;
    }

    private void addDevice(PairingData pairing) {
        // 先不落库：进入"待配对"状态，网页里配对成功（onPairingConfirmed）后才保存并在列表显示
        DeviceProfile profile = new DeviceProfile(pairing.name, pairing.baseUrl);
        int existing = indexOfDevice(pairing.baseUrl);
        if (existing >= 0) profile.name = devices.get(existing).name;
        profile.pendingPairing = true;
        showDashboard(profile, pairing.pairingUrl);
    }

    private void onPairingConfirmed() {
        if (!showingDashboard || activeProfile == null || !activeProfile.pendingPairing) return;
        activeProfile.pendingPairing = false;
        boolean known = indexOfDevice(activeProfile.baseUrl) >= 0;
        if (known) devices.remove(indexOfDevice(activeProfile.baseUrl));
        devices.add(0, activeProfile);
        saveProfiles();
        toast(known ? "配对成功，已更新这台电脑" : "配对成功，已添加这台电脑");
    }

    private int indexOfDevice(String baseUrl) {
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).baseUrl.equals(baseUrl)) return i;
        }
        return -1;
    }

    private void loadProfiles() {
        devices.clear();
        String encoded = getSharedPreferences(PREFS, MODE_PRIVATE).getString(PREF_PROFILES, "[]");
        try {
            JSONArray items = new JSONArray(encoded);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                String name = item.optString("name", "").trim();
                String baseUrl = item.optString("url", "").trim();
                if (name.isEmpty() || baseUrl.isEmpty()) continue;
                devices.add(new DeviceProfile(name, baseUrl));
            }
        } catch (JSONException ignored) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().remove(PREF_PROFILES).apply();
        }
    }

    private void saveProfiles() {
        JSONArray items = new JSONArray();
        for (DeviceProfile profile : devices) {
            JSONObject item = new JSONObject();
            try {
                item.put("name", profile.name);
                item.put("url", profile.baseUrl);
                items.put(item);
            } catch (JSONException ignored) { }
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(PREF_PROFILES, items.toString()).apply();
    }

    private void showDevices() {
        showingDashboard = false;
        activeProfile = null;
        destroyWebView();

        // 列表内容滚动时从状态栏/小白条下方穿过：insets 加在 ScrollView 自身而非根布局，
        // clipToPadding=false 让 padding 区域继续绘制内容
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        ViewCompat.setOnApplyWindowInsetsListener(scroll, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(scroll);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(18), dp(22), dp(28));
        scroll.addView(content, new ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(scroll, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout brand = new LinearLayout(this);
        brand.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = label("Q", 18, ACCENT_INK, true);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(round(ACCENT, 12, ACCENT, 0));
        brand.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));
        LinearLayout brandCopy = new LinearLayout(this);
        brandCopy.setOrientation(LinearLayout.VERTICAL);
        brandCopy.setPadding(dp(11), 0, 0, 0);
        brandCopy.addView(label("Quota Desk", 15, INK, true));
        brandCopy.addView(label("额度远程查看", 11, MUTED, false));
        brand.addView(brandCopy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView badge = label("ANDROID", 9, ACCENT, true);
        badge.setPadding(dp(9), dp(6), dp(9), dp(6));
        badge.setBackground(round(RAISED, 20, BORDER, 1));
        brand.addView(badge);
        TextView themeButton = label(lightTheme ? "☀" : "☾", 17, INK, true);
        themeButton.setGravity(Gravity.CENTER);
        themeButton.setContentDescription(lightTheme ? "切换暗色主题" : "切换亮色主题");
        themeButton.setBackground(round(SURFACE, 10, BORDER, 1));
        LinearLayout.LayoutParams themeParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        themeParams.leftMargin = dp(8);
        brand.addView(themeButton, themeParams);
        themeButton.setOnClickListener(view -> {
            lightTheme = !lightTheme;
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_LIGHT_THEME, lightTheme).apply();
            recreate();
        });
        content.addView(brand);

        content.addView(gap(34));
        content.addView(label("你的设备", 29, INK, true));
        TextView intro = label("添加电脑后，额度面板会沿用 Quota Desk 网页界面。", 13, MUTED, false);
        intro.setPadding(0, dp(8), 0, 0);
        content.addView(intro);

        content.addView(gap(24));
        TextView scan = label("▦     扫描电脑端配对信息", 15, ACCENT_INK, true);
        scan.setGravity(Gravity.CENTER);
        scan.setMinHeight(dp(58));
        scan.setBackground(round(ACCENT, 15, ACCENT, 0));
        scan.setOnClickListener(view -> launchScanner());
        content.addView(scan, matchWidth());

        TextView manual = label("手动粘贴配对链接", 13, INK, true);
        manual.setGravity(Gravity.CENTER);
        manual.setMinHeight(dp(48));
        manual.setBackground(round(SURFACE, 14, BORDER, 1));
        manual.setOnClickListener(view -> showManualEntry());
        LinearLayout.LayoutParams manualParams = matchWidth();
        manualParams.topMargin = dp(10);
        content.addView(manual, manualParams);

        content.addView(gap(30));
        LinearLayout section = new LinearLayout(this);
        section.setGravity(Gravity.CENTER_VERTICAL);
        TextView sectionTitle = label("已添加的设备", 14, INK, true);
        section.addView(sectionTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        section.addView(label(String.valueOf(devices.size()), 12, MUTED, true));
        content.addView(section);
        content.addView(gap(11));

        if (devices.isEmpty()) {
            LinearLayout empty = new LinearLayout(this);
            empty.setOrientation(LinearLayout.VERTICAL);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(22), dp(20), dp(22));
            empty.setBackground(round(SURFACE, 15, BORDER, 1));
            TextView icon = label("⌁", 28, ACCENT, true);
            icon.setGravity(Gravity.CENTER);
            empty.addView(icon);
            TextView title = label("还没有添加设备", 14, INK, true);
            title.setGravity(Gravity.CENTER);
            title.setPadding(0, dp(9), 0, dp(5));
            empty.addView(title);
            TextView description = label("在电脑端打开「设置 → 远程查看」，启用访问后扫描配对信息。", 12, MUTED, false);
            description.setGravity(Gravity.CENTER);
            description.setLineSpacing(dp(3), 1f);
            empty.addView(description);
            content.addView(empty, matchWidth());
        } else {
            for (DeviceProfile profile : new ArrayList<>(devices)) {
                content.addView(deviceCard(profile), matchWidth());
                content.addView(gap(9));
            }
        }

        content.addView(gap(12));
        TextView note = label("额度仍由电脑采集。电脑和访问网络保持运行时，手机才能读取最新信息。", 11, MUTED, false);
        note.setLineSpacing(dp(3), 1f);
        note.setPadding(dp(3), 0, dp(3), 0);
        content.addView(note);

        TextView update = label("检查更新  ·  当前 " + currentVersion(), 12, MUTED, true);
        update.setGravity(Gravity.CENTER);
        update.setMinHeight(dp(46));
        update.setBackground(round(SURFACE, 13, BORDER, 1));
        update.setOnClickListener(view -> checkForUpdates(true));
        LinearLayout.LayoutParams updateParams = matchWidth();
        updateParams.topMargin = dp(18);
        content.addView(update, updateParams);
        setContentView(root);
    }

    private void checkForUpdates(boolean userInitiated) {
        if (updateCheckInProgress) {
            if (userInitiated) toast("正在检查更新…");
            return;
        }
        updateCheckInProgress = true;
        if (userInitiated) toast("正在检查更新…");
        new Thread(() -> {
            UpdateRelease release = null;
            String errorMessage = null;
            try {
                release = fetchLatestRelease();
            } catch (Exception error) {
                errorMessage = error.getMessage();
            }
            UpdateRelease result = release;
            String failure = errorMessage;
            mainHandler.post(() -> {
                updateCheckInProgress = false;
                if (isFinishing() || isDestroyed()) return;
                if (failure != null) {
                    if (userInitiated) toast("检查更新失败，请稍后重试");
                    return;
                }
                if (result != null && compareVersions(result.version, currentVersion()) > 0) {
                    if (userInitiated || !showingDashboard) showUpdateDialog(result);
                } else if (userInitiated) {
                    toast("当前已是最新版本 " + currentVersion());
                }
            });
        }, "QuotaDesk-update-check").start();
    }

    private UpdateRelease fetchLatestRelease() throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
        connection.setConnectTimeout(8_000);
        connection.setReadTimeout(8_000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        connection.setRequestProperty("User-Agent", "QuotaDesk-Android/" + currentVersion());
        try {
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("GitHub returned " + status);
            StringBuilder json = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) json.append(line);
            }
            JSONObject release = new JSONObject(json.toString());
            String version = normalizeVersion(release.optString("tag_name"));
            if (version.isEmpty()) throw new JSONException("Release version is missing");
            String releaseUrl = release.optString("html_url");
            String downloadUrl = releaseUrl;
            JSONArray assets = release.optJSONArray("assets");
            if (assets != null) {
                for (int index = 0; index < assets.length(); index++) {
                    JSONObject asset = assets.optJSONObject(index);
                    if (asset == null || !asset.optString("name").toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                    String candidate = asset.optString("browser_download_url");
                    if (!candidate.isEmpty()) {
                        downloadUrl = candidate;
                        break;
                    }
                }
            }
            if (downloadUrl.isEmpty()) throw new JSONException("Release download URL is missing");
            return new UpdateRelease(version, release.optString("body"), downloadUrl);
        } finally {
            connection.disconnect();
        }
    }

    private void showUpdateDialog(UpdateRelease release) {
        String notes = formatReleaseNotes(release.notes);
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + release.version)
                .setMessage(notes.isEmpty() ? "新版本已经可以下载。" : notes)
                .setNegativeButton("稍后", null)
                .setPositiveButton("下载更新", (dialog, which) -> openExternalUrl(release.downloadUrl))
                .show();
    }

    private void openExternalUrl(String value) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(value)));
        } catch (Exception error) {
            toast("无法打开下载链接");
        }
    }

    private String currentVersion() {
        try {
            String value = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            return value == null || value.trim().isEmpty() ? "未知" : value.trim();
        } catch (Exception ignored) {
            return "未知";
        }
    }

    private String normalizeVersion(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) normalized = normalized.substring(1);
        int suffix = normalized.indexOf('-');
        if (suffix >= 0) normalized = normalized.substring(0, suffix);
        return normalized;
    }

    private int compareVersions(String first, String second) {
        String[] left = normalizeVersion(first).split("\\.");
        String[] right = normalizeVersion(second).split("\\.");
        int length = Math.max(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int leftValue = versionPart(left, index);
            int rightValue = versionPart(right, index);
            if (leftValue != rightValue) return Integer.compare(leftValue, rightValue);
        }
        return 0;
    }

    private int versionPart(String[] parts, int index) {
        if (index >= parts.length) return 0;
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String formatReleaseNotes(String value) {
        if (value == null) return "";
        StringBuilder formatted = new StringBuilder();
        for (String line : value.replace("\r", "").split("\n")) {
            if (line.startsWith("## [")) continue;
            String cleaned = line.startsWith("### ") ? line.substring(4) : line;
            if (formatted.length() == 0 && cleaned.trim().isEmpty()) continue;
            formatted.append(cleaned).append('\n');
        }
        return formatted.toString().trim();
    }

    private View deviceCard(DeviceProfile profile) {
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(15), dp(14), dp(12), dp(14));
        card.setBackground(round(SURFACE, 15, BORDER, 1));
        card.setOnClickListener(view -> showDashboard(profile, null));

        TextView mark = label("Q", 16, ACCENT, true);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(round(RAISED, 11, BORDER, 1));
        card.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout detail = new LinearLayout(this);
        detail.setOrientation(LinearLayout.VERTICAL);
        detail.setPadding(dp(12), 0, dp(7), 0);
        TextView name = label(profile.name, 14, INK, true);
        name.setMaxLines(1);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detail.addView(name);
        TextView address = label(hostAndPort(profile.baseUrl), 11, MUTED, false);
        address.setPadding(0, dp(4), 0, 0);
        address.setMaxLines(1);
        address.setEllipsize(android.text.TextUtils.TruncateAt.END);
        detail.addView(address);
        card.addView(detail, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView open = label("打开", 12, ACCENT, true);
        open.setPadding(dp(9), dp(8), dp(9), dp(8));
        open.setBackground(round(RAISED, 10, BORDER, 1));
        open.setOnClickListener(view -> showDashboard(profile, null));
        card.addView(open);

        TextView remove = label("移除", 12, lightTheme ? Color.rgb(190, 82, 73) : Color.rgb(239, 138, 112), true);
        remove.setPadding(dp(9), dp(8), dp(9), dp(8));
        remove.setBackground(round(RAISED, 10, BORDER, 1));
        remove.setContentDescription("移除设备 " + profile.name);
        remove.setOnClickListener(view -> confirmRemoveDevice(profile));
        LinearLayout.LayoutParams removeParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        removeParams.leftMargin = dp(6);
        card.addView(remove, removeParams);

        card.setOnLongClickListener(view -> {
            showDeviceActions(profile);
            return true;
        });
        return card;
    }

    private void showDeviceActions(DeviceProfile profile) {
        new AlertDialog.Builder(this)
                .setTitle(profile.name)
                .setItems(new String[]{"重命名设备", "移除设备"}, (dialog, which) -> {
                    if (which == 0) renameDevice(profile);
                    else confirmRemoveDevice(profile);
                })
                .show();
    }

    private void renameDevice(DeviceProfile profile) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(profile.name);
        input.setSelection(input.length());
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setPadding(dp(22), dp(6), dp(22), 0);
        wrapper.addView(input, matchWidth());
        new AlertDialog.Builder(this)
                .setTitle("设备名称")
                .setView(wrapper)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        profile.name = name;
                        saveProfiles();
                        showDevices();
                    }
                })
                .show();
    }

    private void confirmRemoveDevice(DeviceProfile profile) {
        new AlertDialog.Builder(this)
                .setTitle("移除设备？")
                .setMessage("将从此手机删除「" + profile.name + "」及本机保存的配对数据。")
                .setNegativeButton("取消", null)
                .setPositiveButton("移除", (dialog, which) -> {
                    int index = indexOfDevice(profile.baseUrl);
                    if (index >= 0) devices.remove(index);
                    saveProfiles();
                    Uri uri = Uri.parse(profile.baseUrl);
                    String origin = uri.getScheme() + "://" + uri.getAuthority();
                    WebStorage.getInstance().deleteOrigin(origin);
                    showDevices();
                })
                .show();
    }

    private void showManualEntry() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("http://10.6.22.1:43187/remote.html#pair=…");
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setSelectAllOnFocus(true);
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setPadding(dp(22), dp(5), dp(22), 0);
        wrapper.addView(input, matchWidth());
        TextView hint = label("粘贴电脑端的完整配对链接。链接中的密钥只用于添加这台设备。", 11, MUTED, false);
        hint.setPadding(0, dp(10), 0, 0);
        wrapper.addView(hint);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("添加设备")
                .setView(wrapper)
                .setNegativeButton("取消", null)
                .setPositiveButton("连接", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                addDevice(parsePairingLink(input.getText().toString()));
                dialog.dismiss();
            } catch (IllegalArgumentException error) {
                input.setError(error.getMessage());
            }
        }));
        dialog.show();
    }

    private void showDashboard(DeviceProfile profile, String firstPairingUrl) {
        showingDashboard = true;
        activeProfile = profile;
        webError = null;
        destroyWebView();

        FrameLayout root = rootFrame();
        webView = new WebView(this);
        webView.setBackgroundColor(BG);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setDatabaseEnabled(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setDefaultTextEncodingName("UTF-8");
        if (android.os.Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(true);
        webView.setVerticalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(android.webkit.PermissionRequest request) {
                request.deny();
            }
        });
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri next = request.getUrl();
                if (activeProfile != null && sameOrigin(Uri.parse(activeProfile.baseUrl), next)) return false;
                toast("为了保护配对密钥，App 只在已添加的电脑地址内打开页面。");
                return true;
            }

            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                if (loadingLabel != null) loadingLabel.setVisibility(View.VISIBLE);
                scheduleConnectionTimeout();
                if (webError != null && webError.getParent() instanceof ViewGroup) {
                    ((ViewGroup) webError.getParent()).removeView(webError);
                    webError = null;
                }
            }

            @Override public void onPageCommitVisible(WebView view, String url) {
                cancelConnectionTimeout();
                if (loadingLabel != null) loadingLabel.setVisibility(View.GONE);
                startPairingCheck();
                startDeviceNamePrefill();
            }

            @Override public void onPageFinished(WebView view, String url) {
                cancelConnectionTimeout();
                if (loadingLabel != null) loadingLabel.setVisibility(View.GONE);
                startPairingCheck();
                startDeviceNamePrefill();
            }

            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) showWebFailure("无法连接电脑。请确认 Quota Desk 和所在网络正在运行。");
            }

            @Override public void onReceivedSslError(WebView view, SslErrorHandler handler, android.net.http.SslError error) {
                handler.cancel();
                showWebFailure("此设备的 HTTPS 证书验证失败。请检查穿透服务的证书和访问地址。");
            }

            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                showWebFailure("页面进程已退出，请重新加载设备页面。");
                destroyWebView();
                return true;
            }
        });
        root.addView(webView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        loadingLabel = label("正在连接电脑…", 13, MUTED, true);
        loadingLabel.setGravity(Gravity.CENTER);
        loadingLabel.setBackgroundColor(BG);
        root.addView(loadingLabel, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        webView.loadUrl(firstPairingUrl == null ? profile.baseUrl : firstPairingUrl);
    }

    private void showWebFailure(String message) {
        if (!showingDashboard || webView == null) return;
        cancelConnectionTimeout();
        if (webError != null && webError.getParent() instanceof ViewGroup) {
            ((ViewGroup) webError.getParent()).removeView(webError);
        }
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(26), dp(24), dp(26), dp(24));
        panel.setBackground(round(SURFACE, 17, BORDER, 1));
        TextView title = label("暂时无法连接", 18, INK, true);
        title.setGravity(Gravity.CENTER);
        panel.addView(title);
        TextView description = label(message, 13, MUTED, false);
        description.setGravity(Gravity.CENTER);
        description.setLineSpacing(dp(3), 1f);
        description.setPadding(0, dp(10), 0, dp(18));
        panel.addView(description);
        TextView retry = label("重新连接", 13, ACCENT_INK, true);
        retry.setGravity(Gravity.CENTER);
        retry.setMinHeight(dp(45));
        retry.setBackground(round(ACCENT, 12, ACCENT, 0));
        retry.setOnClickListener(view -> {
            if (webView != null) webView.reload();
            else if (activeProfile != null) showDashboard(activeProfile, null);
        });
        panel.addView(retry, matchWidth());
        TextView back = label("返回设备列表", 12, MUTED, true);
        back.setGravity(Gravity.CENTER);
        back.setPadding(0, dp(16), 0, 0);
        back.setOnClickListener(view -> returnToDevices());
        panel.addView(back);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        params.setMargins(dp(22), dp(18), dp(22), dp(18));
        ((ViewGroup) webView.getParent()).addView(panel, params);
        webError = panel;
        if (loadingLabel != null) loadingLabel.setVisibility(View.GONE);
    }

    private void scheduleConnectionTimeout() {
        cancelConnectionTimeout();
        connectionTimeout = () -> {
            if (!showingDashboard || webView == null || loadingLabel == null || loadingLabel.getVisibility() != View.VISIBLE) return;
            webView.stopLoading();
            showWebFailure("连接电脑超过 12 秒。请确认电脑端 Quota Desk 正在运行，并检查手机当前网络、VPN 或内网穿透连接。");
        };
        mainHandler.postDelayed(connectionTimeout, CONNECTION_TIMEOUT_MS);
    }

    private void cancelConnectionTimeout() {
        if (connectionTimeout == null) return;
        mainHandler.removeCallbacks(connectionTimeout);
        connectionTimeout = null;
    }

    /**
     * 本机设备名称：系统"设备名称"（设置→关于手机）→ 蓝牙名称 → 型号码。均无需权限。
     * 电脑端配对接口限制 60 字符，这里提前对齐。
     */
    private String deviceDisplayName() {
        String name = null;
        if (android.os.Build.VERSION.SDK_INT >= 25) {
            try {
                name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
            } catch (Exception ignored) { }
        }
        if (name == null || name.trim().isEmpty()) {
            try {
                name = Settings.Secure.getString(getContentResolver(), "bluetooth_name");
            } catch (Exception ignored) { }
        }
        if (name == null || name.trim().isEmpty()) {
            String model = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL.trim();
            String maker = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER.trim();
            name = !model.isEmpty() ? model : (!maker.isEmpty() ? maker : "Android 手机");
        }
        name = name.replaceAll("\\p{Cntrl}", " ").trim();
        return name.length() > 60 ? name.substring(0, 60).trim() : name;
    }

    /**
     * 配对页加载后，把本机设备名写进"设备名称"输入框作为默认值（用户仍可修改）。
     * React 受控输入必须用原型 setter + input 事件，直接赋值会被状态还原。
     * 只在当前值是网页端默认占位（"Android 手机"等）时写入，且只写一次；表单未挂载时重试约 10 秒。
     */
    private void startDeviceNamePrefill() {
        if (deviceNamePrefill != null || webView == null || !showingDashboard) return;
        String displayName = deviceDisplayName();
        if (displayName.isEmpty()) return;
        String payload;
        try {
            payload = new JSONObject().put("name", displayName).toString();
        } catch (JSONException ignored) {
            return;
        }
        final int[] attempts = {0};
        deviceNamePrefill = new Runnable() {
            @Override public void run() {
                final Runnable self = this;
                if (deviceNamePrefill != self || webView == null || attempts[0] >= 24) {
                    if (deviceNamePrefill == self) deviceNamePrefill = null;
                    return;
                }
                attempts[0]++;
                webView.evaluateJavascript(pairFormPrefillScript(payload), result -> {
                    if (deviceNamePrefill != self || webView == null) return;
                    if ("\"retry\"".equals(result)) mainHandler.postDelayed(self, 400L);
                    else deviceNamePrefill = null;
                });
            }
        };
        deviceNamePrefill.run();
    }

    private String pairFormPrefillScript(String payload) {
        return "(function(payload){try{"
                + "var input=document.getElementById('pair-device-name');"
                + "if(!input)return 'retry';"
                + "if(document.activeElement===input)return 'done';"
                + "var current=(input.value||'').trim();"
                + "if(current&&current!=='Android 手机'&&current!=='iOS 设备'&&current!=='浏览器设备')return 'done';"
                + "var setter=Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype,'value').set;"
                + "if(!setter)return 'done';"
                + "setter.call(input,payload.name);"
                + "input.dispatchEvent(new Event('input',{bubbles:true}));"
                + "return 'done';"
                + "}catch(e){return 'done';}})(" + payload + ")";
    }

    /**
     * 待配对设备的确认轮询：网页端配对成功会把只读令牌写入 localStorage；
     * 另以"配对表单出现过又消失"作为兜底信号（防止令牌 key 变动），二者其一即视为配对完成。
     */
    private void startPairingCheck() {
        if (pairingCheck != null || webView == null || !showingDashboard) return;
        if (activeProfile == null || !activeProfile.pendingPairing) return;
        pairingCheck = new Runnable() {
            @Override public void run() {
                final Runnable self = this;
                if (pairingCheck != self || webView == null || !showingDashboard) {
                    if (pairingCheck == self) pairingCheck = null;
                    return;
                }
                if (activeProfile == null || !activeProfile.pendingPairing) {
                    pairingCheck = null;
                    return;
                }
                webView.evaluateJavascript(
                        "(function(){try{"
                                + "if(localStorage.getItem('quota-desk-remote-token-v1'))return 'paired';"
                                + "var form=document.getElementById('pair-device-name');"
                                + "if(form){window.__qdSawPairForm=true;return 'form';}"
                                + "return window.__qdSawPairForm?'paired':'none';"
                                + "}catch(e){return 'none';}})()",
                        result -> {
                            if (pairingCheck != self || webView == null) return;
                            if ("\"paired\"".equals(result)) {
                                pairingCheck = null;
                                onPairingConfirmed();
                            } else {
                                mainHandler.postDelayed(self, 1200L);
                            }
                        });
            }
        };
        pairingCheck.run();
    }

    private boolean sameOrigin(Uri first, Uri second) {
        String firstScheme = first.getScheme();
        String secondScheme = second.getScheme();
        String firstHost = first.getHost();
        String secondHost = second.getHost();
        if (firstScheme == null || secondScheme == null || firstHost == null || secondHost == null) return false;
        int firstPort = effectivePort(first);
        int secondPort = effectivePort(second);
        return firstScheme.equalsIgnoreCase(secondScheme)
                && firstHost.equalsIgnoreCase(secondHost)
                && firstPort == secondPort;
    }

    private int effectivePort(Uri uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private void returnToDevices() {
        if (activeProfile != null && activeProfile.pendingPairing) toast("未完成配对，这台电脑不会被保存");
        showingDashboard = false;
        destroyWebView();
        showDevices();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void destroyWebView() {
        cancelConnectionTimeout();
        if (deviceNamePrefill != null) {
            mainHandler.removeCallbacks(deviceNamePrefill);
            deviceNamePrefill = null;
        }
        if (pairingCheck != null) {
            mainHandler.removeCallbacks(pairingCheck);
            pairingCheck = null;
        }
        WebView old = webView;
        webView = null;
        loadingLabel = null;
        webError = null;
        if (old == null) return;
        old.stopLoading();
        old.setWebChromeClient(null);
        old.setWebViewClient(new WebViewClient());
        if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        old.destroy();
    }

    private FrameLayout rootFrame() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        ViewCompat.setOnApplyWindowInsetsListener(frame, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            // 键盘弹出时整体让位，避免网页里的输入框被 IME 遮住（API 30+ 有效）
            int ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            view.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime));
            return insets;
        });
        ViewCompat.requestApplyInsets(frame);
        return frame;
    }

    private void applyNativeThemeColors() {
        if (lightTheme) {
            BG = Color.rgb(243, 245, 241);
            SURFACE = Color.rgb(251, 252, 249);
            RAISED = Color.rgb(240, 244, 239);
            BORDER = Color.rgb(223, 228, 223);
            INK = Color.rgb(30, 40, 45);
            MUTED = Color.rgb(120, 129, 136);
            ACCENT = Color.rgb(46, 139, 102);
            ACCENT_INK = Color.WHITE;
        } else {
            BG = Color.rgb(17, 25, 23);
            SURFACE = Color.rgb(25, 34, 31);
            RAISED = Color.rgb(32, 43, 39);
            BORDER = Color.rgb(48, 61, 56);
            INK = Color.rgb(241, 245, 241);
            MUTED = Color.rgb(155, 170, 162);
            ACCENT = Color.rgb(155, 223, 178);
            ACCENT_INK = Color.rgb(20, 39, 28);
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setIncludeFontPadding(true);
        return view;
    }

    private GradientDrawable round(int color, int radiusDp, int strokeColor, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), strokeColor);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private View gap(int heightDp) {
        View space = new View(this);
        space.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return space;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String hostAndPort(String value) {
        Uri uri = Uri.parse(value);
        String host = uri.getHost() == null ? value : uri.getHost();
        return uri.getPort() == -1 ? host : host + ":" + uri.getPort();
    }

    private static final class DeviceProfile {
        String name;
        final String baseUrl;
        boolean pendingPairing;
        DeviceProfile(String name, String baseUrl) {
            this.name = name;
            this.baseUrl = baseUrl;
        }
    }

    private static final class PairingData {
        final String name;
        final String baseUrl;
        final String pairingUrl;
        PairingData(String name, String baseUrl, String pairingUrl) {
            this.name = name;
            this.baseUrl = baseUrl;
            this.pairingUrl = pairingUrl;
        }
    }

    private static final class UpdateRelease {
        final String version;
        final String notes;
        final String downloadUrl;
        UpdateRelease(String version, String notes, String downloadUrl) {
            this.version = version;
            this.notes = notes;
            this.downloadUrl = downloadUrl;
        }
    }
}
