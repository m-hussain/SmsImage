package com.smsimg;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.telephony.SmsManager;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

public class SendService extends Service {
    static final String CH = "tx", ACT = "com.smsimg.SENT";
    static final long GAP_MS = 2200; // ~27 SMS/min: stays under Android's "too many SMS" prompt

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent in, int flags, int startId) {
        final String to = in.getStringExtra("to");
        final String path = in.getStringExtra("path");
        final boolean z = in.getBooleanExtra("z", false);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Sending image", NotificationManager.IMPORTANCE_LOW));
        startForeground(2, note("Preparing…", 0, 0, true), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        new Thread(() -> run(to, path, z)).start();
        return START_NOT_STICKY;
    }

    private Notification note(String text, int max, int cur, boolean ongoing) {
        Notification.Builder b = new Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("SMS Image").setContentText(text).setOngoing(ongoing);
        if (max > 0) b.setProgress(max, cur, false);
        return b.build();
    }

    private void run(String to, String path, boolean z) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        final AtomicInteger failed = new AtomicInteger();
        BroadcastReceiver rcv = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (getResultCode() != Activity.RESULT_OK) failed.incrementAndGet();
            }
        };
        IntentFilter f = new IntentFilter(ACT);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(rcv, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(rcv, f);
        int sent = 0;
        try {
            byte[] packed = Files.readAllBytes(new File(path).toPath());
            List<String> msgs = Codec.messages(packed, z, new Random().nextInt(85 * 85));
            SmsManager sm = Build.VERSION.SDK_INT >= 31 ? getSystemService(SmsManager.class) : SmsManager.getDefault();
            for (int i = 0; i < msgs.size(); i++) {
                PendingIntent pi = PendingIntent.getBroadcast(this, i,
                    new Intent(ACT).setPackage(getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                sm.sendTextMessage(to, null, msgs.get(i), pi, null);
                sent++;
                nm.notify(2, note("Sending " + sent + " of " + msgs.size(), msgs.size(), sent, true));
                Thread.sleep(GAP_MS);
            }
            Thread.sleep(4000); // let the last status callbacks arrive
        } catch (Exception e) {
            failed.incrementAndGet();
        }
        try { unregisterReceiver(rcv); } catch (Exception ignored) { }
        stopForeground(true);
        String r = failed.get() == 0 ? "Sent " + sent + " messages" : "Done, but " + failed.get() + " failed (check balance/network)";
        nm.notify(3, note(r, 0, 0, false));
        stopSelf();
    }
}
