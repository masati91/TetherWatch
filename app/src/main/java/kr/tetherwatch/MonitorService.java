package kr.tetherwatch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 1분마다 상태를 확인하고, 문제가 생기거나 풀리면 첫 번째 등록 번호로 문자를 보낸다.
 * 알림창에 항상 떠 있어서 안드로이드가 앱을 끄지 않는다.
 */
public class MonitorService extends Service {
    private static final String CHANNEL = "monitor";
    private static final int NOTIF_ID = 1;
    private static final long INTERVAL_MS = 60_000L;

    private static final int HOTSPOT_OFF_TICKS = 2;   // 2분 연속 꺼져 있으면 알림
    private static final int INTERNET_BAD_TICKS = 3;  // 3분 연속 끊기면 알림
    private static final float HOT_C = 45f;
    private static final float COOL_C = 42f;
    private static final int LOW_BATTERY = 20;
    private static final long BOOT_ALERT_AFTER_MS = 120_000L;

    private HandlerThread thread;
    private Handler handler;
    private PowerManager.WakeLock wakeLock;

    private boolean initialized;
    private boolean hotspotSeenOn, hotspotAlerted;
    private int hotspotOffTicks;
    private boolean internetAlerted;
    private int internetBadTicks;
    private boolean lastCharging, chargeAlerted;
    private boolean tempAlerted, lowAlerted;

    static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, MonitorService.class));
        } catch (Throwable t) {
            Prefs.log(c, "감시 시작 실패: " + t.getMessage());
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            try {
                check();
            } catch (Throwable t) {
                Prefs.log(MonitorService.this, "감시 오류: " + t);
            }
            handler.postDelayed(this, INTERVAL_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "상태 감시", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        startForeground(NOTIF_ID, buildNotification("감시 시작"));

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tetherwatch:monitor");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }

        thread = new HandlerThread("monitor");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(tick);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (handler != null) handler.removeCallbacksAndMessages(null);
        if (thread != null) thread.quitSafely();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification(String text) {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("테더링 지킴이 작동 중")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build();
    }

    private void check() {
        Status s = StatusCollector.collect(this);

        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(NOTIF_ID, buildNotification(s.toSms()));

        List<String> events = new ArrayList<>();

        if (!initialized) {
            initialized = true;
            hotspotSeenOn = Boolean.TRUE.equals(s.hotspotOn);
            lastCharging = s.charging;
        } else {
            // 핫스팟: 한 번이라도 켜진 걸 본 뒤에 꺼지면 알림
            if (Boolean.TRUE.equals(s.hotspotOn)) {
                if (hotspotAlerted) events.add("핫스팟켜짐");
                hotspotAlerted = false;
                hotspotOffTicks = 0;
                hotspotSeenOn = true;
            } else if (Boolean.FALSE.equals(s.hotspotOn) && hotspotSeenOn) {
                hotspotOffTicks++;
                if (hotspotOffTicks == HOTSPOT_OFF_TICKS) {
                    events.add("핫스팟꺼짐");
                    hotspotAlerted = true;
                }
            }

            // 충전기
            if (lastCharging && !s.charging) {
                events.add("충전끊김");
                chargeAlerted = true;
            } else if (!lastCharging && s.charging && chargeAlerted) {
                events.add("충전재개");
                chargeAlerted = false;
            }
            lastCharging = s.charging;
        }

        // 인터넷
        if (s.internetOk) {
            if (internetAlerted) events.add("인터넷복구" + internetBadTicks + "분");
            internetAlerted = false;
            internetBadTicks = 0;
        } else {
            internetBadTicks++;
            if (internetBadTicks == INTERNET_BAD_TICKS) {
                events.add("인터넷끊김");
                internetAlerted = true;
            }
        }

        // 온도
        if (!Float.isNaN(s.tempC)) {
            if (s.tempC >= HOT_C && !tempAlerted) {
                events.add("과열");
                tempAlerted = true;
            } else if (tempAlerted && s.tempC < COOL_C) {
                events.add("온도정상");
                tempAlerted = false;
            }
        }

        // 배터리 부족 (충전 안 될 때만)
        if (s.batteryPct >= 0) {
            if (!s.charging && s.batteryPct <= LOW_BATTERY && !lowAlerted) {
                events.add("배터리부족");
                lowAlerted = true;
            } else if (lowAlerted && (s.charging || s.batteryPct > LOW_BATTERY + 10)) {
                lowAlerted = false;
            }
        }

        // 데이터 제공량 90% (한 달에 한 번)
        if (s.capGb > 0 && s.usedBytes >= 0) {
            long cycle = StatusCollector.cycleStart(Prefs.cycleDay(this));
            boolean over = s.usedBytes >= (long) (s.capGb * 0.9 * Status.GB);
            if (over && Prefs.sp(this).getLong("capAlertCycle", 0L) != cycle) {
                events.add("데이터90%");
                Prefs.sp(this).edit().putLong("capAlertCycle", cycle).apply();
            }
        }

        // 재부팅 (켜지고 2분 뒤, 핫스팟이 다시 켜졌는지 함께 알려줌)
        if (Prefs.sp(this).getBoolean("pendingBoot", false) && s.uptimeMs > BOOT_ALERT_AFTER_MS) {
            events.add("재부팅됨");
            Prefs.sp(this).edit().putBoolean("pendingBoot", false).apply();
        }

        if (!events.isEmpty()) sendAlert(events, s);
    }

    private void sendAlert(List<String> events, Status s) {
        String joined = TextUtils.join(",", events);
        Prefs.log(this, "감지: " + joined);
        if (!Prefs.alertsOn(this)) return;
        String to = Prefs.alertNumber(this);
        if (to == null) return;
        if (!Prefs.takeAlertQuota(this)) {
            Prefs.log(this, "오늘 알림 한도(" + Prefs.DAILY_ALERT_LIMIT + "건)를 넘어 문자 생략");
            return;
        }
        SmsSender.send(this, to, s.toSms("[" + joined + "]"));
    }
}
