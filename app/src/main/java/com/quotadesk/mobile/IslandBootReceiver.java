package com.quotadesk.mobile;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机（或应用自更新）后恢复已开启的超级岛监控 */
public final class IslandBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action) && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) return;
        IslandConfig config = IslandConfig.load(context);
        if (config.enabled && !config.token.isEmpty()) QuotaIslandService.start(context);
    }
}
