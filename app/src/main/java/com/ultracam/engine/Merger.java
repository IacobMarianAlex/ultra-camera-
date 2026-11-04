package com.ultracam.engine;

/**
 * Robust multi-frame merge. Each alternate frame is aligned to the reference and
 * accumulated pixel by pixel with a weight that drops where it disagrees with the
 * reference beyond what noise explains (moving objects -> no ghosts).
 * Frames are streamed: only the reference and one alternate are in memory.
 */
public final class Merger {

    public static final class Merged {
        public int width, height, frames;
        public float[] y, u, v;   // y full-res, u/v half-res, 0..255
        public float[] weight;    // half-res accumulated weight (1 = reference only)
        public float sigma;       // single-frame luma noise (full-res, 0..255)
        public float meanWeight;
        /** Per-tile parallax (half-res px): high = near the camera. Grid tw x th, tile = 32 full-res px. */
        public float[] parallax; public int tw, th;
    }

    private final int W, H, hw, hh;
    private final float[] accY, accU, accV, wAcc;
    private final Align.Pyramid refPyr;
    private final float[] refBlur;
    private final float refMean;
    private final float refSharp;
    private final float sigmaHalf, sigmaFull, sigmaChroma;
    private final byte[] refU, refV;
    private final float ghostStrength;
    private int frames = 1;
    private float[] parallax;
    private int ptw, pth;

    /** @param ghostStrength 1 = normal; larger = more tolerant (more noise reduction, more ghost risk). */
    public Merger(YuvFrame ref, float ghostStrength) {
        W = ref.width; H = ref.height; hw = W / 2; hh = H / 2;
        this.ghostStrength = ghostStrength;
        float[] refHalf = ref.halfLuma();
        refSharp = Filters.sharpness(refHalf, hw, hh);
        sigmaHalf = Filters.estimateNoise(refHalf, hw, hh);
        refMean = robustMean(refHalf);
        refPyr = Align.build(refHalf, hw, hh, Align.levelsFor(hw, hh));
        refBlur = Filters.boxBlur(refHalf, hw, hh, 1);

        accY = new float[W * H];
        final byte[] ry = ref.y;
        Par.range(W * H, (a, b) -> { for (int i = a; i < b; i++) accY[i] = ry[i] & 255; });
        accU = new float[hw * hh]; accV = new float[hw * hh]; wAcc = new float[hw * hh];
        final byte[] ru = ref.u, rv = ref.v;
        Par.range(hw * hh, (a, b) -> {
            for (int i = a; i < b; i++) { accU[i] = ru[i] & 255; accV[i] = rv[i] & 255; wAcc[i] = 1f; }
        });
        // full-res sigma: estimate on a full-res crop of luma
        sigmaFull = estimateFull(ref);
        refU = ref.u; refV = ref.v;
        float[] uf = new float[hw * hh];
        for (int i = 0; i < uf.length; i++) uf[i] = ref.u[i] & 255;
        sigmaChroma = Math.max(1f, Filters.estimateNoise(uf, hw, hh));
    }

    private static float estimateFull(YuvFrame f) {
        int cw = Math.min(f.width, 1024), ch = Math.min(f.height, 1024);
        int x0 = (f.width - cw) / 2, y0 = (f.height - ch) / 2;
        float[] c = new float[cw * ch];
        for (int y = 0; y < ch; y++) for (int x = 0; x < cw; x++) c[y * cw + x] = f.y[(y0 + y) * f.width + x0 + x] & 255;
        return Filters.estimateNoise(c, cw, ch);
    }

    private static float robustMean(float[] img) {
        double s = 0; long n = 0;
        for (int i = 0; i < img.length; i += 7) {
            float v = img[i];
            if (v > 8 && v < 247) { s += v; n++; }
        }
        return n > 0 ? (float) (s / n) : 128f;
    }

    public float referenceSharpness() { return refSharp; }

