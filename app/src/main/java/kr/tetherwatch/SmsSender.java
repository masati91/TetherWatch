package kr.tetherwatch;

import android.content.Context;
import android.telephony.SmsManager;

import java.util.ArrayList;

final class SmsSender {
    private SmsSender() {}

    static void send(Context c, String to, String text) {
        try {
            SmsManager sm = SmsManager.getDefault();
            ArrayList<String> parts = sm.divideMessage(text);
            if (parts.size() <= 1) {
                sm.sendTextMessage(to, null, text, null, null);
            } else {
                sm.sendMultipartTextMessage(to, null, parts, null, null);
            }
            Prefs.log(c, "보냄 " + Prefs.last4(to) + ": " + text
                    + (parts.size() > 1 ? " (" + parts.size() + "건으로 나뉨)" : ""));
        } catch (Throwable t) {
            Prefs.log(c, "보내기 실패: " + t.getMessage());
        }
    }
}
