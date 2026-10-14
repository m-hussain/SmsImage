package com.smsimg;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;

public class MainActivity extends Activity {
    static final int[] DIM = {256, 384, 512, 768};
    static final int[] TGT = {4000, 8000, 16000, 32000};
    EditText number;
    Spinner quality;
    ImageView preview;
    TextView info;
    Button send;
    volatile Bitmap src;
    volatile Codec.Packed pk;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        TextView t = new TextView(this);
        t.setText("Receiving is automatic: images that arrive from another phone running this app are saved to Pictures/SMSImage and you get a notification.");
        root.addView(t);

        number = new EditText(this);
        number.setHint("Recipient number, e.g. 03001234567");
        number.setInputType(InputType.TYPE_CLASS_PHONE);
        root.addView(number);

        quality = new Spinner(this);
        quality.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Tiny (~4 KB)", "Small (~8 KB)", "Medium (~16 KB)", "Large (~32 KB)"}));
        quality.setSelection(1);
        quality.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) { prepare(); }
            public void onNothingSelected(AdapterView<?> a) { }
        });
        root.addView(quality);

        Button pick = new Button(this);
        pick.setText("Choose image");
        pick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT);
            i.setType("image/*");
            startActivityForResult(i, 7);
        });
        root.addView(pick);

        preview = new ImageView(this);
        preview.setAdjustViewBounds(true);
        preview.setMaxHeight((int) (260 * getResources().getDisplayMetrics().density));
        root.addView(preview);

        info = new TextView(this);
        root.addView(info);

        send = new Button(this);
        send.setText("Send by SMS");
        send.setEnabled(false);
        send.setOnClickListener(v -> confirmSend());
        root.addView(send);

        requestPermissions(new String[]{Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS,
            "android.permission.POST_NOTIFICATIONS"}, 1);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent d) {
        if (req == 7 && res == RESULT_OK && d != null && d.getData() != null) {
            final Uri u = d.getData();
            info.setText("Loading…");
            new Thread(() -> load(u)).start();
        }
    }

    private void load(Uri u) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / s > 2048) s *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = s;
            Bitmap bm;
            try (InputStream in = getContentResolver().openInputStream(u)) { bm = BitmapFactory.decodeStream(in, null, o); }
            int rot = 0;
            try (InputStream in = getContentResolver().openInputStream(u)) {
                int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                if (ori == 6) rot = 90; else if (ori == 3) rot = 180; else if (ori == 8) rot = 270;
            } catch (Exception ignored) { }
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                bm = Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
            }
            src = bm;
            prepare();
        } catch (Exception e) {
            runOnUiThread(() -> info.setText("Could not open that image."));
        }
    }

    private void prepare() {
        final Bitmap s = src;
        if (s == null) return;
        final int sel = quality.getSelectedItemPosition();
        runOnUiThread(() -> { info.setText("Compressing…"); send.setEnabled(false); });
        new Thread(() -> {
            Codec.Packed k = Codec.pack(Imaging.shrink(s, DIM[sel], TGT[sel]));
            pk = k;
            final Bitmap pv = BitmapFactory.decodeByteArray(k.raw, 0, k.raw.length);
            final int n = Codec.smsCount(k.data.length);
            runOnUiThread(() -> {
                preview.setImageBitmap(pv);
                info.setText(String.format("%.1f KB on the wire = %d SMS (about %d min to send)",
                    k.data.length / 1024.0, n, Math.max(1, n * 22 / 600)));
                send.setEnabled(true);
            });
        }).start();
    }

    private void confirmSend() {
        final String to = number.getText().toString().trim();
        final Codec.Packed k = pk;
        if (to.length() < 7 || k == null) { Toast.makeText(this, "Enter a number and pick an image", Toast.LENGTH_SHORT).show(); return; }
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.SEND_SMS}, 1);
            return;
        }
        int n = Codec.smsCount(k.data.length);
        new AlertDialog.Builder(this)
            .setTitle("Send " + n + " SMS?")
            .setMessage("This sends " + n + " text messages to " + to + " from your SIM and uses your SMS bundle. The other phone needs this app installed.")
            .setPositiveButton("Send", (d, w) -> doSend(to, k))
            .setNegativeButton("Cancel", null).show();
    }

    private void doSend(String to, Codec.Packed k) {
        try {
            File f = new File(getCacheDir(), "tx.bin");
            Files.write(f.toPath(), k.data);
            startForegroundService(new Intent(this, SendService.class)
                .putExtra("to", to).putExtra("path", f.getAbsolutePath()).putExtra("z", k.z));
            Toast.makeText(this, "Sending in background — see notification", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not start sending", Toast.LENGTH_SHORT).show();
        }
    }
}
