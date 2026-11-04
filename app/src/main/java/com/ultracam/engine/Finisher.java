package com.ultracam.engine;

import java.util.Arrays;

/**
 * Turns the merged burst into the final picture:
 * noise-aware denoise -> 4K resample -> sharpening -> linear RGB -> white balance ->
 * local tone mapping (HDR) -> filmic highlight roll-off -> colour -> 8-bit with rotation.
 */
public final class Finisher {
    private Finisher() {}

    /** Last depth map (for debugging / preview). */
    public static volatile Depth.Map lastDepth;

    public static final class Output {
        public int[] argb; public int width, height;
        public String report;
    }

    private static final int LUT = 4096;
    private static final float[] TO_LIN = new float[LUT + 1];
    private static final float[] TO_SRGB = new float[LUT + 1];
    static {
        for (int i = 0; i <= LUT; i++) {
            double v = i / (double) LUT;
            TO_LIN[i] = (float) (v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4));
            TO_SRGB[i] = (float) (v <= 0.0031308 ? v * 12.92 : 1.055 * Math.pow(v, 1 / 2.4) - 0.055);
        }
    }
    private static float lut(float[] t, float v) {
        if (v <= 0) return 0; if (v >= 1) return t[LUT];
        float f = v * LUT; int i = (int) f; float a = f - i;
        return t[i] + (t[i + 1] - t[i]) * a;
    }

    private static float aces(float x) {
        return (x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f);
    }
    private static float smooth(float e0, float e1, float x) {
        float t = Filters.clampf((x - e0) / (e1 - e0), 0f, 1f);
        return t * t * (3 - 2 * t);
    }
    private static float log2(float v) { return (float) (Math.log(v) * 1.4426950408889634); }

    /** Steps 1-2 at sensor resolution; modifies m.y / m.u / m.v in place. Locals die on return. */
    private static void cleanSource(Merger.Merged m, final Params p, Pipeline.Progress pr, final float sigRes) {
        final int W = m.width, H = m.height, hw = W / 2, hh = H / 2;
        final float[] Y = m.y, wAcc = m.weight;

        // ---------- 1. luma denoise: fast guided filter, stronger where fewer frames merged
        pr.update("Curăț zgomotul (luma)", 0.02f);
        final float[] Ih = Filters.boxDown(Y, W, H, 2);
        if (p.lumaDenoise > 0) {
            float k = p.ispProcessed ? 1.4f : 2.2f;
            float epsL = (k * sigRes * p.lumaDenoise + 0.6f);
            epsL *= epsL;
            final float[][] ab = Filters.guidedCoeffs(Ih, Ih, hw, hh, 2, epsL);
            final float amt = Math.min(1f, p.lumaDenoise);
            Par.range(H, (a, b) -> {
                for (int y = a; y < b; y++) {
                    float hy = (y - 0.5f) * 0.5f;
                    int o = y * W, wr = (y >> 1) * hw;
                    for (int x = 0; x < W; x++) {
                        float hx = (x - 0.5f) * 0.5f;
                        float A = Filters.sample(ab[0], hw, hh, hx, hy), B = Filters.sample(ab[1], hw, hh, hx, hy);
                        float v = Y[o + x];
                        float q = A * v + B;
                        float s = Filters.clampf(1.25f / (float) Math.sqrt(wAcc[wr + (x >> 1)]), 0.35f, 1f) * amt;
                        Y[o + x] = v + s * (q - v);
                    }
                }
            });
        }

        // ---------- 1b. "Zero zgomot": multi-scale luma noise removal
        if (p.noiseFree > 0) {
            pr.update("Zero zgomot: luminozitate", 0.06f);
            Filters.multiscaleDenoise(Y, W, H, sigRes * (p.ispProcessed ? 0.8f : 1f), p.noiseFree);
            Filters.despeckle(Y, W, H, 0.6f * sigRes + 0.5f);
        }

        // ---------- 2. chroma denoise: joint guided filter (edges follow luma)
        pr.update("Curăț zgomotul de culoare", 0.10f);
        float[] U = m.u, V = m.v;
        if (p.chromaDenoise > 0) {
            float epsC = (3.0f * sigRes + 3f) * p.chromaDenoise;
            epsC *= epsC;
            int rc = Math.max(2, Math.round(5 * p.chromaDenoise));
            U = Filters.guided(Ih, U, hw, hh, rc, epsC);
            V = Filters.guided(Ih, V, hw, hh, rc, epsC);
        }
        if (p.noiseFree > 0) {
            pr.update("Zero zgomot: culoare", 0.14f);
            float sc = Math.max(0.8f, sigRes * 0.7f);
            Filters.multiscaleDenoise(U, hw, hh, sc, p.noiseFree * 1.4f);
            Filters.multiscaleDenoise(V, hw, hh, sc, p.noiseFree * 1.4f);
            Filters.despeckle(U, hw, hh, sc);
            Filters.despeckle(V, hw, hh, sc);
        }
        m.u = U; m.v = V;
    }

    public static Output render(Merger.Merged m, final Params p, Pipeline.Progress pr) {
        final int W = m.width, H = m.height, hw = W / 2, hh = H / 2;
        final float sigRes = m.sigma / (float) Math.sqrt(Math.max(1f, m.meanWeight));
        StringBuilder rep = new StringBuilder();
        rep.append(String.format("cadre=%d, zgomot cadru=%.2f, zgomot dupa fuziune=%.2f\n", m.frames, m.sigma, sigRes));
        cleanSource(m, p, pr, sigRes);


        // ---------- 3. resample to the 4K canvas (with aspect crop)
        pr.update("Redimensionez la 4K", 0.18f);
        boolean landscape = W >= H;
        int ow, oh;
        if (landscape) { ow = p.outLong; oh = Math.round(p.outLong / p.aspect); }
        else { oh = p.outLong; ow = Math.round(p.outLong / p.aspect); }
        ow &= ~1; oh &= ~1;
        float ta = ow / (float) oh, sa = W / (float) H;
        float cw, ch;
        if (sa > ta) { ch = H; cw = H * ta; } else { cw = W; ch = W / ta; }
        float cx = (W - cw) / 2f, cy = (H - ch) / 2f;
        final float[] R = Filters.resample(m.y, W, H, cx, cy, cw, ch, ow, oh);
        m.y = null;
        final float[] G = Filters.resample(m.u, hw, hh, cx / 2, cy / 2, cw / 2, ch / 2, ow, oh);
        m.u = null;
        final float[] Bl = Filters.resample(m.v, hw, hh, cx / 2, cy / 2, cw / 2, ch / 2, ow, oh);
        m.v = null;
        final float up = ow / cw;
        rep.append(String.format("sursa %dx%d -> %dx%d (scala %.2f)\n", W, H, ow, oh, up));
        final int N = ow * oh;
        final int fw = ow, fh = oh;

        // ---------- 3b. depth: what is near / in focus vs background
        pr.update("Analizez adâncimea (aproape / fundal)", 0.24f);
        final Depth.Map dm = Depth.estimate(R, fw, fh, m, cx, cy, cw, ch, sigRes);
        lastDepth = dm;
        rep.append(String.format("adancime: fundal neclar %.0f%%, paralaxa %s\n",
                dm.blurryFraction * 100, dm.parallaxUsed ? "da" : "nu"));
        final float dsx = dm.w / (float) fw, dsy = dm.h / (float) fh; // map may be coarser than 1/2

        // ---------- 3c. recover lost sharpness on the subject (deconvolution)
        if (p.detailRecovery > 0) {
            pr.update("Recuperez claritatea pierdută", 0.27f);
            int psf = up > 1.3f ? 2 : 1;
            Filters.richardsonLucy(R, fw, fh, psf, 8, dm.subject, dm.w, dm.h,
                    p.detailRecovery * (p.ispProcessed ? 0.6f : 1f));
        }

        // ---------- 4. luma sharpening (noise-thresholded, halo-limited)
        pr.update("Scot detaliile", 0.30f);
        if (p.sharpen > 0) {
            final float th = Math.max(0.8f, (1.6f + 1.2f * p.noiseFree) * sigRes * (up < 1 ? up : 1f));
            final float amount = p.sharpen * (up > 1.05f ? 1.35f : 1f) * (p.ispProcessed ? 0.55f : 1f);
            float[] blur = Filters.boxBlur(R, fw, fh, 1);
            final float[] bl = blur;
            Par.range(fh, (a, b) -> {
                for (int y = a; y < b; y++) {
                    float my = (y + 0.5f) * dsy - 0.5f;
                    for (int x = 0; x < fw; x++) {
                        int i = y * fw + x;
                        float d = R[i] - bl[i];
                        float ad = Math.abs(d);
                        if (ad < th) { float t = ad / th; d *= t * t; }
                        if (d > 22f) d = 22f; else if (d < -22f) d = -22f;
                        // subject gets full sharpening, background stays smooth
                        float sj = Filters.sample(dm.subject, dm.w, dm.h, (x + 0.5f) * dsx - 0.5f, my);
                        R[i] += amount * (0.5f + 0.7f * sj) * d;
                    }
                }
            });
            // a second, wider pass for "lens-like" micro-contrast
            blur = Filters.boxBlur(R, fw, fh, 3);
            final float[] bl2 = blur;
            final float amt2 = 0.22f * p.sharpen;
            final float th2 = 0.7f * th;
            Par.range(N, (a, b) -> {
                for (int i = a; i < b; i++) {
                    float d = R[i] - bl2[i];
                    float ad = Math.abs(d);
                    if (ad < th2) { float t = ad / th2; d *= t * t; }
                    if (d > 14f) d = 14f; else if (d < -14f) d = -14f;
                    R[i] += amt2 * d;
                }
            });
            blur = null;
        }

        // ---------- 5. YUV -> linear RGB (in place: R=Y, G=U, Bl=V)
        pr.update("Trec în spațiu liniar", 0.40f);
        Par.range(N, (a, b) -> {
            for (int i = a; i < b; i++) {
                float y = R[i], u = G[i] - 128f, v = Bl[i] - 128f;
                float r = (y + 1.402f * v) / 255f;
                float g = (y - 0.344136f * u - 0.714136f * v) / 255f;
                float bb = (y + 1.772f * u) / 255f;
                R[i] = lut(TO_LIN, r); G[i] = lut(TO_LIN, g); Bl[i] = lut(TO_LIN, bb);
            }
        });

        // ---------- 6. gentle automatic white balance (partial grey-world on midtones)
        double sr = 0, sg = 0, sbb = 0; long cnt = 0;
        for (int i = 0; i < N; i += 11) {
            float r = R[i], g = G[i], b = Bl[i];
            float L = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            float mx = Math.max(r, Math.max(g, b));
            if (L > 0.03f && L < 0.7f && mx < 0.93f) { sr += r; sg += g; sbb += b; cnt++; }
        }
        if (cnt > 1000) {
            final float wb = 0.2f;
            final float gr = Filters.clampf(1f + wb * ((float) (sg / sr) - 1f), 0.94f, 1.07f);
            final float gb = Filters.clampf(1f + wb * ((float) (sg / sbb) - 1f), 0.94f, 1.07f);
            rep.append(String.format("balans alb: R x%.3f, B x%.3f\n", gr, gb));
            Par.range(N, (a, b) -> { for (int i = a; i < b; i++) { R[i] *= gr; Bl[i] *= gb; } });
        }

        // ---------- 6b. full-frame look: deepen the defocus that is already there
        if (p.bokeh > 0 && dm.blurryFraction > 0.06f) {
            pr.update("Bokeh full-frame pe fundal", 0.46f);
            final int hw2 = fw / 2, hh2 = fh / 2;
            final int rb = Math.max(2, Math.round(fw / 900f));
            float[][] chans = {R, G, Bl};
            for (float[] chn : chans) {
                final float[] cc = chn;
                float[] sm = Filters.boxDown(cc, fw, fh, 2);
                float[] t = Filters.boxBlur(sm, hw2, hh2, rb);
                Filters.boxBlurInto(t, sm, hw2, hh2, rb);
                Filters.boxBlurInto(sm, t, hw2, hh2, rb);
                final float[] bb = t;
                final float amt = Math.min(1f, p.bokeh);
                Par.range(fh, (a, b) -> {
                    for (int y = a; y < b; y++) {
                        float my = (y + 0.5f) * dsy - 0.5f, by = (y + 0.5f) * 0.5f - 0.5f;
                        for (int x = 0; x < fw; x++) {
                            float wgt = amt * smooth(0.45f, 0.85f, Filters.sample(dm.defocus, dm.w, dm.h, (x + 0.5f) * dsx - 0.5f, my));
                            if (wgt < 0.01f) continue;
                            int i = y * fw + x;
                            float v = Filters.sample(bb, hw2, hh2, (x + 0.5f) * 0.5f - 0.5f, by);
                            cc[i] += (v - cc[i]) * wgt;
                        }
                    }
                });
            }
        }

        // ---------- 7. local tone mapping on log luminance (edge-aware base/detail split)
        pr.update("HDR local (tone mapping)", 0.50f);
        final int f = 4;
        final int sw = fw / f, sh = fh / f;
        final float[] logS = new float[sw * sh];
        Par.range(sh, (a, b) -> {
            for (int j = a; j < b; j++) {
                for (int i = 0; i < sw; i++) {
                    float acc = 0;
                    for (int yy = 0; yy < f; yy++) {
                        int o = (j * f + yy) * fw + i * f;
                        for (int xx = 0; xx < f; xx++) {
                            int k = o + xx;
                            acc += log2(0.2126f * R[k] + 0.7152f * G[k] + 0.0722f * Bl[k] + 1e-5f);
                        }
                    }
                    logS[j * sw + i] = acc / (f * f);
                }
            }
        });
        int rr = Math.max(3, Math.round(sw / 60f));
        final float[] base = Filters.guided(logS, logS, sw, sh, rr, 0.12f);
        float[] sorted = Arrays.copyOf(base, base.length);
        Arrays.sort(sorted);
        float p01 = sorted[(int) (sorted.length * 0.01f)], p99 = sorted[(int) (sorted.length * 0.99f)];
        final float keyIn = sorted[sorted.length / 2];
        sorted = null;
        float targetKey = log2(p.night ? 0.075f : 0.17f);
        float shift = (targetKey - keyIn) * 0.8f;
        shift = Filters.clampf(shift, -0.8f, 2.2f - p.captureEv);
        float range = Math.max(0.1f, p99 - p01);
        float c = Filters.clampf(7.0f / range, 0.5f, 1f);
        c = 1f - (1f - c) * p.hdrStrength;
        final float fc = c, fshift = shift;
        final float clar = p.clarity;
        final float dLo = log2(0.003f), dHi = log2(0.03f);
        rep.append(String.format("luminanta mediana=%.4f, gama dinamica=%.1f EV, compresie=%.2f, expunere %+.2f EV\n",
                Math.pow(2, keyIn), range, c, shift));

        pr.update("Randare filmică", 0.65f);
        final float pre = 0.8f;
        final float white = aces(1.6f);
        final float vib = p.vibrance;
        final int rot = ((p.rotation % 360) + 360) % 360;
        final int dw = (rot == 90 || rot == 270) ? fh : fw;
        final int dh = (rot == 90 || rot == 270) ? fw : fh;
        final int[] out = new int[dw * dh];
        Par.range(dh, (a, b) -> {
            for (int yd = a; yd < b; yd++) {
                int seed = yd * 0x9E3779B1;
                for (int xd = 0; xd < dw; xd++) {
                    int xs, ys;
                    switch (rot) {
                        case 90: xs = yd; ys = fh - 1 - xd; break;
                        case 180: xs = fw - 1 - xd; ys = fh - 1 - yd; break;
                        case 270: xs = fw - 1 - yd; ys = xd; break;
                        default: xs = xd; ys = yd;
                    }
                    int k = ys * fw + xs;
                    float r = R[k], g = G[k], bb = Bl[k];
                    float L = 0.2126f * r + 0.7152f * g + 0.0722f * bb;
                    float ll = log2(L + 1e-5f);
                    float bf = Filters.sample(base, sw, sh, (xs + 0.5f) / f - 0.5f, (ys + 0.5f) / f - 0.5f);
                    float dg = 1f + clar * smooth(dLo, dHi, bf);
                    float nl = keyIn + (bf - keyIn) * fc + fshift + (ll - bf) * dg;
                    float ratio = (float) Math.pow(2, nl - ll);
                    // keep clipped highlights white instead of dull grey
                    float mx = Math.max(r, Math.max(g, bb));
                    float clip = smooth(0.70f, 0.96f, mx);
                    ratio = ratio + (Math.max(ratio, 2.0f) - ratio) * clip;
                    r *= ratio * pre; g *= ratio * pre; bb *= ratio * pre;
                    // filmic: mix of per-channel and luminance-preserving ACES
                    float Ln = (0.2126f * r + 0.7152f * g + 0.0722f * bb);
                    float sL = Ln > 1e-6f ? aces(Ln) / Ln : 0f;
                    float r1 = 0.5f * aces(r) + 0.5f * r * sL;
                    float g1 = 0.5f * aces(g) + 0.5f * g * sL;
                    float b1 = 0.5f * aces(bb) + 0.5f * bb * sL;
                    r1 = lut(TO_SRGB, r1 / white); g1 = lut(TO_SRGB, g1 / white); b1 = lut(TO_SRGB, b1 / white);
                    // vibrance: boost muted colours more than saturated ones
                    float l2 = 0.299f * r1 + 0.587f * g1 + 0.114f * b1;
                    float mxx = Math.max(r1, Math.max(g1, b1)), mnn = Math.min(r1, Math.min(g1, b1));
                    float sat = (mxx - mnn) / (mxx + 1e-4f);
                    float boost = 1f + vib * (1f - sat) * smooth(0.02f, 0.15f, l2);
                    r1 = l2 + (r1 - l2) * boost; g1 = l2 + (g1 - l2) * boost; b1 = l2 + (b1 - l2) * boost;
                    seed = seed * 1664525 + 1013904223;
                    float dith = ((seed >>> 8) & 0xFFFF) / 65536f - 0.5f;
                    int ri = q8(r1, dith), gi = q8(g1, -dith), bi = q8(b1, dith * 0.5f);
                    out[yd * dw + xd] = 0xFF000000 | (ri << 16) | (gi << 8) | bi;
                }
            }
        });
        Output o = new Output();
        o.argb = out; o.width = dw; o.height = dh; o.report = rep.toString();
        return o;
    }

    private static int q8(float v, float d) {
        int i = (int) (v * 255f + 0.5f + d);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    /** Plain conversion of a single frame (the "before" picture), downscaled to maxLong. */
    public static Output plain(YuvFrame f, int maxLong, int rotation) {
        int W = f.width, H = f.height;
        int step = Math.max(1, (int) Math.ceil(Math.max(W, H) / (double) maxLong));
        int fw = W / step, fh = H / step;
        int rot = ((rotation % 360) + 360) % 360;
        int dw = (rot == 90 || rot == 270) ? fh : fw, dh = (rot == 90 || rot == 270) ? fw : fh;
        int[] out = new int[dw * dh];
        for (int yd = 0; yd < dh; yd++) for (int xd = 0; xd < dw; xd++) {
            int xs, ys;
            switch (rot) {
                case 90: xs = yd; ys = fh - 1 - xd; break;
                case 180: xs = fw - 1 - xd; ys = fh - 1 - yd; break;
                case 270: xs = fw - 1 - yd; ys = xd; break;
                default: xs = xd; ys = yd;
            }
            int X = xs * step, Yy = ys * step;
            float y = f.y[Yy * W + X] & 255;
            float u = (f.u[(Yy / 2) * (W / 2) + X / 2] & 255) - 128f, v = (f.v[(Yy / 2) * (W / 2) + X / 2] & 255) - 128f;
            int r = YuvFrame.clamp8(y + 1.402f * v), g = YuvFrame.clamp8(y - 0.344136f * u - 0.714136f * v), b = YuvFrame.clamp8(y + 1.772f * u);
            out[yd * dw + xd] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        Output o = new Output(); o.argb = out; o.width = dw; o.height = dh; o.report = "";
        return o;
    }
}
