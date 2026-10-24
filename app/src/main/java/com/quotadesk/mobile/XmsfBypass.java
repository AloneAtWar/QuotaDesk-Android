package com.quotadesk.mobile;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

/**
 * 用 Shizuku 的特权身份（adb 或 root）临时切断小米服务框架 com.xiaomi.xmsf 的网络：
 * XMSF 负责焦点通知白名单的联网鉴权，断网期间校验默认放行（fail-open），
 * 在这个窗口里发出携带 miui.focus.param 的通知即可绕过白名单，随后立刻恢复 XMSF 网络。
 * 时序与开源项目 HyperBridge 相同：断网 → 50ms → notify → 1s 后恢复。
 */
public final class XmsfBypass {
    private static final String XMSF_PACKAGE = "com.xiaomi.xmsf";
    /** OEM_DENY 防火墙链；对未单独设规则的 uid 默认放行 */
    private static final int OEM_DENY_CHAIN = 9;
    private static final int RULE_ALLOW = 0;
    private static final int RULE_DENY = 2;
    private static final long BLOCK_SETTLE_MS = 50L;
    private static final long RESTORE_DELAY_MS = 1_000L;

    /** (serviceName, stubClass) 两条后端：connectivity 优先，network_management 兜底 */
    private static final String[][] BACKENDS = {
            {Context.CONNECTIVITY_SERVICE, "android.net.IConnectivityManager$Stub"},
            {"network_management", "android.os.INetworkManagementService$Stub"},
    };

    private static final Object LOCK = new Object();
    private static final Handler RESTORE_HANDLER;
    /** 已提交但尚未执行的恢复任务；非 null 表示 XMSF 仍处于断网窗口内 */
    private static Runnable pendingRestore;

    /** Shizuku 重启后旧 binder 代理会失效，按 stub 类名缓存并在异常时清除重建 */
    private static final ConcurrentHashMap<String, Object> SERVICES = new ConcurrentHashMap<>();

    static {
        HandlerThread thread = new HandlerThread("QuotaDesk-xmsf");
        thread.start();
        RESTORE_HANDLER = new Handler(thread.getLooper());
    }

    private XmsfBypass() {
    }

    /** 最近一次 notifyNow 的断网结果（空串=成功）；诊断与预览提示用 */
    public static volatile String lastBlockError = "";

