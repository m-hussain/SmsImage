package com.smsimg;

import android.graphics.Bitmap;
import android.os.Build;
import java.io.ByteArrayOutputStream;

final class Imaging {
    /** Shrinks and lossy-compresses until the image fits targetBytes (or we give up). */
    static byte[] shrink(Bitmap src, int maxDim, int targetBytes) {
        Bitmap.CompressFormat fmt = Build.VERSION.SDK_INT >= 30
            ? Bitmap.CompressFormat.WEBP_LOSSY : Bitmap.CompressFormat.WEBP;
        int dim = maxDim;
        byte[] best = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            float r = Math.min(1f, dim / (float) Math.max(src.getWidth(), src.getHeight()));
            int w = Math.max(1, Math.round(src.getWidth() * r));
            int h = Math.max(1, Math.round(src.getHeight() * r));
            Bitmap b = r < 1f ? Bitmap.createScaledBitmap(src, w, h, true) : src;
            for (int q = 70; q >= 20; q -= 10) {
                ByteArrayOutputStream o = new ByteArrayOutputStream();
                b.compress(fmt, q, o);
                best = o.toByteArray();
                if (best.length <= targetBytes) return best;
            }
            dim = (int) (dim * 0.8f);
        }
        return best;
    }
}
