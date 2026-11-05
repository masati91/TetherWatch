package kr.tetherwatch;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** 설정 화면. 화면 하나에 모든 설정을 둔다. */
public class MainActivity extends Activity {
    private static final int REQ_PERMS = 1;
    private static final String[] PERMS = {
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_PHONE_STATE,
    };

    private EditText numbers, keyword, cycleDay, capGb;
    private CheckBox alerts;
    private TextView preview, permsInfo, logView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("테더링 지킴이");

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);
        setContentView(scroll);

        root.addView(header("지금 상태 (문자로 가는 내용)"));
        preview = box();
        root.addView(preview);
        root.addView(button("새로고침", new View.OnClickListener() {
            @Override public void onClick(View v) { refreshPreview(); }
        }));

        root.addView(header("1. 권한"));
        permsInfo = label("");
        root.addView(permsInfo);
        root.addView(button("문자·전화 상태 권한 허용", new View.OnClickListener() {
            @Override public void onClick(View v) { requestPermissions(PERMS, REQ_PERMS); }
        }));
        root.addView(button("데이터 사용량 접근 허용 (목록에서 이 앱 켜기)", new View.OnClickListener() {
            @Override public void onClick(View v) { open(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)); }
        }));
        root.addView(button("배터리 최적화에서 제외", new View.OnClickListener() {
            @Override public void onClick(View v) { requestIgnoreBattery(); }
        }));

        root.addView(header("2. 설정"));
        root.addView(label("조회할 수 있는 번호 (쉼표로 구분). 자동 알림은 첫 번째 번호로 갑니다."));
        numbers = edit(InputType.TYPE_CLASS_PHONE, "01012345678, 01098765432");
        root.addView(numbers);
        root.addView(label("조회 키워드. 숫자를 붙이면 더 안전합니다 (예: #상태1234)"));
        keyword = edit(InputType.TYPE_CLASS_TEXT, Prefs.DEFAULT_KEYWORD);
        root.addView(keyword);
        root.addView(label("데이터 집계 시작일 (요금제 기준일, 1~28)"));
        cycleDay = edit(InputType.TYPE_CLASS_NUMBER, "1");
        root.addView(cycleDay);
        root.addView(label("월 데이터 제공량 GB. 모르면 비워두세요 (90% 넘으면 알림)"));
        capGb = edit(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL, "예: 50");
        root.addView(capGb);
        alerts = new CheckBox(this);
        alerts.setText("문제가 생기면 자동으로 문자 보내기 (하루 최대 " + Prefs.DAILY_ALERT_LIMIT + "건)");
        root.addView(alerts);
        root.addView(button("저장", new View.OnClickListener() {
            @Override public void onClick(View v) { save(); }
        }));
        root.addView(button("첫 번째 번호로 테스트 문자 보내기", new View.OnClickListener() {
            @Override public void onClick(View v) { sendTest(); }
        }));

        root.addView(header("기록 (최근 순)"));
        logView = box();
        root.addView(logView);

        load();
        MonitorService.start(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshPerms();
        refreshPreview();
        refreshLog();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshPerms();
        refreshPreview();
    }

    // ---- 동작 ----

    private void load() {
        numbers.setText(Prefs.sp(this).getString("allowed", ""));
        keyword.setText(Prefs.keyword(this));
        cycleDay.setText(String.valueOf(Prefs.cycleDay(this)));
        float cap = Prefs.capGb(this);
        capGb.setText(cap > 0 ? Status.fmtGb(cap) : "");
        alerts.setChecked(Prefs.alertsOn(this));
    }

    private void save() {
        int day = parseInt(cycleDay.getText().toString(), 1);
        float cap = parseFloat(capGb.getText().toString(), 0f);
        Prefs.sp(this).edit()
                .putString("allowed", numbers.getText().toString().trim())
                .putString("keyword", keyword.getText().toString().trim())
                .putInt("cycleDay", Math.max(1, Math.min(28, day)))
                .putFloat("capGb", Math.max(0f, cap))
                .putBoolean("alertsOn", alerts.isChecked())
                .apply();
        List<String> list = Prefs.allowedNumbers(this);
        toast(list.isEmpty()
                ? "저장했습니다. 번호가 없어서 어떤 문자에도 답하지 않습니다."
                : "저장했습니다. 등록 번호 " + list.size() + "개");
        Prefs.log(this, "설정 저장 (번호 " + list.size() + "개)");
        load();
        refreshPreview();
        refreshLog();
    }

    private void sendTest() {
        final String to = Prefs.alertNumber(this);
        if (to == null) { toast("먼저 번호를 입력하고 저장하세요."); return; }
        if (!granted(Manifest.permission.SEND_SMS)) { toast("문자 권한을 먼저 허용하세요."); return; }
        new Thread(new Runnable() {
            @Override public void run() {
                SmsSender.send(MainActivity.this, to, StatusCollector.collect(MainActivity.this).toSms("[테스트]"));
                runOnUiThread(new Runnable() { @Override public void run() { refreshLog(); } });
            }
        }).start();
        toast(Prefs.last4(to) + " 번호로 보냈습니다.");
    }

    private void requestIgnoreBattery() {
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void open(Intent i) {
        try {
            startActivity(i);
        } catch (Exception e) {
            toast("설정 화면을 열 수 없습니다.");
        }
    }

    private void refreshPreview() {
        new Thread(new Runnable() {
            @Override public void run() {
                final String sms = StatusCollector.collect(MainActivity.this).toSms();
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        preview.setText(sms + "\n\n(" + sms.length() + "자 · " + Status.byteLen(sms)
                                + "바이트 / 단문 한도 " + Status.MAX_CHARS + "자 · " + Status.MAX_BYTES + "바이트)");
                    }
                });
            }
        }).start();
    }

    private void refreshPerms() {
        boolean sms = granted(Manifest.permission.RECEIVE_SMS) && granted(Manifest.permission.SEND_SMS);
        boolean phone = granted(Manifest.permission.READ_PHONE_STATE);
        boolean usage = StatusCollector.hasUsageAccess(this);
        PowerManager pm = getSystemService(PowerManager.class);
        boolean battery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        permsInfo.setText(mark(sms) + " 문자 받기·보내기\n"
                + mark(phone) + " 통신 상태 읽기 (LTE, 데이터 켜짐)\n"
                + mark(usage) + " 데이터 사용량 읽기\n"
                + mark(battery) + " 배터리 최적화 제외");
    }

    private void refreshLog() {
        String log = Prefs.logText(this);
        logView.setText(log.isEmpty() ? "아직 기록이 없습니다." : log);
    }

    // ---- 도우미 ----

    private boolean granted(String perm) {
        return checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED;
    }

    private static String mark(boolean ok) {
        return ok ? "[O]" : "[X]";
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static float parseFloat(String s, float def) {
        try { return Float.parseFloat(s.trim()); } catch (Exception e) { return def; }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView header(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(18);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        lp.bottomMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setPadding(0, dp(8), 0, dp(2));
        return tv;
    }

    private TextView box() {
        TextView tv = new TextView(this);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextSize(14);
        tv.setTextIsSelectable(true);
        tv.setBackgroundColor(0xFFEFEFEF);
        tv.setTextColor(0xFF222222);
        int p = dp(12);
        tv.setPadding(p, p, p, p);
        return tv;
    }

    private EditText edit(int inputType, String hint) {
        EditText e = new EditText(this);
        e.setInputType(inputType);
        e.setHint(hint);
        e.setSingleLine(true);
        return e;
    }

    private Button button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }
}
