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
import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

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
    // 삼성 폰은 숨김 API(isWifiApEnabled)가 핫스팟이 켜져 있어도 false를 주는 경우가 있어서,
    // 여러 방법으로 확인하고 하나라도 켜짐이면 켜진 것으로 본다.
    private static final String ACTION_AP_STATE = "android.net.wifi.WIFI_AP_STATE_CHANGED";
    private static final String EXTRA_AP_STATE = "wifi_state";
    private static final String ACTION_TETHER_STATE = "android.net.conn.TETHER_STATE_CHANGED";
    private static final String EXTRA_ACTIVE_TETHER = "tetherArray";
    private static final int AP_ENABLING = 12, AP_ENABLED = 13;

    /** 핫스팟 판단에 쓴 값들. #진단 문자와 설정 화면에 그대로 보여준다. */
    static final class HotspotProbe {
        Boolean apiEnabled;      // WifiManager.isWifiApEnabled() (숨김 API)
        int apiState = -1;       // WifiManager.getWifiApState() (숨김 API)
        int broadcastState = -1; // 마지막 핫스팟 상태 방송
        List<String> tethered;   // 테더링 중인 인터페이스 (null = 못 읽음)
        List<String> apIfaces;   // 켜져 있는 핫스팟 인터페이스 (null = 못 읽음)

        boolean wifiOn() {
            return Boolean.TRUE.equals(apiEnabled)
                    || apiState == AP_ENABLING || apiState == AP_ENABLED
                    || broadcastState == AP_ENABLING || broadcastState == AP_ENABLED
                    || containsWifi(tethered)
                    || (apIfaces != null && !apIfaces.isEmpty());
        }

        boolean anyKnown() {
            return apiEnabled != null || apiState >= 0 || broadcastState >= 0
                    || tethered != null || apIfaces != null;
        }

        /** 예: "API:F/11 방송:13 테더:swlan0 IF:swlan0" */
        String summary() {
            return "API:" + (apiEnabled == null ? "?" : (apiEnabled ? "T" : "F"))
                    + "/" + (apiState < 0 ? "?" : String.valueOf(apiState))
                    + " 방송:" + (broadcastState < 0 ? "?" : String.valueOf(broadcastState))
                    + " 테더:" + listText(tethered)
                    + " IF:" + listText(apIfaces);
        }

        private static String listText(List<String> l) {
            if (l == null) return "?";
            if (l.isEmpty()) return "-";
            return TextUtils.join(",", l);
        }
    }

    static HotspotProbe probeHotspot(Context c) {
        HotspotProbe p = new HotspotProbe();
        WifiManager wm = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
        if (wm != null) {
            try {
                Method m = wm.getClass().getMethod("isWifiApEnabled");
                p.apiEnabled = (Boolean) m.invoke(wm);
            } catch (Throwable ignored) {}
            try {
                Method m = wm.getClass().getMethod("getWifiApState");
                p.apiState = (Integer) m.invoke(wm);
            } catch (Throwable ignored) {}
        }
        try {
            Intent i = c.registerReceiver(null, new IntentFilter(ACTION_AP_STATE));
            if (i != null) p.broadcastState = i.getIntExtra(EXTRA_AP_STATE, -1);
        } catch (Throwable ignored) {}
        try {
            Intent i = c.registerReceiver(null, new IntentFilter(ACTION_TETHER_STATE));
            if (i != null) {
                ArrayList<String> active = i.getStringArrayListExtra(EXTRA_ACTIVE_TETHER);
                p.tethered = active != null ? active : new ArrayList<String>();
            }
        } catch (Throwable ignored) {}
        try {
            List<String> up = new ArrayList<>();
            Enumeration<NetworkInterface> all = NetworkInterface.getNetworkInterfaces();
            if (all != null) {
                for (NetworkInterface ni : Collections.list(all)) {
                    if (isApIfaceName(ni.getName()) && ni.isUp() && hasIpv4(ni)) up.add(ni.getName());
                }
            }
            p.apIfaces = up;
        } catch (Throwable ignored) {}
        return p;
    }

    private static void readHotspot(Context c, Status s) {
        HotspotProbe p = probeHotspot(c);
        if (p.wifiOn()) s.hotspotOn = true;
        else if (p.anyKnown()) s.hotspotOn = false;
        if (Boolean.TRUE.equals(s.hotspotOn)) s.clients = countClients(apIfaceNames(p));
    }

    /** 핫스팟 전용 인터페이스 이름 (wlan0은 일반 와이파이 연결이라 빼야 한다). */
    private static boolean isApIfaceName(String name) {
        return name != null && (name.startsWith("swlan") || name.startsWith("ap") || name.startsWith("softap"));
    }

    /** 테더링 목록 안의 이름은 wlan으로 시작해도 핫스팟이다. */
    private static boolean isWifiTether(String name) {
        return name != null && (isApIfaceName(name) || name.startsWith("wlan"));
    }

    private static boolean containsWifi(List<String> names) {
        if (names == null) return false;
        for (String n : names) if (isWifiTether(n)) return true;
        return false;
    }

    private static boolean hasIpv4(NetworkInterface ni) {
        for (InetAddress a : Collections.list(ni.getInetAddresses())) {
            if (a instanceof Inet4Address) return true;
        }
        return false;
    }

    private static List<String> apIfaceNames(HotspotProbe p) {
        List<String> out = new ArrayList<>();
        if (p.tethered != null) for (String n : p.tethered) if (isWifiTether(n) && !out.contains(n)) out.add(n);
        if (p.apIfaces != null) for (String n : p.apIfaces) if (!out.contains(n)) out.add(n);
        return out;
    }

    /** 연결 기기 수. 안드로이드가 막아두면 null. */
    private static Integer countClients(List<String> apIfaces) {
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
                boolean apIface = apIfaces.isEmpty() ? isApIfaceName(dev) : apIfaces.contains(dev);
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
