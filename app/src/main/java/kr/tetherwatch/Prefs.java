package kr.tetherwatch;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 설정값과 기록(로그)을 저장한다. */
final class Prefs {
    private static final String NAME = "tetherwatch";
    static final String DEFAULT_KEYWORD = "#상태";
    static final int DAILY_ALERT_LIMIT = 20;
    private static final int LOG_LINES = 40;

    private Prefs() {}

    static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** "+82 10-1234-5678" → "01012345678" */
    static String normalize(String number) {
        if (number == null) return "";
        String d = number.replaceAll("[^0-9]", "");
        if (d.startsWith("82") && d.length() >= 11) d = "0" + d.substring(2);
        return d;
    }

    static List<String> allowedNumbers(Context c) {
        List<String> out = new ArrayList<>();
        String raw = sp(c).getString("allowed", "");
        for (String part : raw.split("[,;\\s]+")) {
            String n = normalize(part);
            if (n.length() >= 9 && !out.contains(n)) out.add(n);
        }
        return out;
    }

    static boolean isAllowed(Context c, String sender) {
        String s = normalize(sender);
        if (s.isEmpty()) return false;
        return allowedNumbers(c).contains(s);
    }

    /** 자동 알림은 첫 번째 등록 번호로 보낸다. */
    static String alertNumber(Context c) {
        List<String> list = allowedNumbers(c);
        return list.isEmpty() ? null : list.get(0);
    }

    static String keyword(Context c) {
        String k = sp(c).getString("keyword", DEFAULT_KEYWORD);
        k = k == null ? "" : k.trim();
        return k.isEmpty() ? DEFAULT_KEYWORD : k;
    }

    static boolean alertsOn(Context c) {
        return sp(c).getBoolean("alertsOn", true);
    }

    static void setAlertsOn(Context c, boolean on) {
        sp(c).edit().putBoolean("alertsOn", on).apply();
    }

    static int cycleDay(Context c) {
        int d = sp(c).getInt("cycleDay", 1);
        return Math.max(1, Math.min(28, d));
    }

    static float capGb(Context c) {
        return sp(c).getFloat("capGb", 0f);
    }

    /** 하루 알림 한도 안이면 1건을 쓰고 true. */
    static synchronized boolean takeAlertQuota(Context c) {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        SharedPreferences p = sp(c);
        int count = today.equals(p.getString("alertDay", "")) ? p.getInt("alertCount", 0) : 0;
        if (count >= DAILY_ALERT_LIMIT) return false;
        p.edit().putString("alertDay", today).putInt("alertCount", count + 1).apply();
        return true;
    }

    static synchronized void log(Context c, String msg) {
        String line = new SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(new Date()) + "  " + msg;
        String old = sp(c).getString("log", "");
        StringBuilder sb = new StringBuilder(line);
        if (old != null && !old.isEmpty()) {
            String[] lines = old.split("\n");
            for (int i = 0; i < lines.length && i < LOG_LINES - 1; i++) sb.append('\n').append(lines[i]);
        }
        sp(c).edit().putString("log", sb.toString()).apply();
    }

    static String logText(Context c) {
        String s = sp(c).getString("log", "");
        return s == null ? "" : s;
    }

    static String last4(String number) {
        String n = normalize(number);
        return n.length() <= 4 ? n : "…" + n.substring(n.length() - 4);
    }
}
