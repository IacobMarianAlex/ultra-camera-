package com.ultracam.engine;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

/** Planar YUV 4:2:0 frame (full-range, 8 bit). Width and height are even. */
public final class YuvFrame {
    private static final int MAGIC = 0x55435946;

    public final int width, height;
    public final byte[] y, u, v;

    public YuvFrame(int w, int h) {
        if ((w & 1) != 0 || (h & 1) != 0) throw new IllegalArgumentException("odd size");
        width = w; height = h;
        y = new byte[w * h];
        u = new byte[(w / 2) * (h / 2)];
        v = new byte[(w / 2) * (h / 2)];
    }

    public void write(File f) throws IOException {
        try (DataOutputStream o = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(f), 1 << 20))) {
            o.writeInt(MAGIC); o.writeInt(width); o.writeInt(height);
            o.write(y); o.write(u); o.write(v);
        }
    }

    public static YuvFrame read(File f) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f), 1 << 20))) {
            if (in.readInt() != MAGIC) throw new IOException("bad frame file " + f);
            int w = in.readInt(), h = in.readInt();
            YuvFrame fr = new YuvFrame(w, h);
            in.readFully(fr.y); in.readFully(fr.u); in.readFully(fr.v);
            return fr;
        }
    }

    /** Builds a frame from packed ARGB pixels (used for JPEG fallback frames). */
    public static YuvFrame fromArgb(int[] px, int srcW, int w, int h) {
        YuvFrame f = new YuvFrame(w, h);
        final int hw = w / 2;
        Par.range(h / 2, (s, e) -> {
            for (int j = s; j < e; j++) {
                for (int i = 0; i < hw; i++) {
                    float su = 0, sv = 0;
                    for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                        int x = 2 * i + dx, yy = 2 * j + dy;
                        int c = px[yy * srcW + x];
                        float r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
                        float Y = 0.299f * r + 0.587f * g + 0.114f * b;
                        f.y[yy * w + x] = (byte) clamp8(Y);
                        su += -0.168736f * r - 0.331264f * g + 0.5f * b;
                        sv += 0.5f * r - 0.418688f * g - 0.081312f * b;
                    }
                    f.u[j * hw + i] = (byte) clamp8(su * 0.25f + 128f);
                    f.v[j * hw + i] = (byte) clamp8(sv * 0.25f + 128f);
                }
            }
        });
        return f;
    }

    static int clamp8(float v) {
        int i = (int) (v + 0.5f);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    /** 2x2 averaged luma, (w/2) x (h/2). Pixel i covers full-res pixels 2i, 2i+1. */
    public float[] halfLuma() {
        final int hw = width / 2, hh = height / 2;
        final float[] o = new float[hw * hh];
        Par.range(hh, (s, e) -> {
            for (int j = s; j < e; j++) {
                int r0 = 2 * j * width, r1 = r0 + width, oi = j * hw;
                for (int i = 0; i < hw; i++) {
                    int x = 2 * i;
                    o[oi + i] = ((y[r0 + x] & 255) + (y[r0 + x + 1] & 255) + (y[r1 + x] & 255) + (y[r1 + x + 1] & 255)) * 0.25f;
                }
            }
        });
        return o;
    }
}
