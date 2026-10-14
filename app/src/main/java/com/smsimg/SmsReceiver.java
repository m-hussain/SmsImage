package com.smsimg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import java.util.LinkedHashMap;
import java.util.Map;

public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        SmsMessage[] ms = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (ms == null) return;
        final Map<String, StringBuilder> by = new LinkedHashMap<>();
        for (SmsMessage m : ms) {
            String a = m.getOriginatingAddress();
            String b = m.getMessageBody();
            if (a == null || b == null) continue;
            StringBuilder sb = by.get(a);
            if (sb == null) { sb = new StringBuilder(); by.put(a, sb); }
            sb.append(b);
        }
        final Context app = ctx.getApplicationContext();
        final PendingResult pr = goAsync();
        new Thread(() -> {
            try {
                for (Map.Entry<String, StringBuilder> e : by.entrySet())
                    Store.onSms(app, e.getKey(), e.getValue().toString());
            } finally { pr.finish(); }
        }).start();
    }
}
