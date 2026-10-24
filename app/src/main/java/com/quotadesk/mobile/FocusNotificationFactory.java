package com.quotadesk.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 把 /api/v1/quota/snapshot 快照翻译成澎湃OS 焦点通知（超级岛）：
 * - 挑选"最紧张"的窗口作为主展示（剩余比例最低的百分比窗口优先）
 * - miui.focus.param 走 param_v2 模板（baseInfo 卡片 + progressInfo 进度条 + param_island 岛数据）
 * - 非小米 / 无焦点通知协议时退化为带进度条的普通通知
 * 字段口径参照官方文档与 HyperIsland-ToolKit 的模型定义。
 */
public final class FocusNotificationFactory {
    public static final String CHANNEL_ISLAND = "quota_island";
    public static final String CHANNEL_SERVICE = "quota_island_service";
    private static final String NOTIFY_ID = "quotadesk_island";
    /** 焦点通知卡片的存活时长（分钟），须大于轮询间隔，每轮刷新顺延 */
    private static final int CARD_TIMEOUT_MINUTES = 15;
    /** 超级岛停留时长（秒），须大于轮询间隔 */
    private static final int ISLAND_TIMEOUT_SECONDS = 1200;

    private FocusNotificationFactory() {
    }

    // ---- 支持性探测 ------------------------------------------------------------

    /** 焦点通知协议版本：1=OS1 模板 2=OS2 焦点通知 3=OS3 超级岛，0=不支持/未知 */
    public static int focusProtocol(Context context) {
        try {
            return Settings.System.getInt(context.getContentResolver(), "notification_focus_protocol", 0);
        } catch (Throwable error) {
            return 0;
        }
    }

    public static boolean isXiaomi() {
        return "Xiaomi".equalsIgnoreCase(android.os.Build.MANUFACTURER)
                || "Xiaomi".equalsIgnoreCase(android.os.Build.BRAND);
    }

    /**
     * 焦点通知（含超级岛）extras 是否要带上：小米设备一律带上，
     * 协议探测读不到（返回 0）不代表不支持——不能据此丢弃焦点字段，否则永远出不了岛。
     */
    public static boolean focusSupported(Context context) {
        return isXiaomi();
    }

    // ---- 数据挑选 --------------------------------------------------------------

    public static final class QuotaRow {
        public final String account;
        public final String windowKey;
        public final String windowLabel;
        public final boolean percentage;
        /** 百分比窗口：0-100 的剩余比例；非百分比窗口为 -1 */
        public final int remainingPct;
        /** 余额类窗口的展示文本（如 "¥12.30"），百分比窗口为 null */
        public final String balanceText;
        public final String resetText;

        QuotaRow(String account, String windowKey, boolean percentage, int remainingPct,
                 String balanceText, String resetText) {
            this.account = account;
            this.windowKey = windowKey;
            this.windowLabel = windowLabel(windowKey);
            this.percentage = percentage;
            this.remainingPct = remainingPct;
            this.balanceText = balanceText;
            this.resetText = resetText;
        }

        public String valueText() {
            return percentage ? "剩" + remainingPct + "%" : balanceText;
        }
    }

    public static final class IslandData {
        public final List<QuotaRow> rows;
        public final boolean empty;

        IslandData(List<QuotaRow> rows) {
            this.rows = rows;
            this.empty = rows.isEmpty();
        }

        public QuotaRow primary() {
            return rows.isEmpty() ? null : rows.get(0);
        }
    }

