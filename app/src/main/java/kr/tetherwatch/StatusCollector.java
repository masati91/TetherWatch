package kr.tetherwatch;

import android.app.AppOpsManager;
import android.app.usage.NetworkStats;
import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.telephony.SignalStrength;
import android.telephony.TelephonyManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.util.Calendar;

/** 폰 상태를 읽는다. 어느 항목이 실패해도 나머지는 계속 읽는다. */
final class StatusCollector {
    private StatusCollector() {}

    static Status collect(Context ctx) {
        Context c = ctx.getApplicationContext();
        Status s = new Status();
        try { readHotspot(c, s); } catch (Throwable ignored) {}
        try { readTelephony(c, s); } catch (Throwable ignored) {}
        try { s.internetOk = cellularValidated(c); } catch (Throwable ignored) {}
        try { readBattery(c, s); } catch (Throwable ignored) {}
        try { readDataUsage(c, s); } catch (Throwable ignored) {}
        s.uptimeMs = SystemClock.elapsedRealtime();
        return s;
    }

    // ---- 핫스팟 ----
    private static void readHotspot(Context c, Status s) {
        WifiManager wm = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return;
        try {
            Method m = wm.getClass().getMethod("isWifiApEnabled");
            s.hotspotOn = (Boolean) m.invoke(wm);
        } catch (Throwable t) {
            try {
                Method m = wm.getClass().getMethod("getWifiApState");
                int state = (Integer) m.invoke(wm);
                s.hotspotOn = state == 12 || state == 13; // ENABLING, ENABLED
            } catch (Throwable ignored) {
                // 알 수 없음으로 둔다
            }
        }
        if (Boolean.TRUE.equals(s.hotspotOn)) s.clients = countClients();
    }

    /** 연결 기기 수. 안드로이드가 막아두면 null. */
    private static Integer countClients() {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new FileReader("/proc/net/arp"));
            String line = r.readLine(); // 머리줄
            if (line == null) return null;
            int n = 0;
            while ((line = r.readLine()) != null) {
                String[] f = line.trim().split("\\s+");
                if (f.length < 6) continue;
                boolean complete = !"0x0".equals(f[2]) && !"00:00:00:00:00:00".equals(f[3]);
                String dev = f[5];
                boolean apIface = dev.startsWith("swlan") || dev.startsWith("wlan")
                        || dev.startsWith("ap") || dev.startsWith("softap");
                if (complete && apIface) n++;
            }
            return n;
        } catch (Throwable t) {
            return null;
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) {}
        }
    }

    // ---- 통신 ----
    private static void readTelephony(Context c, Status s) {
        s.airplane = Settings.Global.getInt(c.getContentResolver(), Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
        TelephonyManager tm = (TelephonyManager) c.getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) return;

        s.simReady = tm.getSimState() == TelephonyManager.SIM_STATE_READY;

        int type = TelephonyManager.NETWORK_TYPE_UNKNOWN;
        try {
            type = tm.getDataNetworkType();
        } catch (SecurityException e) {
            try { type = tm.getNetworkType(); } catch (Throwable ignored) {}
        }
        s.netType = typeName(type);

        if (Build.VERSION.SDK_INT >= 28) {
            SignalStrength ss = tm.getSignalStrength();
            if (ss != null) s.signalLevel = ss.getLevel();
        }
        try { s.dataEnabled = tm.isDataEnabled(); } catch (Throwable ignored) {}
    }

    private static String typeName(int type) {
        switch (type) {
            case TelephonyManager.NETWORK_TYPE_NR:
                return "5G";
            case TelephonyManager.NETWORK_TYPE_LTE:
            case 19: // LTE_CA (숨김 상수)
            case TelephonyManager.NETWORK_TYPE_IWLAN:
                return "LTE";
            case TelephonyManager.NETWORK_TYPE_UMTS:
            case TelephonyManager.NETWORK_TYPE_HSDPA:
            case TelephonyManager.NETWORK_TYPE_HSUPA:
            case TelephonyManager.NETWORK_TYPE_HSPA:
            case TelephonyManager.NETWORK_TYPE_HSPAP:
            case TelephonyManager.NETWORK_TYPE_EVDO_0:
            case TelephonyManager.NETWORK_TYPE_EVDO_A:
            case TelephonyManager.NETWORK_TYPE_EVDO_B:
            case TelephonyManager.NETWORK_TYPE_EHRPD:
            case TelephonyManager.NETWORK_TYPE_TD_SCDMA:
                return "3G";
            case TelephonyManager.NETWORK_TYPE_GPRS:
            case TelephonyManager.NETWORK_TYPE_EDGE:
            case TelephonyManager.NETWORK_TYPE_CDMA:
            case TelephonyManager.NETWORK_TYPE_1xRTT:
            case TelephonyManager.NETWORK_TYPE_IDEN:
            case TelephonyManager.NETWORK_TYPE_GSM:
                return "2G";
            default:
                return "망없음";
        }
    }

    // ---- 인터넷 ----
    /** 폰의 모바일망이 실제로 인터넷이 되는지 (안드로이드가 확인한 값). */
    private static boolean cellularValidated(Context c) {
        ConnectivityManager cm = (ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities nc = cm.getNetworkCapabilities(n);
            if (nc != null
                    && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                return true;
            }
        }
        return false;
    }

    // ---- 배터리 ----
    private static void readBattery(Context c, Status s) {
        Intent b = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        if (level >= 0 && scale > 0) s.batteryPct = Math.round(level * 100f / scale);
        s.charging = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        int t = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        if (t != Integer.MIN_VALUE) s.tempC = t / 10f;
    }

    // ---- 데이터 사용량 ----
    private static void readDataUsage(Context c, Status s) throws Exception {
        s.capGb = Prefs.capGb(c);
        if (!hasUsageAccess(c)) return;
        NetworkStatsManager nsm = (NetworkStatsManager) c.getSystemService(Context.NETWORK_STATS_SERVICE);
        if (nsm == null) return;
        long start = cycleStart(Prefs.cycleDay(c));
        NetworkStats.Bucket b = nsm.querySummaryForDevice(
                ConnectivityManager.TYPE_MOBILE, null, start, System.currentTimeMillis());
        if (b != null) s.usedBytes = b.getRxBytes() + b.getTxBytes();
    }

    static boolean hasUsageAccess(Context c) {
        AppOpsManager ops = (AppOpsManager) c.getSystemService(Context.APP_OPS_SERVICE);
        if (ops == null) return false;
        int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    /** 요금 집계 시작 시각 (매달 day일 0시). */
    static long cycleStart(int day) {
        Calendar cal = Calendar.getInstance();
        int today = cal.get(Calendar.DAY_OF_MONTH);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (today < day) cal.add(Calendar.MONTH, -1);
        cal.set(Calendar.DAY_OF_MONTH, day);
        return cal.getTimeInMillis();
    }
}
