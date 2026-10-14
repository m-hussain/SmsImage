package com.smsimg;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.CRC32;

/** Collects incoming chunks on disk and assembles finished images into the gallery. */
final class Store {
    static final String CH = "rx";

    static synchronized void onSms(Context c, String from, String body) {
        if (!body.startsWith(Codec.TAG) || body.length() < 7) return;
        try {
            char t = body.charAt(4);
            String id = body.substring(5, 7);
            String hex = String.format("%04x%04x", (int) id.charAt(0), (int) id.charAt(1));
            File dir = new File(c.getFilesDir(), "rx/" + from.replaceAll("[^0-9]", "") + "_" + hex);
            dir.mkdirs();
            if (t == 'H') put(dir, "h", body);
            else if (t == 'F') put(dir, "f", body);
            else if (t == 'D' && body.length() > 9) put(dir, "d" + Codec.num(body, 7, 2), body.substring(9));
            else return;
            finish(c, dir, from);
        } catch (Exception ignored) { }
        cleanup(c);
    }

    private static void finish(Context c, File dir, String from) throws Exception {
        File hf = new File(dir, "h");
        if (!hf.exists()) return;
        String h = get(hf);
        int total = Codec.num(h, 7, 2);
        int len = Codec.num(h, 9, 4);
        boolean z = h.charAt(13) == '1';
        int have = 0;
        for (int i = 0; i < total; i++) if (new File(dir, "d" + i).exists()) have++;
        int nid = dir.getName().hashCode();
        File ff = new File(dir, "f");
        if (have < total || !ff.exists()) {
            note(c, nid, "Receiving image from " + from, have + " of " + total + " parts", null, total, have);
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < total; i++) sb.append(get(new File(dir, "d" + i)));
        byte[] packed = Codec.dec(sb.toString());
        byte[] cb = Codec.dec(get(ff).substring(7));
        long want = ((cb[0] & 0xFFL) << 24) | ((cb[1] & 0xFFL) << 16) | ((cb[2] & 0xFFL) << 8) | (cb[3] & 0xFFL);
        CRC32 k = new CRC32();
        k.update(packed);
        if (packed.length != len || k.getValue() != want) {
            note(c, nid, "Image from " + from + " was damaged", "Ask them to send it again.", null, 0, 0);
            wipe(dir);
            return;
        }
        byte[] img = z ? Codec.inflate(packed) : packed;
        Uri u = save(c, img);
        note(c, nid, "Image received from " + from, "Tap to view (saved in Pictures/SMSImage)", u, 0, 0);
        wipe(dir);
    }

    private static Uri save(Context c, byte[] img) throws Exception {
        ContentResolver cr = c.getContentResolver();
        ContentValues v = new ContentValues();
        v.put(MediaStore.Images.Media.DISPLAY_NAME, "SMSIMG_" + System.currentTimeMillis() + ".webp");
        v.put(MediaStore.Images.Media.MIME_TYPE, "image/webp");
        v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/SMSImage");
        Uri u = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
        try (OutputStream o = cr.openOutputStream(u)) { o.write(img); }
        return u;
    }

    private static void note(Context c, int nid, String title, String text, Uri view, int max, int cur) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CH, "Received images", NotificationManager.IMPORTANCE_DEFAULT));
        Notification.Builder b = new Notification.Builder(c, CH)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title).setContentText(text)
            .setOnlyAlertOnce(true).setAutoCancel(true);
        if (max > 0) b.setProgress(max, cur, false).setTimeoutAfter(10 * 60 * 1000L);
        if (view != null) {
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(view, "image/webp")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            b.setContentIntent(PendingIntent.getActivity(c, nid, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        }
        nm.notify(nid, b.build());
    }

    private static void cleanup(Context c) {
        File[] ds = new File(c.getFilesDir(), "rx").listFiles();
        if (ds == null) return;
        long cut = System.currentTimeMillis() - 3L * 24 * 3600 * 1000;
        for (File d : ds) if (d.lastModified() < cut) wipe(d);
    }

    private static void wipe(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
    }

    private static void put(File d, String n, String s) throws Exception {
        Files.write(new File(d, n).toPath(), s.getBytes(StandardCharsets.UTF_8));
    }

    private static String get(File f) throws Exception {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
