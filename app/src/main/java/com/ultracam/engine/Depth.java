package com.ultracam.engine;

import java.util.Arrays;

/**
 * Estimates what is near / in focus (the subject) and what is background, without a neural net:
 *  1. focus map: ratio of fine-scale to coarse-scale edge energy (sharp edges = in focus),
 *  2. parallax from the burst: near objects move differently from the background as the hand shakes.
 * The cues are fused, propagated into flat areas and snapped to object edges with a guided filter.
 * Output: low-res map, 1 = subject / near & sharp, 0 = far / out of focus.
 */
public final class Depth {
    private Depth() {}

    public static final class Map {
        public float[] subject; public int w, h;
        public float blurryFraction; // how much of the frame is clearly out of focus
        public boolean parallaxUsed;
        /** Absolute out-of-focus confidence (0..1) — only here is extra bokeh allowed. */
        public float[] defocus;
    }

    /**
     * @param Y      output-resolution luma (0..255)
     * @param m      merge info (parallax grid)
     * @param cx,cy,cw,ch crop in source coordinates that produced Y; ow/oh = Y size
     */
    public static Map estimate(float[] Y, int ow, int oh, Merger.Merged m,
                               float cx, float cy, float cw, float ch, float sigma) {
        // measure focus at (roughly) the sensor's native scale, so upscaling isn't mistaken for blur
        final int f = Math.max(2, Math.round(2f * ow / cw));
        final int w = ow / f, h = oh / f;
        final float[] Yd = Filters.boxDown(Y, ow, oh, f);
        final float[] b1 = Filters.boxBlur(Yd, w, h, 1);
        final float[] b4 = Filters.boxBlur(Yd, w, h, 4);
        final float[] hf = new float[w * h], lf = new float[w * h];
        Par.range(w * h, (a, e) -> {
            for (int i = a; i < e; i++) { hf[i] = Math.abs(Yd[i] - b1[i]); lf[i] = Math.abs(b1[i] - b4[i]); }
        });
        final int rr = Math.max(4, w / 160);
        final float[] E1 = Filters.boxBlur(hf, w, h, rr);
        final float[] E2 = Filters.boxBlur(lf, w, h, rr);
        final float nf = 0.5f * sigma + 0.6f;
        final float[] focus = hf, conf = lf; // reuse
        Par.range(w * h, (a, e) -> {
            for (int i = a; i < e; i++) {
                float e2 = E2[i];
                focus[i] = E1[i] / (e2 + nf);
                conf[i] = e2 / (e2 + 3f * nf);
            }
        });
        // relative + absolute focus scaling
        float[] tmp = new float[(w * h) / 7 + 1];
        int c = 0;
        for (int i = 0; i < w * h; i += 7) if (conf[i] > 0.5f) tmp[c++] = focus[i];
        float lo, hi;
        if (c > 100) {
            Arrays.sort(tmp, 0, c);
            lo = tmp[(int) (c * 0.15f)]; hi = tmp[(int) (c * 0.9f)];
        } else { lo = 0.2f; hi = 0.6f; }
        if (hi - lo < 0.05f) hi = lo + 0.05f;
        final float flo = lo, fhi = hi;

        // parallax sampled on this grid
        boolean usePar = false;
        float parScale = 1f;
        if (m.parallax != null) {
            float[] ps = Arrays.copyOf(m.parallax, m.parallax.length);
            Arrays.sort(ps);
            float p90 = ps[(int) (ps.length * 0.9f)], p50 = ps[ps.length / 2];
            if (p90 - p50 > 0.25f) { usePar = true; parScale = 1f / (p90 - p50); }
        }
        final boolean fusePar = usePar;
        final float pScale = parScale;
        final float sx = cw / ow, sy = ch / oh;
        final float[] num = new float[w * h], den = new float[w * h], absDef = new float[w * h];
        Par.range(h, (a, e) -> {
            for (int y = a; y < e; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    float fn = Filters.clampf((focus[i] - flo) / (fhi - flo), 0f, 1f);
                    float v = fn;
                    if (fusePar) {
                        float srcX = cx + (x * f + f * 0.5f) * sx, srcY = cy + (y * f + f * 0.5f) * sy;
                        float gx = (srcX * 0.5f - Align.TILE * 0.5f) / Align.TILE;
                        float gy = (srcY * 0.5f - Align.TILE * 0.5f) / Align.TILE;
                        float pr = Filters.sample(m.parallax, m.tw, m.th, gx, gy);
                        float pn = Filters.clampf((pr - 0f) * pScale * 0.5f, 0f, 1f);
                        v = 0.75f * fn + 0.25f * Math.max(fn, pn);
                    }
                    float cf = conf[i];
                    num[i] = v * cf; den[i] = cf;
                    // absolute defocus: real texture present but almost no fine-scale energy
                    absDef[i] = cf * Filters.clampf((0.4f * fhi - focus[i]) / (0.25f * fhi), 0f, 1f);
                }
            }
        });
        // propagate into flat regions (normalised convolution)
        final int pr = Math.max(8, w / 40);
        final float[] N = Filters.boxBlur(num, w, h, pr), D = Filters.boxBlur(den, w, h, pr);
        final float[] AD = Filters.boxBlur(absDef, w, h, pr / 2);
        final float[] raw = num;
        Par.range(w * h, (a, e) -> {
            for (int i = a; i < e; i++) raw[i] = D[i] > 1e-3f ? N[i] / D[i] : 0.5f;
        });
        // snap to object edges
        final float[] guide = new float[w * h];
        Par.range(w * h, (a, e) -> { for (int i = a; i < e; i++) guide[i] = Yd[i] / 255f; });
        float[] subj = Filters.guided(guide, raw, w, h, Math.max(6, w / 120), 0.002f);
        float[] def = Filters.guided(guide, AD, w, h, Math.max(6, w / 120), 0.002f);
        int blurry = 0;
        for (int i = 0; i < subj.length; i++) {
            subj[i] = Filters.clampf(subj[i], 0f, 1f);
            def[i] = Filters.clampf(def[i] * 2.2f, 0f, 1f) * (1f - subj[i]);
            if (def[i] > 0.5f) blurry++;
        }
        Map out = new Map();
        out.subject = subj; out.defocus = def; out.w = w; out.h = h;
        out.blurryFraction = blurry / (float) subj.length;
        out.parallaxUsed = usePar;
        return out;
    }
}
