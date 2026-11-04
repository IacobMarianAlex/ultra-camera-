package com.ultracam.engine;

import java.util.Arrays;

/** Image filters on float planes. All are multi-threaded and memory-conscious. */
public final class Filters {
    private Filters() {}

    static int clampi(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
    static float clampf(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    /** Box blur of radius r (window 2r+1), edge-replicated. Uses one extra plane. */
    public static float[] boxBlur(final float[] s, final int w, final int h, final int r) {
        final float[] d = new float[w * h];
        boxBlurInto(s, d, w, h, r);
        return d;
    }

    /** Box blur into a caller-provided plane (d must not alias s). */
    public static void boxBlurInto(final float[] s, final float[] d, final int w, final int h, final int r) {
        if (r <= 0) { System.arraycopy(s, 0, d, 0, s.length); return; }
        final double inv = 1.0 / (2 * r + 1);
        // vertical: s -> d, column chunks with row-contiguous access
        Par.range(w, (a, b) -> {
            int n = b - a;
            double[] acc = new double[n];
            for (int k = -r; k <= r; k++) {
                int row = clampi(k, 0, h - 1) * w;
                for (int x = 0; x < n; x++) acc[x] += s[row + a + x];
            }
            for (int y = 0; y < h; y++) {
                int o = y * w + a;
                int add = Math.min(y + r + 1, h - 1) * w + a;
                int sub = Math.max(y - r, 0) * w + a;
                for (int x = 0; x < n; x++) {
                    d[o + x] = (float) (acc[x] * inv);
                    acc[x] += s[add + x] - s[sub + x];
                }
            }
        });
        // horizontal in place on d with a row buffer
        Par.range(h, (a, b) -> {
            float[] row = new float[w];
            for (int y = a; y < b; y++) {
                int o = y * w;
                System.arraycopy(d, o, row, 0, w);
                double acc = 0;
                for (int k = -r; k <= r; k++) acc += row[clampi(k, 0, w - 1)];
                for (int x = 0; x < w; x++) {
                    d[o + x] = (float) (acc * inv);
                    acc += row[Math.min(x + r + 1, w - 1)] - row[Math.max(x - r, 0)];
                }
            }
        });
    }

    /** Integer-factor box downsample (floor size). */
    public static float[] boxDown(final float[] s, final int w, final int h, final int f) {
        final int dw = w / f, dh = h / f;
        final float[] d = new float[dw * dh];
        final float inv = 1f / (f * f);
        Par.range(dh, (a, b) -> {
            for (int j = a; j < b; j++) {
                for (int i = 0; i < dw; i++) {
                    float acc = 0;
                    for (int y = 0; y < f; y++) {
                        int o = (j * f + y) * w + i * f;
                        for (int x = 0; x < f; x++) acc += s[o + x];
                    }
                    d[j * dw + i] = acc * inv;
                }
            }
        });
        return d;
    }

    /** Gaussian-like 2x downsample ([1 3 3 1]/8 separable) used for alignment pyramids. */
    public static float[] down2(final float[] s, final int w, final int h) {
        final int dw = w / 2, dh = h / 2;
        final float[] d = new float[dw * dh];
        final float[] k = {1 / 8f, 3 / 8f, 3 / 8f, 1 / 8f};
        Par.range(dh, (a, b) -> {
            for (int j = a; j < b; j++) {
                for (int i = 0; i < dw; i++) {
                    float acc = 0;
                    for (int y = 0; y < 4; y++) {
                        int yy = clampi(2 * j - 1 + y, 0, h - 1) * w;
                        float ky = k[y];
                        for (int x = 0; x < 4; x++) {
                            acc += ky * k[x] * s[yy + clampi(2 * i - 1 + x, 0, w - 1)];
                        }
                    }
                    d[j * dw + i] = acc;
                }
            }
        });
        return d;
    }

    /** Bilinear sample with edge clamp. */
    public static float sample(float[] img, int w, int h, float x, float y) {
        if (x < 0) x = 0; else if (x > w - 1) x = w - 1;
        if (y < 0) y = 0; else if (y > h - 1) y = h - 1;
        int x0 = (int) x, y0 = (int) y;
        int x1 = x0 + 1 < w ? x0 + 1 : x0, y1 = y0 + 1 < h ? y0 + 1 : y0;
        float fx = x - x0, fy = y - y0;
        float a = img[y0 * w + x0], b = img[y0 * w + x1], c = img[y1 * w + x0], d = img[y1 * w + x1];
        return (a + (b - a) * fx) + ((c + (d - c) * fx) - (a + (b - a) * fx)) * fy;
    }

    public static float sampleB(byte[] img, int w, int h, float x, float y) {
        if (x < 0) x = 0; else if (x > w - 1) x = w - 1;
        if (y < 0) y = 0; else if (y > h - 1) y = h - 1;
        int x0 = (int) x, y0 = (int) y;
        int x1 = x0 + 1 < w ? x0 + 1 : x0, y1 = y0 + 1 < h ? y0 + 1 : y0;
        float fx = x - x0, fy = y - y0;
        float a = img[y0 * w + x0] & 255, b = img[y0 * w + x1] & 255, c = img[y1 * w + x0] & 255, d = img[y1 * w + x1] & 255;
        float top = a + (b - a) * fx, bot = c + (d - c) * fx;
        return top + (bot - top) * fy;
    }

    /**
     * Guided filter coefficients (He et al.). Returns {meanA, meanB} so that q = meanA*I + meanB.
     * Works at the resolution of I and p; caller may upsample coefficients (fast guided filter).
     */
    public static float[][] guidedCoeffs(final float[] I, final float[] p, final int w, final int h, final int r, final float eps) {
        final int n = w * h;
        final boolean self = (I == p);
        float[] mI = boxBlur(I, w, h, r);
        float[] mP = self ? mI : boxBlur(p, w, h, r);
        float[] tmp = new float[n];
        Par.range(n, (a, b) -> { for (int i = a; i < b; i++) tmp[i] = I[i] * p[i]; });
        float[] mIp = boxBlur(tmp, w, h, r);
        float[] mII;
        if (self) mII = mIp;
        else {
            Par.range(n, (a, b) -> { for (int i = a; i < b; i++) tmp[i] = I[i] * I[i]; });
            mII = boxBlur(tmp, w, h, r);
        }
        final float[] A = tmp; // reuse
        final float[] B = mIp;  // reuse after reading
        final float[] fmP = mP, fmII = mII;
        Par.range(n, (a, b) -> {
            for (int i = a; i < b; i++) {
                float mi = mI[i];
                float cov = B[i] - mi * fmP[i];
                float var = fmII[i] - mi * mi;
                float aa = cov / (var + eps);
                A[i] = aa;
                B[i] = fmP[i] - aa * mi;
            }
        });
        return new float[][]{boxBlur(A, w, h, r), boxBlur(B, w, h, r)};
    }

    /** Full guided filter at the same resolution. */
    public static float[] guided(float[] I, float[] p, int w, int h, int r, float eps) {
        float[][] ab = guidedCoeffs(I, p, w, h, r, eps);
        float[] a = ab[0], b = ab[1];
        float[] q = new float[w * h];
        Par.range(w * h, (s, e) -> { for (int i = s; i < e; i++) q[i] = a[i] * I[i] + b[i]; });
        return q;
    }

    private static float cubic(float x) { // Catmull-Rom
        x = Math.abs(x);
        if (x < 1f) return (1.5f * x - 2.5f) * x * x + 1f;
        if (x < 2f) return ((-0.5f * x + 2.5f) * x - 4f) * x + 2f;
        return 0f;
    }

    private static final class Kernel {
        int taps; int[] idx; float[] wt;
    }

    private static Kernel kernel(int srcLen, float start, float len, int dstLen) {
        float scale = len / dstLen;
        float fs = Math.max(1f, scale);
        float support = 2f * fs;
        Kernel k = new Kernel();
        k.taps = (int) Math.ceil(support * 2) + 1;
        k.idx = new int[dstLen * k.taps];
        k.wt = new float[dstLen * k.taps];
        for (int x = 0; x < dstLen; x++) {
            float c = start + (x + 0.5f) * scale - 0.5f;
            int first = (int) Math.floor(c - support) + 1;
            float sum = 0;
            for (int t = 0; t < k.taps; t++) {
                int si = first + t;
                float wv = cubic((si - c) / fs);
                k.idx[x * k.taps + t] = clampi(si, 0, srcLen - 1);
                k.wt[x * k.taps + t] = wv;
                sum += wv;
            }
            if (sum != 0) for (int t = 0; t < k.taps; t++) k.wt[x * k.taps + t] /= sum;
        }
        return k;
    }

    /**
     * Separable high-quality resample of the crop (cx,cy,cw,ch) of src into dw x dh.
     * Antialiased when shrinking, Catmull-Rom when enlarging.
     */
    public static float[] resample(final float[] src, final int sw, final int sh,
                                   float cx, float cy, float cw, float ch, final int dw, final int dh) {
        final Kernel kx = kernel(sw, cx, cw, dw);
        final Kernel ky = kernel(sh, cy, ch, dh);
        // rows of src actually needed
        int minRow = sh, maxRow = -1;
        for (int i : ky.idx) { if (i < minRow) minRow = i; if (i > maxRow) maxRow = i; }
        final int r0 = minRow, rows = maxRow - minRow + 1;
        final float[] tmp = new float[dw * rows];
        Par.range(rows, (a, b) -> {
            for (int j = a; j < b; j++) {
                int so = (r0 + j) * sw, to = j * dw;
                for (int x = 0; x < dw; x++) {
                    int base = x * kx.taps;
                    float acc = 0;
                    for (int t = 0; t < kx.taps; t++) acc += src[so + kx.idx[base + t]] * kx.wt[base + t];
                    tmp[to + x] = acc;
                }
            }
        });
        final float[] dst = new float[dw * dh];
        Par.range(dh, (a, b) -> {
            for (int y = a; y < b; y++) {
                int base = y * ky.taps, o = y * dw;
                for (int t = 0; t < ky.taps; t++) {
                    float wv = ky.wt[base + t];
                    if (wv == 0) continue;
                    int to = (ky.idx[base + t] - r0) * dw;
                    for (int x = 0; x < dw; x++) dst[o + x] += tmp[to + x] * wv;
                }
            }
        });
        return dst;
    }

    /** Noise sigma estimate from the flattest blocks of a plane (0..255 units). */
    public static float estimateNoise(final float[] img, final int w, final int h) {
        final int B = 16;
        final int bw = (w - 2) / B, bh = (h - 2) / B;
        final float[] vars = new float[bw * bh];
        Par.range(bh, (a, b) -> {
            for (int j = a; j < b; j++) {
                for (int i = 0; i < bw; i++) {
                    double s = 0, s2 = 0, m = 0;
                    int cnt = 0;
                    for (int y = 1 + j * B; y < 1 + (j + 1) * B; y++) {
                        int o = y * w;
                        for (int x = 1 + i * B; x < 1 + (i + 1) * B; x++) {
                            float c = img[o + x];
                            float hp = c - 0.25f * (img[o + x - 1] + img[o + x + 1] + img[o + x - w] + img[o + x + w]);
                            s += hp; s2 += hp * hp; m += c; cnt++;
                        }
                    }
                    m /= cnt;
                    double var = s2 / cnt - (s / cnt) * (s / cnt);
                    vars[j * bw + i] = (m < 12 || m > 243) ? Float.MAX_VALUE : (float) var;
                }
            }
        });
        Arrays.sort(vars);
        int valid = 0;
        while (valid < vars.length && vars[valid] != Float.MAX_VALUE) valid++;
        if (valid == 0) return 2f;
        float v = vars[(int) (valid * 0.15f)];
        return (float) Math.sqrt(v / 1.25f);
    }

    /** Mean squared Laplacian — a sharpness score. */
    public static float sharpness(final float[] img, final int w, final int h) {
        final int step = 2;
        final int rows = (h - 2) / step;
        final double[] sums = new double[rows];
        Par.range(rows, (a, b) -> {
            for (int jj = a; jj < b; jj++) {
                int y = 1 + jj * step;
                double s = 0;
                int o = y * w;
                for (int x = 1; x < w - 1; x += step) {
                    float l = 4 * img[o + x] - img[o + x - 1] - img[o + x + 1] - img[o + x - w] - img[o + x + w];
                    s += l * l;
                }
                sums[jj] = s;
            }
        });
        double t = 0;
        for (double s : sums) t += s;
        return (float) (t / Math.max(1, rows * ((w - 2) / step)));
    }

    /**
     * Multi-scale noise removal ("Zero zgomot"). Splits the plane into 4 detail bands
     * (box pyramid at full resolution), measures the noise in each band from the image
     * itself and removes it with a non-negative garrote: edges and real texture
     * (well above the noise) survive, grain and colour blotches disappear.
     * In place. sigma = expected noise of the plane (0..255 units).
     */
    public static void multiscaleDenoise(final float[] img, final int w, final int h, float sigma, float strength) {
        if (strength <= 0) return;
        final int n = w * h;
        float[] cur = img.clone();
        final int[] radii = {1, 2, 4, 8};
        final float[] kLevel = {1.7f, 1.5f, 1.3f, 1.1f};
        float theory = Math.max(0.3f, sigma);
        for (int l = 0; l < radii.length; l++) {
            final float[] c = cur;
            final float[] b = boxBlur(c, w, h, radii[l]);
            int step = Math.max(1, (int) Math.sqrt(n / 60000.0));
            int cnt = 0;
            float[] samp = new float[(h / step + 1) * (w / step + 1)];
            for (int y = 0; y < h; y += step) for (int x = 0; x < w; x += step) {
                int i = y * w + x; samp[cnt++] = Math.abs(c[i] - b[i]);
            }
            Arrays.sort(samp, 0, cnt);
            float med = samp[cnt / 2] / 0.6745f;
            float th = theory * 0.95f;
            float sl = clampf(med, th, 3f * th);
            final float T = kLevel[l] * strength * sl;
            final float T2 = T * T;
            final boolean first = (l == 0);
            Par.range(n, (a, e) -> {
                for (int i = a; i < e; i++) {
                    float band = c[i] - b[i];
                    float sh = Math.abs(band) <= T ? 0f : band - T2 / band;
                    img[i] = first ? sh : img[i] + sh;
                }
            });
            cur = b;
            theory = sl / (2 * radii[l] + 1) * 1.6f; // coarser bands: less, but correlated, noise
        }
        final float[] fin = cur;
        Par.range(n, (a, e) -> { for (int i = a; i < e; i++) img[i] += fin[i]; });
    }

    /**
     * Removes isolated hot/dark spots up to (2r-1) px wide: a pixel is clamped to the
     * range of the ring of pixels at distance r around it (+thr). Run with r=1 then r=2.
     */
    public static void despeckle(final float[] img, final int w, final int h, final float thr, final int r) {
        final float[] src = img.clone();
        final int m = 8 * r;
        final int[] off = new int[m];
        int c = 0;
        for (int dy = -r; dy <= r; dy++) for (int dx = -r; dx <= r; dx++)
            if (Math.max(Math.abs(dx), Math.abs(dy)) == r) off[c++] = dy * w + dx;
        Par.range(h - 2 * r, (a, e) -> {
            for (int yy = a; yy < e; yy++) {
                int y = yy + r;
                for (int x = r; x < w - r; x++) {
                    int i = y * w + x;
                    float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
                    for (int k = 0; k < m; k++) {
                        float v = src[i + off[k]];
                        if (v < mn) mn = v; if (v > mx) mx = v;
                    }
                    float v = src[i];
                    if (v > mx + thr) img[i] = mx; else if (v < mn - thr) img[i] = mn;
                }
            }
        });
    }

    public static void despeckle(float[] img, int w, int h, float thr) {
        despeckle(img, w, h, thr, 1);
        despeckle(img, w, h, thr, 2);
    }

    /**
     * Detail recovery: damped Richardson-Lucy deconvolution with a box PSF of radius r.
     * Restores edges softened by the lens / upscaling instead of just adding contrast.
     * The result is blended in through mask (low-res, 0..1), so only the subject gets it.
     */
    public static void richardsonLucy(final float[] img, final int w, final int h, final int r, final int iters,
                                      final float[] mask, final int mw, final int mh, final float strength) {
        final int n = w * h;
        final float[] est = img.clone();
        final float[] t1 = new float[n], t2 = new float[n];
        for (int it = 0; it < iters; it++) {
            boxBlurInto(est, t1, w, h, r);
            Par.range(n, (a, e) -> {
                for (int i = a; i < e; i++) {
                    float q = (img[i] + 2f) / (t1[i] + 2f);
                    t1[i] = q < 0.85f ? 0.85f : (q > 1.18f ? 1.18f : q);
                }
            });
            boxBlurInto(t1, t2, w, h, r);
            Par.range(n, (a, e) -> { for (int i = a; i < e; i++) est[i] *= t2[i]; });
        }
        final float sx = mw / (float) w, sy = mh / (float) h;
        Par.range(h, (a, e) -> {
            for (int y = a; y < e; y++) {
                float my = (y + 0.5f) * sy - 0.5f;
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    float m = mask == null ? 1f : sample(mask, mw, mh, (x + 0.5f) * sx - 0.5f, my);
                    float d = est[i] - img[i];
                    if (d > 25f) d = 25f; else if (d < -25f) d = -25f;
                    img[i] += strength * m * d;
                }
            }
        });
    }
}
