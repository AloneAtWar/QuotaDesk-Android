package com.quotadesk.mobile;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import rikka.shizuku.Shizuku;

/**
 * 超级岛一键自检：设备能力、协议、白名单状态、Shizuku 链路、断网绕过是否真正生效，
 * 全部结果（含真实异常信息）汇总成可复制的报告，用于定位"出不了岛"的原因。
 * 必须在后台线程执行（含多个跨进程查询）。
 */
public final class IslandDiagnostics {
    private IslandDiagnostics() {
    }

    public static String run(Context context) {
        Context app = context.getApplicationContext();
        StringBuilder report = new StringBuilder();

        line(report, "设备", Build.MANUFACTURER + " " + Build.MODEL
                + "（Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT + "）");
        String osName = prop("ro.mi.os.version.name", prop("ro.miui.ui.version.name", ""));
        line(report, "系统版本", osName.isEmpty() ? "非 MIUI/HyperOS" : osName);
        line(report, "焦点通知协议", String.valueOf(FocusNotificationFactory.focusProtocol(app)));
        line(report, "岛硬件 persist.sys.feature.island", String.valueOf(propBool("persist.sys.feature.island")));

        Boolean allowed = XmsfBypass.probeCanShowFocus(app);
        line(report, "白名单 canShowFocus（未绕过）",
                allowed == null ? "查询失败（可能非小米 ROM）" : String.valueOf(allowed));

        line(report, "Shizuku 运行", String.valueOf(XmsfBypass.binderAlive()));
        if (XmsfBypass.binderAlive()) {
            try {
                line(report, "Shizuku 版本", String.valueOf(Shizuku.getVersion()));
            } catch (Throwable error) {
                line(report, "Shizuku 版本", "读取失败：" + XmsfBypass.describe(error));
            }
        }
        line(report, "Shizuku 已授权", String.valueOf(XmsfBypass.permissionGranted()));
        int uid = XmsfBypass.xmsfUidOf(app);
        line(report, "小米服务框架 XMSF", uid >= 0 ? "已安装（uid=" + uid + "）" : "未安装（非国行小米 ROM？）");

        if (Build.VERSION.SDK_INT >= 36) {
            boolean promoted = app.checkSelfPermission("android.permission.POST_PROMOTED_NOTIFICATIONS")
                    == PackageManager.PERMISSION_GRANTED;
            line(report, "实时活动权限（Live Updates）", promoted
                    ? "已授予 —— 原生路径可用"
                    : "未授予 —— 到 设置→应用→Quota Desk→通知 里开启「已提升的通知/推广通知」");
        }

        if (!XmsfBypass.ready()) {
            line(report, "断网绕过测试", "跳过：Shizuku 未运行或未授权");
        } else {
            boolean blocked = false;
            try {
                XmsfBypass.setXmsfNetworkingForTest(app, false);
                blocked = true;
                line(report, "切断 XMSF 网络", "成功");
            } catch (Throwable error) {
                line(report, "切断 XMSF 网络", "失败：" + XmsfBypass.describe(error));
            }
            if (blocked) {
                try {
                    Thread.sleep(300L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                Boolean inWindow = XmsfBypass.probeCanShowFocus(app);
                line(report, "白名单 canShowFocus（断网窗口内）",
                        inWindow == null ? "查询失败"
                                : inWindow ? "true —— 绕过生效，岛数据会被接受" : "false —— 校验仍未放行（XMSF 缓存或链路不对）");
            }
            try {
                XmsfBypass.setXmsfNetworkingForTest(app, true);
                line(report, "恢复 XMSF 网络", "成功");
            } catch (Throwable error) {
                line(report, "恢复 XMSF 网络", "失败：" + XmsfBypass.describe(error));
            }
        }

        String sample = FocusNotificationFactory.sampleParamJson(app);
        line(report, "发送的焦点 JSON（样例）", sample.isEmpty() ? "生成失败" : sample);

        return report.toString();
    }

    private static void line(StringBuilder report, String key, String value) {
        report.append(key).append("：").append(value).append('\n');
    }

    private static String prop(String key, String fallback) {
        try {
            Object value = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class).invoke(null, key);
            return value instanceof String && !((String) value).isEmpty() ? (String) value : fallback;
        } catch (Throwable error) {
            return fallback;
        }
    }

    private static boolean propBool(String key) {
        try {
            Object value = Class.forName("android.os.SystemProperties")
                    .getMethod("getBoolean", String.class, boolean.class).invoke(null, key, false);
            return Boolean.TRUE.equals(value);
        } catch (Throwable error) {
            return false;
        }
    }
}