    /** 解析快照：跳过停用账号，每个账号选最短周期的可用窗口，按剩余比例升序（最紧张的在前），最多取 3 行 */
    public static IslandData parse(JSONObject snapshot) {
        List<QuotaRow> rows = new ArrayList<>();
        if (snapshot != null) {
            JSONArray accounts = snapshot.optJSONArray("accounts");
            if (accounts != null) {
                for (int i = 0; i < accounts.length(); i++) {
                    JSONObject account = accounts.optJSONObject(i);
                    if (account == null || account.optBoolean("disabled", false)) continue;
                    QuotaRow row = pickRow(account);
                    if (row != null) rows.add(row);
                }
            }
        }
        rows.sort(Comparator
                .comparingInt((QuotaRow row) -> row.percentage ? 0 : 1)
                .thenComparingInt(row -> row.percentage ? row.remainingPct : Integer.MAX_VALUE));
        if (rows.size() > 4) rows = new ArrayList<>(rows.subList(0, 4));
        return new IslandData(rows);
    }

    private static QuotaRow pickRow(JSONObject account) {
        String name = account.optString("name", "").trim();
        if (name.isEmpty()) name = account.optString("providerName", "账号");
        JSONArray windows = account.optJSONArray("windows");
        if (windows == null) return null;
        JSONObject best = null;
        int bestPriority = Integer.MAX_VALUE;
        JSONObject fallback = null;
        for (int i = 0; i < windows.length(); i++) {
            JSONObject window = windows.optJSONObject(i);
            if (window == null || !window.optBoolean("available", true)) continue;
            String key = window.optString("key", "");
            Double remaining = optDouble(window, "remaining");
            Double total = optDouble(window, "total");
            boolean percentageWindow = remaining != null && total != null && total > 0 && !"balance".equals(key);
            if (percentageWindow) {
                int priority = windowPriority(key);
                if (priority < bestPriority) {
                    bestPriority = priority;
                    best = window;
                }
            } else if (fallback == null && optDouble(window, "amount") != null) {
                fallback = window;
            }
        }
        if (best == null) best = fallback;
        if (best == null) return null;
        String key = best.optString("key", "window");
        Double remaining = optDouble(best, "remaining");
        Double total = optDouble(best, "total");
        if (remaining != null && total != null && total > 0 && !"balance".equals(key)) {
            int pct = (int) Math.round(remaining / total * 100.0);
            pct = Math.max(0, Math.min(100, pct));
            return new QuotaRow(name, key, true, pct, null, resetText(parseIso(best.optString("resetAt", null))));
        }
        Double amount = optDouble(best, "amount");
        Double limit = optDouble(best, "limitAmount");
        String unit = best.optString("unit", "");
        String text = formatAmount(amount) + (limit != null && limit > 0 ? " / " + formatAmount(limit) : "")
                + (unit.isEmpty() ? "" : " " + unit);
        int pct = limit != null && limit > 0 ? (int) Math.round(amount / limit * 100.0) : -1;
        pct = Math.max(0, Math.min(100, pct));
        return new QuotaRow(name, key, false, pct, text, resetText(parseIso(best.optString("resetAt", null))));
    }

    /** 窗口周期从短到长的展示优先级；未知窗口排最后 */
    private static int windowPriority(String key) {
        String[] order = {"five_hour", "daily", "weekly", "monthly", "yearly",
                "mimo_plan", "gemini_pro", "gemini_flash", "gemini_flash_lite"};
        for (int i = 0; i < order.length; i++) {
            if (order[i].equals(key)) return i;
        }
        return order.length;
    }

    private static String windowLabel(String key) {
        switch (key == null ? "" : key) {
            case "five_hour": return "5 小时";
            case "daily": return "1 天";
            case "weekly": return "7 天";
            case "monthly": return "1 个月";
            case "yearly": return "1 年";
            case "balance": return "余额";
            case "mimo_plan": return "Credits";
            case "gemini_pro": return "Pro";
            case "gemini_flash": return "Flash";
            case "gemini_flash_lite": return "Flash Lite";
            default: return key == null || key.isEmpty() ? "额度" : key;
        }
    }

