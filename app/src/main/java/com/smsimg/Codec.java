package com.smsimg;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Pure-Java wire format. Every SMS is <=160 GSM-7 characters (single segment). */
final class Codec {
    // 85 characters, all in the GSM 03.38 basic set (no escape chars, no spaces)
    static final String ALPHA =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!#%&()*+,-./:;<=>?@_$'\"";
    static final int PAY = 150;           // payload chars per data SMS (30 groups -> 120 bytes)
    static final String TAG = "@IMG";

    static final class Packed {
        byte[] raw;   // compressed image (WebP)
        byte[] data;  // what goes on the wire (raw, or deflated raw)
        boolean z;
    }

    static Packed pack(byte[] raw) {
        Packed p = new Packed();
        p.raw = raw;
        byte[] d = deflate(raw);
        if (d.length < raw.length - 16) { p.data = d; p.z = true; } else { p.data = raw; }
        return p;
    }

    static int smsCount(int packedLen) {
        int rem = packedLen % 4;
        int chars = packedLen / 4 * 5 + (rem > 0 ? rem + 1 : 0);
        return (chars + PAY - 1) / PAY + 2; // + header + footer
    }

    static List<String> messages(byte[] packed, boolean z, int id) {
        String body = enc(packed);
        int total = (body.length() + PAY - 1) / PAY;
        String ids = num(id, 2);
        List<String> out = new ArrayList<>();
        out.add(TAG + "H" + ids + num(total, 2) + num(packed.length, 4) + (z ? "1" : "0"));
        for (int i = 0; i < total; i++) {
            out.add(TAG + "D" + ids + num(i, 2) + body.substring(i * PAY, Math.min(body.length(), (i + 1) * PAY)));
        }
        CRC32 c = new CRC32();
        c.update(packed);
        long v = c.getValue();
        byte[] cb = {(byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v};
        out.add(TAG + "F" + ids + enc(cb));
        return out;
    }

    static String num(long v, int w) {
        char[] c = new char[w];
        for (int i = w - 1; i >= 0; i--) { c[i] = ALPHA.charAt((int) (v % 85)); v /= 85; }
        return new String(c);
    }

    static int num(String s, int off, int w) {
        long v = 0;
        for (int i = 0; i < w; i++) {
            int d = ALPHA.indexOf(s.charAt(off + i));
            if (d < 0) throw new IllegalArgumentException("bad char");
            v = v * 85 + d;
        }
        return (int) v;
    }

    /** Ascii85-style: 4 bytes -> 5 chars (80% efficiency). */
    static String enc(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 4) {
            int n = Math.min(4, d.length - i);
            long v = 0;
            for (int j = 0; j < 4; j++) v = (v << 8) | (j < n ? d[i + j] & 0xFF : 0);
            char[] c = new char[5];
            for (int k = 4; k >= 0; k--) { c[k] = ALPHA.charAt((int) (v % 85)); v /= 85; }
            sb.append(c, 0, n + 1);
        }
        return sb.toString();
    }

    static byte[] dec(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i += 5) {
            int m = Math.min(5, s.length() - i);
            long v = 0;
            for (int j = 0; j < 5; j++) {
                int d = j < m ? ALPHA.indexOf(s.charAt(i + j)) : 84;
                if (d < 0) throw new IllegalArgumentException("bad char");
                v = v * 85 + d;
            }
            for (int j = 0; j < m - 1; j++) o.write((int) ((v >> (24 - 8 * j)) & 0xFF));
        }
        return o.toByteArray();
    }

    static byte[] deflate(byte[] d) {
        Deflater df = new Deflater(9, true);
        df.setInput(d);
        df.finish();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!df.finished()) o.write(buf, 0, df.deflate(buf));
        df.end();
        return o.toByteArray();
    }

    static byte[] inflate(byte[] d) throws DataFormatException {
        byte[] in = new byte[d.length + 1]; // dummy trailing byte for raw inflate
        System.arraycopy(d, 0, in, 0, d.length);
        Inflater inf = new Inflater(true);
        inf.setInput(in);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (!inf.finished()) {
            int n = inf.inflate(buf);
            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break;
            o.write(buf, 0, n);
        }
        inf.end();
        return o.toByteArray();
    }
}