    /** Aligns and merges one alternate frame. Returns the fraction of pixels that were used. */
    public float add(YuvFrame alt) {
        if (alt.width != W || alt.height != H) return 0f;
        final float[] altHalf = alt.halfLuma();
        float am = robustMean(altHalf);
        final float gain = Filters.clampf(refMean / Math.max(1f, am), 0.7f, 1.45f);
        if (Math.abs(gain - 1f) > 0.005f) {
            Par.range(altHalf.length, (a, b) -> { for (int i = a; i < b; i++) altHalf[i] *= gain; });
        }
        float altSharp = Filters.sharpness(altHalf, hw, hh);
        // blurrier frames (hand shake during exposure) count less
        final float frameW = Filters.clampf(altSharp / Math.max(1e-3f, refSharp), 0.25f, 1f);

        Align.Pyramid ap = Align.build(altHalf, hw, hh, refPyr.levels());
        final Align al = Align.align(refPyr, ap);
        ap = null;
        final float[] altBlur = Filters.boxBlur(altHalf, hw, hh, 1);
        accumulateParallax(al);

        // per-pixel robustness weight at half resolution
        final float[] wmap = new float[hw * hh];
        final float sb = sigmaHalf / 3f;
        final float noiseScale = (float) Math.sqrt(2) * sb + 1.2f;
        final float thr = 2.0f * ghostStrength;
        Par.range(hh, (a, b) -> {
            for (int j = a; j < b; j++) {
                for (int i = 0; i < hw; i++) {
                    float ddx = al.dispX(i, j), ddy = al.dispY(i, j);
                    float sx = i + ddx, sy = j + ddy;
                    float w;
                    if (sx < 0 || sy < 0 || sx > hw - 1 || sy > hh - 1) w = 0f;
                    else {
                        float av = Filters.sample(altBlur, hw, hh, sx, sy);
                        float rv = refBlur[j * hw + i];
                        float t = Math.abs(av - rv) / (noiseScale + 0.015f * rv);
                        // colour difference catches moving objects with similar brightness
                        float du = Filters.sampleB(alt.u, hw, hh, sx, sy) - (refU[j * hw + i] & 255);
                        float dv = Filters.sampleB(alt.v, hw, hh, sx, sy) - (refV[j * hw + i] & 255);
                        float tc = (Math.abs(du) + Math.abs(dv)) / (2.5f * sigmaChroma + 2f);
                        if (tc > t) t = tc;
                        float e = t - thr;
                        w = e <= 0 ? 1f : (float) Math.exp(-e * e);
                    }
                    wmap[j * hw + i] = w * frameW;
                }
            }
        });
        final float[] ws = Filters.boxBlur(wmap, hw, hh, 1);
        // min of raw and smoothed: keeps ghost rejection sharp but removes speckle
        Par.range(ws.length, (a, b) -> { for (int i = a; i < b; i++) ws[i] = Math.min(ws[i], wmap[i] * 1.15f); });

        // accumulate luma (full res) — displacement is doubled at full res
        final byte[] ay = alt.y;
        final float lumaGain = gain;
        Par.range(H, (a, b) -> {
            for (int y = a; y < b; y++) {
                float hy = (y - 0.5f) * 0.5f;
                int wrow = (y >> 1) * hw;
                int o = y * W;
                for (int x = 0; x < W; x++) {
                    float w = ws[wrow + (x >> 1)];
                    if (w <= 0.002f) continue;
                    float hx = (x - 0.5f) * 0.5f;
                    float sx = x + 2f * al.dispX(hx, hy), sy = y + 2f * al.dispY(hx, hy);
                    accY[o + x] += w * Filters.sampleB(ay, W, H, sx, sy) * lumaGain;
                }
            }
        });
        final byte[] au = alt.u, av = alt.v;
        Par.range(hh, (a, b) -> {
            for (int j = a; j < b; j++) {
                for (int i = 0; i < hw; i++) {
                    int k = j * hw + i;
                    float w = ws[k];
                    if (w <= 0.002f) continue;
                    float sx = i + al.dispX(i, j), sy = j + al.dispY(i, j);
                    accU[k] += w * Filters.sampleB(au, hw, hh, sx, sy);
                    accV[k] += w * Filters.sampleB(av, hw, hh, sx, sy);
                    wAcc[k] += w;
                }
            }
        });
        frames++;
        double used = 0;
        for (int i = 0; i < ws.length; i += 13) used += ws[i];
        return (float) (used / Math.ceil(ws.length / 13.0));
    }

    /** Fits the global (affine) hand-shake motion; what remains per tile is parallax = depth. */
    private void accumulateParallax(Align al) {
        int tw = al.tw, th = al.th, n = tw * th;
        if (parallax == null) { parallax = new float[n]; ptw = tw; pth = th; }
        double[][] A = new double[3][3];
        double[] bx = new double[3], by = new double[3];
        for (int j = 0; j < th; j++) for (int i = 0; i < tw; i++) {
            double[] f = {1, i, j};
            int t = j * tw + i;
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) A[r][c] += f[r] * f[c];
                bx[r] += f[r] * al.dx[t]; by[r] += f[r] * al.dy[t];
            }
        }
        double[] cx = solve3(A, bx), cy = solve3(A, by);
        if (cx == null || cy == null) return;
        for (int j = 0; j < th; j++) for (int i = 0; i < tw; i++) {
            int t = j * tw + i;
            double ex = al.dx[t] - (cx[0] + cx[1] * i + cx[2] * j);
            double ey = al.dy[t] - (cy[0] + cy[1] * i + cy[2] * j);
            float r = (float) Math.sqrt(ex * ex + ey * ey);
            parallax[t] += r;
        }
    }

    private static double[] solve3(double[][] m, double[] b) {
        double[][] a = new double[3][4];
        for (int i = 0; i < 3; i++) { System.arraycopy(m[i], 0, a[i], 0, 3); a[i][3] = b[i]; }
        for (int c = 0; c < 3; c++) {
            int piv = c;
            for (int r = c + 1; r < 3; r++) if (Math.abs(a[r][c]) > Math.abs(a[piv][c])) piv = r;
            double[] tmp = a[c]; a[c] = a[piv]; a[piv] = tmp;
            if (Math.abs(a[c][c]) < 1e-9) return null;
            for (int r = 0; r < 3; r++) if (r != c) {
                double f = a[r][c] / a[c][c];
                for (int k = c; k < 4; k++) a[r][k] -= f * a[c][k];
            }
        }
        return new double[]{a[0][3] / a[0][0], a[1][3] / a[1][1], a[2][3] / a[2][2]};
    }

    public Merged finish() {
        final Merged m = new Merged();
        m.width = W; m.height = H; m.frames = frames;
        Par.range(H, (a, b) -> {
            for (int y = a; y < b; y++) {
                int wrow = (y >> 1) * hw, o = y * W;
                for (int x = 0; x < W; x++) accY[o + x] /= wAcc[wrow + (x >> 1)];
            }
        });
        double s = 0;
        for (int i = 0; i < wAcc.length; i++) {
            float w = wAcc[i];
            accU[i] /= w; accV[i] /= w; s += w;
        }
        m.y = accY; m.u = accU; m.v = accV; m.weight = wAcc;
        m.sigma = sigmaFull;
        m.meanWeight = (float) (s / wAcc.length);
        if (parallax != null) {
            for (int i = 0; i < parallax.length; i++) parallax[i] /= Math.max(1, frames - 1);
            m.parallax = parallax; m.tw = ptw; m.th = pth;
        }
        return m;
    }
}
