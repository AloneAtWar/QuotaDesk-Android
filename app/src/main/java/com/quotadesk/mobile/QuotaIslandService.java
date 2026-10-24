package com.quotadesk.mobile;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Locale;

/**
 * 超级岛监控前台服务：按固定间隔轮询电脑端 /api/v1/quota/snapshot，
 * 把额度快照翻译成焦点通知发出（经 XmsfBypass 绕过白名单）。
 * 通知刷新复用同一 notifyId/递增 sequence，超级岛上表现为原地更新。
 */
public final class QuotaIslandService extends Service {
    public static final String ACTION_STOP = "com.quotadesk.mobile.action.ISLAND_STOP";
    public static final int NOTIFICATION_FGS = 2001;
    public static final int NOTIFICATION_ISLAND = 2002;

    private HandlerThread workerThread;
    private Handler worker;
    private Runnable scheduledPoll;

    @Override
    public void onCreate() {
        super.onCreate();
        FocusNotificationFactory.ensureChannels(this);
        workerThread = new HandlerThread("QuotaDesk-island");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            IslandConfig config = IslandConfig.load(this);
            config.enabled = false;
            config.save(this);
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        startInForeground("正在获取额度…");
        schedulePoll(200L);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (scheduledPoll != null && worker != null) worker.removeCallbacks(scheduledPoll);
        if (workerThread != null) workerThread.quitSafely();
        // 收尾时兜底恢复 XMSF 网络，防止进程结束时恰好停在断网窗口里
        final Context app = getApplicationContext();
        new Thread(() -> XmsfBypass.ensureRestored(app), "QuotaDesk-island-restore").start();
        super.onDestroy();
    }

    private void startInForeground(String status) {
        Notification notification = buildFgsNotification(status);
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_FGS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_FGS, notification);
        }
    }

    private void schedulePoll(long delayMs) {
        if (worker == null) return;
        if (scheduledPoll != null) worker.removeCallbacks(scheduledPoll);
        scheduledPoll = this::poll;
        worker.postDelayed(scheduledPoll, delayMs);
    }

    /** 一轮完整循环：自愈断网状态 → （需要时）配对 → 拉快照 → 发焦点通知；全部在工作线程执行 */
    private void poll() {
        IslandConfig config = IslandConfig.load(this);
        if (!config.enabled) {
            stopSelf();
            return;
        }
        XmsfBypass.ensureRestored(this);
        String summary;
        try {
            config = ensurePaired(config);
            FocusNotificationFactory.IslandData data = fetchAndNotify(config);
            summary = "运行中 · " + FocusNotificationFactory.summarize(data);
        } catch (QuotaApi.ApiException authError) {
            // 令牌被电脑端移除/重置：还有配对密钥就自动重新配对再试一次
            if (isAuthError(authError) && !config.pairingKey.isEmpty()) {
                try {
                    config.token = "";
                    config = ensurePaired(config);
                    FocusNotificationFactory.IslandData data = fetchAndNotify(config);
                    summary = "运行中(已重新配对) · " + FocusNotificationFactory.summarize(data);
                } catch (Exception retryError) {
                    summary = "自动重新配对失败：" + brief(retryError);
                }
            } else {
                summary = "访问被拒绝：" + brief(authError);
            }
        } catch (Exception error) {
            summary = "连接失败，下轮自动重试（" + brief(error) + "）";
        }
        updateFgs(summary);
        schedulePoll(config.intervalMinutes * 60_000L);
    }

    private IslandConfig ensurePaired(IslandConfig config) throws IOException {
        if (!config.token.isEmpty()) return config;
        if (config.pairingKey.isEmpty()) {
            throw new IOException("还没有配对，请在 App 的超级岛设置里完成配对");
        }
        return doPair(config);
    }

    private IslandConfig doPair(IslandConfig config) throws IOException {
        QuotaApi.PairResult result = QuotaApi.pair(config.origin(), config.pairingKey,
                config.deviceId(), IslandConfig.pairedDeviceName(this));
        config.token = result.token;
        config.tokenPairedAt = System.currentTimeMillis();
        config.save(this);
        return config;
    }

    private FocusNotificationFactory.IslandData fetchAndNotify(IslandConfig config) throws IOException {
        JSONObject snapshot = QuotaApi.fetchSnapshot(config.origin(), config.token);
        FocusNotificationFactory.IslandData data = FocusNotificationFactory.parse(snapshot);
        config.sequence++;
        config.save(this);
        Notification notification = FocusNotificationFactory.build(this, data, config.sequence, config.sequence <= 1, config);
        XmsfBypass.notifyNow(this, NOTIFICATION_ISLAND, notification);
        return data;
    }

    private boolean isAuthError(Exception error) {
        return error instanceof QuotaApi.ApiException
                && (((QuotaApi.ApiException) error).status == 401 || ((QuotaApi.ApiException) error).status == 403);
    }

    private String brief(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
        return message.length() > 60 ? message.substring(0, 60) + "…" : message;
    }

    private void updateFgs(String status) {
        android.app.NotificationManager manager = getSystemService(android.app.NotificationManager.class);
        if (manager != null) manager.notify(NOTIFICATION_FGS, buildFgsNotification(status));
    }

    private Notification buildFgsNotification(String status) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, FocusNotificationFactory.CHANNEL_SERVICE)
                : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        Intent stop = new Intent(this, QuotaIslandService.class).setAction(ACTION_STOP);
        return builder
                .setSmallIcon(R.drawable.quotadesk_launcher)
                .setContentTitle("超级岛额度监控")
                .setContentText(String.format(Locale.CHINA, "%s · 每 %d 分钟刷新", status, IslandConfig.load(this).intervalMinutes))
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(this, 1, open,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null, "停止监控",
                        PendingIntent.getService(this, 2, stop,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
    }

    // ---- 外部控制 --------------------------------------------------------------

    public static void start(Context context) {
        Intent intent = new Intent(context, QuotaIslandService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, QuotaIslandService.class));
        android.app.NotificationManager manager = context.getSystemService(android.app.NotificationManager.class);
        if (manager != null) manager.cancel(NOTIFICATION_ISLAND);
    }
}
