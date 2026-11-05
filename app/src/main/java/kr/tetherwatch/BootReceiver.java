package kr.tetherwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 폰이 켜지거나 앱이 업데이트되면 감시를 다시 시작한다. */
public class BootReceiver extends BroadcastReceiver {
    private static final String QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || QUICKBOOT.equals(action)) {
            Prefs.sp(context).edit().putBoolean("pendingBoot", true).apply();
            Prefs.log(context, "폰 재부팅 감지");
            MonitorService.start(context);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            MonitorService.start(context);
        }
    }
}
