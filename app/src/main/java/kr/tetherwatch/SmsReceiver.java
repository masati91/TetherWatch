package kr.tetherwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;

/** 등록된 번호에서 명령 문자가 오면 답장한다. */
public class SmsReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) return;
        SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (msgs == null || msgs.length == 0 || msgs[0] == null) return;

        StringBuilder body = new StringBuilder();
        for (SmsMessage m : msgs) {
            if (m != null && m.getMessageBody() != null) body.append(m.getMessageBody());
        }
        final String text = body.toString().trim();
        if (text.isEmpty() || text.length() > 30) return; // 일반 문자는 바로 무시

        final Context app = context.getApplicationContext();
        final String from = msgs[0].getOriginatingAddress();
        final String command = command(app, text);
        if (command == null) return;

        if (!Prefs.isAllowed(app, from)) {
            Prefs.log(app, "무시 (등록 안 된 번호 " + Prefs.last4(from) + "): " + text);
            return;
        }

        final PendingResult pending = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    handle(app, Prefs.normalize(from), text, command);
                } catch (Throwable t) {
                    Prefs.log(app, "처리 오류: " + t);
                } finally {
                    pending.finish();
                }
            }
        }).start();
    }

    /** 문자 내용이 명령이면 명령 이름, 아니면 null. */
    private static String command(Context c, String text) {
        String t = text.replace(" ", "");
        if (t.equalsIgnoreCase(Prefs.keyword(c).replace(" ", ""))) return "status";
        if (t.equals("#알림끔")) return "off";
        if (t.equals("#알림켬")) return "on";
        if (t.equals("#도움")) return "help";
        if (t.equals("#진단")) return "diag";
        return null;
    }

    private static void handle(Context c, String sender, String text, String command) {
        Prefs.log(c, "받음 " + Prefs.last4(sender) + ": " + text);
        String reply;
        switch (command) {
            case "status":
                reply = StatusCollector.collect(c).toSms();
                break;
            case "off":
                Prefs.setAlertsOn(c, false);
                reply = "자동알림 꺼짐";
                break;
            case "on":
                Prefs.setAlertsOn(c, true);
                reply = "자동알림 켜짐";
                break;
            case "diag":
                reply = "핫스팟진단 " + StatusCollector.probeHotspot(c).summary();
                break;
            default:
                reply = "명령: " + Prefs.keyword(c) + " #알림켬 #알림끔 #진단";
                break;
        }
        SmsSender.send(c, sender, reply);
    }
}
