package com.quotadesk.mobile;

import android.content.Context;
import android.net.Uri;
import android.provider.Settings;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 超级岛配置：绑定一台电脑（baseUrl + 配对密钥）、原生配对得到的只读令牌，
 * 以及焦点通知的递增序号，全部持久化在一个 JSON 里。
 */
public final class IslandConfig {
    private static final String PREFS = "quota_desk_mobile";
    private static final String KEY = "island_config_v1";

    public boolean enabled;
    public String baseUrl = "";
    public String deviceName = "";
    public String pairingKey = "";
    public String deviceId = "";
    public String token = "";
    public long tokenPairedAt;
    public long sequence;
    public int intervalMinutes = 5;

    // ---- 显示样式（全部可在设置里切换） ----
    /** 焦点通知卡片模板：auto(=base)/base/chat/highlight/cover/animtext/icontext */
    public String cardTemplate = "auto";
    /** 卡片背景：brand(品牌墨绿)/black(炭黑)/none(跟随系统) */
    public String cardBg = "brand";
    /** 进度条：auto(单账号单条/多账号多条)/single/multi/none */
    public String progressMode = "auto";
    /** 基础卡片上把剩余值显示为彩色徽章 */
    public boolean showBadge = true;
    /** Android 16 实时活动样式：auto/progress(分段进度条)/text(逐账号文本) */
    public String liveStyle = "auto";

    public static IslandConfig load(Context context) {
        IslandConfig config = new IslandConfig();
        String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        if (encoded == null || encoded.trim().isEmpty()) return config;
        try {
            JSONObject json = new JSONObject(encoded);
            config.enabled = json.optBoolean("enabled", false);
            config.baseUrl = json.optString("baseUrl", "");
            config.deviceName = json.optString("deviceName", "");
            config.pairingKey = json.optString("pairingKey", "");
            config.deviceId = json.optString("deviceId", "");
            config.token = json.optString("token", "");
            config.tokenPairedAt = json.optLong("tokenPairedAt", 0L);
            config.sequence = json.optLong("sequence", 0L);
            int interval = json.optInt("intervalMinutes", 5);
            config.intervalMinutes = interval >= 1 && interval <= 120 ? interval : 5;
            config.cardTemplate = nonEmpty(json.optString("cardTemplate", "auto"), "auto");
            config.cardBg = nonEmpty(json.optString("cardBg", "brand"), "brand");
            config.progressMode = nonEmpty(json.optString("progressMode", "auto"), "auto");
            config.showBadge = json.optBoolean("showBadge", true);
            config.liveStyle = nonEmpty(json.optString("liveStyle", "auto"), "auto");
        } catch (JSONException ignored) {
        }
        return config;
    }

    public void save(Context context) {
        try {
            JSONObject json = new JSONObject()
                    .put("enabled", enabled)
                    .put("baseUrl", baseUrl)
                    .put("deviceName", deviceName)
                    .put("pairingKey", pairingKey)
                    .put("deviceId", deviceId)
                    .put("token", token)
                    .put("tokenPairedAt", tokenPairedAt)
                    .put("sequence", sequence)
                    .put("intervalMinutes", intervalMinutes)
                    .put("cardTemplate", cardTemplate)
                    .put("cardBg", cardBg)
                    .put("progressMode", progressMode)
                    .put("showBadge", showBadge)
                    .put("liveStyle", liveStyle);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY, json.toString()).apply();
        } catch (JSONException ignored) {
        }
    }

    /** 电脑端 HTTP 服务的根地址（scheme://authority），API 路径在此基础上拼接 */
    public String origin() {        if (baseUrl == null || baseUrl.isEmpty()) return "";
        Uri uri = Uri.parse(baseUrl);
        String host = uri.getHost();
        if (host == null || host.isEmpty()) return "";
        StringBuilder origin = new StringBuilder(uri.getScheme() == null ? "http" : uri.getScheme())
                .append("://").append(host);
        if (uri.getPort() != -1) origin.append(':').append(uri.getPort());
        return origin.toString();
    }

    private static String nonEmpty(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    /** 本机在电脑端配对设备列表里的稳定标识；电脑端要求 ^[\w-]{8,128}$，首次需要时生成并应由调用方 save */
    public String deviceId() {
        if (deviceId == null || deviceId.isEmpty()) {
            deviceId = "qd-island-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        }
        return deviceId;
    }

    /**
     * 本机设备名称：系统"设备名称" → 蓝牙名称 → 型号码，均无需权限。
     * 电脑端配对接口限制 60 字符，这里提前对齐。
     */
    public static String deviceBaseName(Context context) {
        String name = null;
        if (android.os.Build.VERSION.SDK_INT >= 25) {
            try {
                name = Settings.Global.getString(context.getContentResolver(), Settings.Global.DEVICE_NAME);
            } catch (Exception ignored) {
            }
        }
        if (name == null || name.trim().isEmpty()) {
            try {
                name = Settings.Secure.getString(context.getContentResolver(), "bluetooth_name");
            } catch (Exception ignored) {
            }
        }
        if (name == null || name.trim().isEmpty()) {
            String model = android.os.Build.MODEL == null ? "" : android.os.Build.MODEL.trim();
            String maker = android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER.trim();
            name = !model.isEmpty() ? model : (!maker.isEmpty() ? maker : "Android 手机");
        }
        name = name.replaceAll("\\p{Cntrl}", " ").trim();
        return name.length() > 60 ? name.substring(0, 60).trim() : name;
    }

    /** 超级岛在电脑端配对设备列表里的显示名（baseName + 后缀，总长仍 ≤60） */
    public static String pairedDeviceName(Context context) {
        String base = deviceBaseName(context);
        String suffix = " ·超级岛";
        if (base.length() + suffix.length() > 60) base = base.substring(0, 60 - suffix.length()).trim();
        return base + suffix;
    }
}
