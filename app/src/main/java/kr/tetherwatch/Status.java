package kr.tetherwatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 한 시점의 폰 상태. toSms()로 단문 한도(70자, 90바이트) 안의 문자로 만든다. */
final class Status {
    static final long GB = 1024L * 1024L * 1024L;
    static final int MAX_CHARS = 70;
    static final int MAX_BYTES = 90;

    Boolean hotspotOn;          // null = 알 수 없음
    Integer clients;            // null = 알 수 없음
    boolean airplane;
    boolean simReady = true;
    String netType = "?";
    int signalLevel = -1;       // 0~4
    Boolean dataEnabled;
    boolean internetOk;
    int batteryPct = -1;
    boolean charging;           // 충전기 연결 여부
    float tempC = Float.NaN;
    long usedBytes = -1;        // 이번 달 모바일 데이터
    float capGb;
    long uptimeMs;

    String toSms() {
        return toSms(null);
    }

    /** prefix 예: "[핫스팟꺼짐]". 길면 덜 중요한 항목부터 뺀다. */
    String toSms(String prefix) {
        List<String> texts = new ArrayList<>();
        List<Integer> drops = new ArrayList<>(); // 0 = 안 뺌, 숫자가 클수록 먼저 뺌

        if (prefix != null && !prefix.isEmpty()) add(texts, drops, prefix, 0);

        add(texts, drops, hotspotOn == null ? "핫스팟?" : (hotspotOn ? "핫스팟ON" : "핫스팟OFF"), 0);
        if (Boolean.TRUE.equals(hotspotOn) && clients != null) add(texts, drops, "연결" + clients, 2);

        if (airplane) add(texts, drops, "비행기모드", 0);
        else if (!simReady) add(texts, drops, "유심오류", 0);
        else add(texts, drops, netType + (signalLevel >= 0 ? signalLevel + "/4" : ""), 0);
        if (dataEnabled != null && !dataEnabled) add(texts, drops, "데이터OFF", 0);

        add(texts, drops, internetOk ? "인터넷OK" : "인터넷X", 0);

        if (batteryPct >= 0) add(texts, drops, batteryPct + "%" + (charging ? "충전중" : "충전X"), 0);
        if (!Float.isNaN(tempC)) add(texts, drops, Math.round(tempC) + "C" + (tempC >= 45f ? "과열" : ""), 0);

        if (usedBytes >= 0) {
            String used = String.format(Locale.US, "%.1f", usedBytes / (double) GB);
            add(texts, drops, used + (capGb > 0 ? "/" + fmtGb(capGb) : "") + "GB", 1);
        }
        add(texts, drops, "가동" + uptimeText(), 3);

        String out = join(texts, drops, 99);
        for (int level = 3; level >= 1 && !fits(out); level--) out = join(texts, drops, level);
        return out;
    }

    private static void add(List<String> t, List<Integer> d, String text, int drop) {
        t.add(text);
        d.add(drop);
    }

    /** drop 값이 minDrop 이상인 항목은 뺀다. */
    private static String join(List<String> t, List<Integer> d, int minDrop) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.size(); i++) {
            int drop = d.get(i);
            if (drop != 0 && drop >= minDrop) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t.get(i));
        }
        return sb.toString();
    }

    private String uptimeText() {
        long hours = uptimeMs / 3_600_000L;
        if (hours >= 24) return (hours / 24) + "일";
        if (hours >= 1) return hours + "시간";
        return (uptimeMs / 60_000L) + "분";
    }

    static String fmtGb(float gb) {
        if (gb == Math.round(gb)) return String.valueOf(Math.round(gb));
        return String.format(Locale.US, "%.1f", gb);
    }

    static boolean fits(String s) {
        return s.length() <= MAX_CHARS && byteLen(s) <= MAX_BYTES;
    }

    /** 한국 단문 기준 바이트 수 (한글 2, 영문·숫자 1). */
    static int byteLen(String s) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) n += s.charAt(i) < 0x80 ? 1 : 2;
        return n;
    }
}