    public static boolean binderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable error) {
            return false;
        }
    }

    public static boolean permissionGranted() {
        try {
            return binderAlive() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable error) {
            return false;
        }
    }

    public static boolean ready() {
        return permissionGranted();
    }

    /**
     * 查询本应用的焦点通知白名单结果（经 SystemUI 转 XMSF 校验，可能较慢）：
     * true=允许 false=被拦 null=查询本身失败。诊断时用，不要放进通知热路径。
     */
    public static Boolean probeCanShowFocus(Context context) {
        try {
            Bundle extras = new Bundle();
            extras.putString("package", context.getPackageName());
            Bundle result = context.getContentResolver().call(
                    Uri.parse("content://miui.statusbar.notification.public"), "canShowFocus", null, extras);
            return result != null && result.getBoolean("canShowFocus", false);
        } catch (Throwable error) {
            return null;
        }
    }

    public static int xmsfUidOf(Context context) {
        return xmsfUid(context);
    }

    /** 诊断/测试直入口：同步设置 XMSF 网络通断，失败抛出真实异常 */
    public static void setXmsfNetworkingForTest(Context context, boolean enabled) throws Exception {
        setXmsfNetworking(context, enabled);
    }

    public static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static int xmsfUid(Context context) {
        try {
            return context.getPackageManager().getPackageUid(XMSF_PACKAGE, 0);
        } catch (Exception error) {
            return -1;
        }
    }

    /**
     * 在断网窗口内发出通知：已处于窗口内时直接复用（避免反复拉闸/恢复抖动），
     * Shizuku 不可用或操作失败时仍以普通方式发送（白名单内设备或未强制校验的 ROM 依然有效）。
     */
    public static void notifyNow(Context context, int id, Notification notification) {
        Context app = context.getApplicationContext();
        // 探测优先：白名单已放行的设备（新版 HyperOS 开放或已装解锁模块）直接发送，
        // 不碰防火墙——应用进程里对隐藏防火墙接口的反射在部分 ROM（如 Android 16）上不可用
        Boolean allowed = probeCanShowFocus(app);
        if (Boolean.TRUE.equals(allowed)) {
            lastBlockError = "";
            NotificationManager manager = app.getSystemService(NotificationManager.class);
            if (manager != null) manager.notify(id, notification);
            return;
        }
        synchronized (LOCK) {
            boolean insideWindow = pendingRestore != null;
            if (insideWindow) {
                RESTORE_HANDLER.removeCallbacks(pendingRestore);
                pendingRestore = null;
            }
            boolean blocked = false;
            String blockError = "";
            if (!insideWindow && ready()) {
                try {
                    setXmsfNetworking(app, false);
                    blocked = true;
                    SystemClock.sleep(BLOCK_SETTLE_MS);
                } catch (Exception error) {
                    blockError = describe(error);
                }
            } else if (!insideWindow) {
                blockError = "Shizuku 未就绪，未尝试断网";
            }
            lastBlockError = blockError;
            try {
                NotificationManager manager = app.getSystemService(NotificationManager.class);
                if (manager != null) manager.notify(id, notification);
            } finally {
                if (blocked) scheduleRestore(app, RESTORE_DELAY_MS);
            }
        }
    }

    /** 立即恢复 XMSF 网络并清掉待执行的恢复任务；服务启动/收尾时调用以自愈卡死的断网状态 */
    public static void ensureRestored(Context context) {
        final Context app = context.getApplicationContext();
        synchronized (LOCK) {
            if (pendingRestore != null) {
                RESTORE_HANDLER.removeCallbacks(pendingRestore);
                pendingRestore = null;
            }
        }
        restoreNow(app);
    }

    private static void scheduleRestore(Context app, long delayMs) {
        Runnable task = () -> {
            synchronized (LOCK) {
                pendingRestore = null;
                restoreNow(app);
            }
        };
        pendingRestore = task;
        RESTORE_HANDLER.postDelayed(task, delayMs);
    }

    private static void restoreNow(Context app) {
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!ready()) return; // Shizuku 不在无从恢复；下次 ready 时 ensureRestored 会兜底
            try {
                setXmsfNetworking(app, true);
                return;
            } catch (Exception error) {
                SystemClock.sleep(400L);
            }
        }
    }

    private static void setXmsfNetworking(Context context, boolean enabled) throws Exception {
        int uid = xmsfUid(context);
        if (uid < 0) throw new IllegalStateException("本机没有小米服务框架（" + XMSF_PACKAGE + "）");
        int rule = enabled ? RULE_ALLOW : RULE_DENY;
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            for (String[] backend : BACKENDS) {
                try {
                    applyUidRule(backend, uid, rule, !enabled);
                    return;
                } catch (Exception error) {
                    last = error;
                    SERVICES.remove(backend[1]);
                }
            }
            if (attempt == 0) SystemClock.sleep(400L);
        }
        throw new IllegalStateException("Shizuku 调整防火墙规则失败", last);
    }

    private static void applyUidRule(String[] backend, int uid, int rule, boolean enableChain) throws Exception {
        Object service = hookedService(backend[0], backend[1]);
        if (enableChain) {
            // 先启用 OEM_DENY 链再对 XMSF 单独设拒绝；个别版本没有链开关，失败也继续设 uid 规则
            try {
                invokeFlexible(service, new String[]{"setFirewallChainEnabled"}, OEM_DENY_CHAIN, true);
            } catch (Exception ignored) {
            }
        }
        try {
            invokeFlexible(service, new String[]{"setUidFirewallRule"}, OEM_DENY_CHAIN, uid, rule);
        } catch (Exception ruleError) {
            try {
                // AOSP 新版 INetworkManagementService 的链式接口签名
                invokeFlexible(service, new String[]{"setFirewallUidRule"}, OEM_DENY_CHAIN, uid, rule);
            } catch (Exception legacyError) {
                invokeFlexible(service, new String[]{"setFirewallUidRules"}, OEM_DENY_CHAIN, new int[]{uid}, new int[]{rule});
            }
        }
    }

    /** 用 ShizukuBinderWrapper 包装系统服务原始 binder，之后的所有 transact 都由 Shizuku 以特权身份转发 */
    private static Object hookedService(String serviceName, String stubClassName) throws Exception {
        Object cached = SERVICES.get(stubClassName);
        if (cached != null) return cached;
        IBinder original = SystemServiceHelper.getSystemService(serviceName);
        if (original == null) throw new IllegalStateException("系统服务 " + serviceName + " 不可用");
        IBinder wrapper = new ShizukuBinderWrapper(original);
        Class<?> stub = Class.forName(stubClassName);
        Object proxy = stub.getMethod("asInterface", IBinder.class).invoke(null, wrapper);
        if (proxy == null) throw new IllegalStateException(stubClassName + ".asInterface 返回空");
        SERVICES.put(stubClassName, proxy);
        return proxy;
    }

    /** 按名字与参数个数匹配方法并做 int/boolean 自适应，绕开不同 ROM 上的签名差异 */
    private static void invokeFlexible(Object target, String[] names, Object... args) throws Exception {
        Method match = null;
        for (Method method : target.getClass().getMethods()) {
            if (method.getParameterCount() != args.length || !matchesName(names, method.getName())) continue;
            Object[] adapted = adaptArgs(method, args);
            if (adapted == null) continue;
            match = method;
            try {
                method.setAccessible(true);
                method.invoke(target, adapted);
                return;
            } catch (InvocationTargetException error) {
                Throwable cause = error.getCause() != null ? error.getCause() : error;
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new Exception(cause);
            }
        }
        throw new NoSuchMethodException("未找到 " + names[0] + "（" + args.length + " 参数）于 " + target.getClass().getName()
                + (match == null ? "" : "，参数类型不匹配"));
    }

    private static boolean matchesName(String[] names, String actual) {
        for (String name : names) {
            if (name.equals(actual)) return true;
        }
        return false;
    }

    private static Object[] adaptArgs(Method method, Object[] args) {
        Class<?>[] types = method.getParameterTypes();
        Object[] adapted = new Object[args.length];
        for (int index = 0; index < args.length; index++) {
            Object arg = args[index];
            Class<?> expected = types[index];
            if (expected == int.class) {
                if (arg instanceof Integer) adapted[index] = arg;
                else if (arg instanceof Boolean) adapted[index] = (Boolean) arg ? 1 : 0;
                else return null;
            } else if (expected == boolean.class) {
                if (arg instanceof Boolean) adapted[index] = arg;
                else if (arg instanceof Integer) adapted[index] = (Integer) arg != 0;
                else return null;
            } else if (expected == int[].class) {
                if (arg instanceof int[]) adapted[index] = arg;
                else return null;
            } else if (expected.isInstance(arg)) {
                adapted[index] = arg;
            } else {
                return null;
            }
        }
        return adapted;
    }
}