    private static Double optDouble(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof Number) return ((Number) value).doubleValue();
        return null;
    }

    private static String formatAmount(double value) {
        if (value == Math.rint(value)) return String.format(Locale.CHINA, "%.0f", value);
        return String.format(Locale.CHINA, "%.2f", value);
    }

    /** ISO 8601 → 时间戳；兼容毫秒精度/无精度、Z 后缀与 ±HH:MM 偏移 */
    private static long parseIso(String value) {
        if (value == null || value.trim().isEmpty()) return 0L;
        String text = value.trim();
        if (text.endsWith("Z")) text = text.substring(0, text.length() - 1) + "+0000";
        text = text.replaceAll("([+\\-]\\d\\d):(\\d\\d)$", "$1$2");
        text = text.replaceAll("(\\.\\d{3})\\d+", "$1");
        String[] patterns = {"yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ"};
        for (String pattern : patterns) {
            try {
                return new SimpleDateFormat(pattern, Locale.US).parse(text).getTime();
            } catch (ParseException ignored) {
            }
        }
        return 0L;
    }

    private static String resetText(long resetAt) {
        if (resetAt <= 0L) return "";
        long delta = resetAt - System.currentTimeMillis();
        if (delta <= 0L) return "即将重置";
        long hours = delta / 3_600_000L;
        long minutes = (delta % 3_600_000L) / 60_000L;
        if (hours >= 48L) return hours / 24L + " 天后重置";
        if (hours >= 1L) return hours + " 小时" + (minutes > 0L ? " " + minutes + " 分" : "") + "后重置";
        if (minutes >= 1L) return minutes + " 分后重置";
        return "即将重置";
    }

    // ---- 通知构建 --------------------------------------------------------------

    public static void ensureChannels(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return;
        // 渠道的 importance 等设定只在首次创建时生效，删除重建保证设置变更能落地
        manager.deleteNotificationChannel(CHANNEL_ISLAND);
        NotificationChannel island = new NotificationChannel(CHANNEL_ISLAND,
                "超级岛额度", NotificationManager.IMPORTANCE_DEFAULT);
        island.setDescription("显示额度剩余与重置倒计时（焦点通知/超级岛）");
        island.setSound(null, null);
        island.enableVibration(false);
        island.enableLights(false);
        manager.createNotificationChannel(island);
        NotificationChannel service = new NotificationChannel(CHANNEL_SERVICE,
                "超级岛监控", NotificationManager.IMPORTANCE_MIN);
        service.setDescription("超级岛额度监控的常驻通知");
        manager.createNotificationChannel(service);
    }

    /** 服务运行状态摘要，用于前台服务通知的正文 */
    public static String summarize(IslandData data) {
        if (data.empty) return "电脑端暂无可展示的账号";
        QuotaRow primary = data.primary();
        return primary.account + " " + primary.valueText();
    }

    /** 按剩余量告警配色：紧张红 → 偏低琥珀 → 充足绿；余额类始终绿 */
    private static String urgencyColor(QuotaRow row) {
        if (row == null || !row.percentage) return "#2E8B66";
        if (row.remainingPct < 20) return "#E5484D";
        if (row.remainingPct < 50) return "#F2A93B";
        return "#2E8B66";
    }

    /** 告警色的亮色变体：焦点卡片使用深色底，颜色需要更亮才看得清 */
    private static String urgencyColorBright(QuotaRow row) {
        if (row == null || !row.percentage) return "#6FD69E";
        if (row.remainingPct < 20) return "#FF6B66";
        if (row.remainingPct < 50) return "#FFC24B";
        return "#6FD69E";
    }

    // 深色品牌卡片配色（背景色接管亮暗两种模式，文字固定用亮色系）
    private static final String CARD_BG = "#132820";
    private static final String CARD_TITLE = "#F0FAF4";
    private static final String CARD_SUB = "#9FD4B5";
    private static final String CARD_CONTENT = "#CFEBDD";
    private static final String CARD_SUBCONTENT = "#8CC1A4";
    private static final String CARD_PROGRESS = "#6FD69E";
    private static final String CARD_TRACK = "#3A4A42";

    /** 单账号一行的明细文案："账号：5 小时 剩42%（1 小时后重置）" */
    private static String rowLine(QuotaRow row) {
        StringBuilder line = new StringBuilder(row.account).append("：")
                .append(row.windowLabel).append(' ').append(row.valueText());
        if (!row.resetText.isEmpty()) line.append("（").append(row.resetText).append("）");
        return line.toString();
    }

    /**
     * @param requestFloat 本条是否以展开态出现（首次展示/手动预览时为 true，
     *                     周期刷新为 false，避免每 5 分钟弹一次打断使用）
     */
    public static Notification build(Context context, IslandData data, long sequence, boolean requestFloat) {
        QuotaRow primary = data.primary();
        boolean multi = data.rows.size() > 1;
        String title = primary != null ? primary.account : "Quota Desk";
        String content = primary != null
                ? primary.windowLabel + (primary.percentage ? "窗口剩余 " + primary.remainingPct + "%" : " " + primary.balanceText)
                : "电脑端暂无可展示的账号";
        String reset = primary != null ? primary.resetText : "";
        String ticker = primary != null ? primary.account + " " + primary.valueText() : "Quota Desk";

        Notification.Builder builder = android.os.Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(context, CHANNEL_ISLAND)
                : new Notification.Builder(context);
        builder.setSmallIcon(R.drawable.quotadesk_launcher)
                .setContentTitle(title)
                .setContentText(reset.isEmpty() ? content : content + " · " + reset)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(tapIntent(context))
                .setColor(android.graphics.Color.parseColor(urgencyColor(primary)));
        if (primary != null && primary.remainingPct >= 0) {
            builder.setProgress(100, primary.remainingPct, false);
        }
        // Android 16+ 原生实时活动(Live Updates)：HyperOS 3 会把它画进超级岛。
        // 国行 HyperOS 3.0.3xx 上小米焦点通知路径对第三方静默失效，这条原生路径是唯一能上岛的方式，
        // 与 miui.focus.param 双信号并存，由系统选择用哪条（参照 HyperBridge LiveUpdateTranslator）。
        // 框架只有 setShortCriticalText 有公开方法；promoted 请求在 androidx 里也是纯 extras 键实现。
        if (android.os.Build.VERSION.SDK_INT >= 36) {
            String shortCritical = primary != null
                    ? (primary.percentage ? "剩" + primary.remainingPct + "%" : primary.balanceText)
                    : "Quota Desk";
            Bundle liveExtras = new Bundle();
            liveExtras.putBoolean("android.requestPromotedOngoing", true);
            liveExtras.putString("android.shortCriticalText", shortCritical);
            builder.addExtras(liveExtras);
            if (primary != null && primary.remainingPct >= 0) {
                // 实时活动的招牌样式：分段进度条，剩余段按告警配色，游标落在已用/剩余分界
                Notification.ProgressStyle style = new Notification.ProgressStyle();
                style.addProgressSegment(new Notification.ProgressStyle.Segment(primary.remainingPct)
                        .setColor(android.graphics.Color.parseColor(urgencyColorBright(primary))));
                style.addProgressSegment(new Notification.ProgressStyle.Segment(100 - primary.remainingPct)
                        .setColor(android.graphics.Color.parseColor(CARD_TRACK)));
                style.setProgress(100 - primary.remainingPct);
                builder.setStyle(style);
                if (multi) builder.setSubText(othersSummary(data));
            } else {
                // 纯余额类没有比例可画，退回逐账号文本明细
                builder.setStyle(new Notification.BigTextStyle()
                        .setBigContentTitle(title)
                        .bigText(bigText(data, content, reset)));
            }
        }
        if (focusSupported(context)) {
            builder.addExtras(focusExtras(context, data, sequence, requestFloat, ticker));
        }
        return builder.build();
    }

    /** 其余账号的单行摘要："B 剩78% · C 剩5%" */
    private static String othersSummary(IslandData data) {
        StringBuilder summary = new StringBuilder();
        for (int i = 1; i < data.rows.size(); i++) {
            QuotaRow row = data.rows.get(i);
            if (summary.length() > 0) summary.append(" · ");
            summary.append(row.account).append(' ').append(row.valueText());
        }
        return summary.toString();
    }

    /** 实时活动展开态的正文：每个账号一行明细 */
    private static String bigText(IslandData data, String content, String reset) {
        if (data.rows.isEmpty()) return content;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < data.rows.size(); i++) {
            if (i > 0) text.append('\n');
            text.append(rowLine(data.rows.get(i)));
        }
        return text.toString();
    }

    /**
     * 组装 miui.focus.param / miui.focus.pics：
     * JSON 根节点 param_v2，卡片走 baseInfo+progressInfo，超级岛走 param_island
     * （协议 2 的设备会忽略 param_island，仅显示状态栏/通知栏/AOD 形态）。
     */
    static Bundle focusExtras(Context context, IslandData data, long sequence, boolean requestFloat, String ticker) {
        QuotaRow primary = data.primary();
        // 探测不到协议（0/1）不代表不支持：按超级岛（3）目标发送，不支持的 ROM 会自行忽略
        int detected = focusProtocol(context);
        int protocol = detected >= 2 ? Math.min(detected, 3) : 3;
        try {
            // 深色品牌卡片：背景色接管亮暗两种模式，文字固定亮色系；剩余值做成彩色徽章
            JSONObject baseInfo = new JSONObject()
                    .put("type", 1)
                    .put("title", primary != null ? primary.account : "Quota Desk")
                    .put("subTitle", primary != null ? primary.windowLabel + "窗口" : "额度")
                    .put("specialTitle", primary != null ? primary.valueText() : null)
                    .put("colorSpecialTitle", "#FFFFFF")
                    .put("colorSpecialTitleDark", "#FFFFFF")
                    .put("colorSpecialBg", urgencyColorBright(primary))
                    .put("colorSpecialBgDark", urgencyColorBright(primary))
                    .put("colorTitle", CARD_TITLE)
                    .put("colorTitleDark", CARD_TITLE)
                    .put("colorSubTitle", CARD_SUB)
                    .put("colorSubTitleDark", CARD_SUB);
            if (primary != null) {
                String line = primary.percentage ? "已用 " + (100 - primary.remainingPct) + "%" : "";
                if (!primary.resetText.isEmpty()) line += (line.isEmpty() ? "" : " · ") + primary.resetText;
                if (line.isEmpty()) line = "余额 " + primary.balanceText;
                baseInfo.put("content", line);
                String others = othersSummary(data);
                if (!others.isEmpty()) baseInfo.put("subContent", others);
            } else {
                baseInfo.put("content", "电脑端暂无可展示的账号");
            }
            baseInfo.put("colorContent", CARD_CONTENT)
                    .put("colorContentDark", CARD_CONTENT)
                    .put("colorSubContent", CARD_SUBCONTENT)
                    .put("colorSubContentDark", CARD_SUBCONTENT);

            JSONObject paramV2 = new JSONObject()
                    .put("protocol", protocol)
                    .put("business", "quotadesk")
                    .put("updatable", true)
                    .put("reopen", "reopen")
                    .put("ticker", ticker)
                    .put("tickerPic", "miui.focus.pic_ticker")
                    .put("aodTitle", ticker)
                    .put("aodPic", "miui.focus.pic_aod")
                    .put("notifyId", NOTIFY_ID)
                    .put("sequence", sequence)
                    .put("timeout", CARD_TIMEOUT_MINUTES)
                    .put("isShowNotification", true)
                    .put("islandFirstFloat", requestFloat)
                    .put("enableFloat", requestFloat)
                    .put("bgInfo", new JSONObject()
                            .put("type", 1)
                            .put("colorBg", CARD_BG))
                    .put("baseInfo", baseInfo);
            // 进度组件：单账号用单进度条；多账号切换为逐账号带标题的多进度条（最多 3 条，颜色按告警分级）
            if (data.rows.size() > 1) {
                JSONArray multiProgress = new JSONArray();
                int bars = 0;
                for (QuotaRow row : data.rows) {
                    if (bars >= 3 || row.remainingPct < 0) continue;
                    multiProgress.put(new JSONObject()
                            .put("title", row.account + " " + row.valueText())
                            .put("progress", row.remainingPct)
                            .put("color", urgencyColorBright(row)));
                    bars++;
                }
                if (multiProgress.length() > 0) paramV2.put("multiProgressInfo", multiProgress);
            } else if (primary != null && primary.remainingPct >= 0) {
                paramV2.put("progressInfo", new JSONObject()
                        .put("progress", primary.remainingPct)
                        .put("colorProgress", CARD_PROGRESS));
            }

            // 大岛展开态：左栏放最紧张的账号（图标+数值+倒计时），第二个账号放右栏，更多账号折叠计数
            String islandTitle = primary != null ? primary.account + " · " + primary.valueText() : "Quota Desk";
            String leftContent;
            if (primary == null) {
                leftContent = "暂无可展示的账号";
            } else {
                leftContent = primary.windowLabel + "窗口";
                if (!primary.resetText.isEmpty()) leftContent += " · " + primary.resetText;
            }
            JSONObject bigArea = new JSONObject()
                    .put("imageTextInfoLeft", new JSONObject()
                            .put("type", 1)
                            .put("picInfo", new JSONObject()
                                    .put("type", 1)
                                    .put("pic", "miui.focus.pic_island"))
                            .put("textInfo", new JSONObject()
                                    .put("title", islandTitle)
                                    .put("content", leftContent)));
            if (data.rows.size() > 1) {
                QuotaRow second = data.rows.get(1);
                String rightContent = second.windowLabel + "窗口";
                if (data.rows.size() > 2) rightContent += " · 另 " + (data.rows.size() - 2) + " 个账号";
                else if (!second.resetText.isEmpty()) rightContent += " · " + second.resetText;
                bigArea.put("imageTextInfoRight", new JSONObject()
                        .put("type", 2)
                        .put("textInfo", new JSONObject()
                                .put("title", second.account + " · " + second.valueText())
                                .put("content", rightContent)));
            }
            JSONObject island = new JSONObject()
                    .put("islandProperty", 1)
                    .put("islandPriority", 2)
                    .put("islandTimeout", ISLAND_TIMEOUT_SECONDS)
                    .put("highlightColor", urgencyColor(primary))
                    .put("bigIslandArea", bigArea)
                    .put("smallIslandArea", new JSONObject()
                            .put("picInfo", new JSONObject()
                                    .put("type", 1)
                                    .put("pic", "miui.focus.pic_island")));
            paramV2.put("param_island", island);

            Bundle pics = new Bundle();
            Icon icon = Icon.createWithResource(context, R.drawable.quotadesk_launcher);
            pics.putParcelable("miui.focus.pic_island", icon);
            pics.putParcelable("miui.focus.pic_ticker", icon);
            pics.putParcelable("miui.focus.pic_aod", icon);

            Bundle extras = new Bundle();
            extras.putString("miui.focus.param", new JSONObject().put("param_v2", paramV2).toString());
            extras.putBundle("miui.focus.pics", pics);
            return extras;
        } catch (Exception error) {
            // JSON 组装失败时退回普通通知，绝不能因焦点字段让整条通知发不出去
            return new Bundle();
        }
    }

    private static PendingIntent tapIntent(Context context) {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** 诊断用：用样例数据生成一份真实的 miui.focus.param JSON，便于核对字段口径 */
    static String sampleParamJson(Context context) {
        List<QuotaRow> rows = new ArrayList<>();
        rows.add(new QuotaRow("示例账号", "five_hour", true, 42, null, "1 小时 23 分后重置"));
        rows.add(new QuotaRow("示例账号B", "weekly", true, 78, null, ""));
        Bundle extras = focusExtras(context, new IslandData(rows), 1L, true, "示例账号 剩42%");
        return extras.getString("miui.focus.param", "");
    }
}
