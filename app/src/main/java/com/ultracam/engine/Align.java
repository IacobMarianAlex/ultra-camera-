package com.ultracam.engine;

import java.util.Arrays;

/**
 * Coarse-to-fine tile alignment (HDR+-style) on a Gaussian pyramid.
 * Works on half-resolution luma. Produces a smooth per-tile displacement field
 * with sub-pixel precision: alt(x + d(x)) ~ ref(x).
 */
public final class Align {
    public static final int TILE = 16; // tile size at level 0 (half-res pixels) = 32 px full-res

    public static final class Pyramid {
        public final float[][] lv; public final int[] ws, hs;
        Pyramid(int n) { lv = new float[n][]; ws = new int[n]; hs = new int[n]; }
        public int levels() { return lv.length; }
    }

    public static int levelsFor(int w, int h) {
        int n = 1;
        while (n < 5 && (Math.min(w, h) >> n) >= 48) n++;
        return n;
    }

    public static Pyramid build(float[] base, int w, int h, int levels) {
        Pyramid p = new Pyramid(levels);
        p.lv[0] = base; p.ws[0] = w; p.hs[0] = h;
        for (int l = 1; l < levels; l++) {
            p.lv[l] = Filters.down2(p.lv[l - 1], p.ws[l - 1], p.hs[l - 1]);
            p.ws[l] = p.ws[l - 1] / 2; p.hs[l] = p.hs[l - 1] / 2;
        }
        return p;
    }

    public final int w, h, tw, th;
    public final float[] dx, dy;
    public int globalX, globalY;

    private Align(int w, int h) {
        this.w = w; this.h = h;
        tw = (w + TILE - 1) / TILE; th = (h + TILE - 1) / TILE;
        dx = new float[tw * th]; dy = new float[tw * th];
    }

    /** Displacement at level-0 coordinates via bilinear interpolation of tile centres. */
    public float dispX(float x, float y) { return interp(dx, x, y); }
    public float dispY(float x, float y) { return interp(dy, x, y); }

    private float interp(float[] f, float x, float y) {
        float gx = (x - TILE * 0.5f) / TILE, gy = (y - TILE * 0.5f) / TILE;
        int i0 = (int) Math.floor(gx), j0 = (int) Math.floor(gy);
        float fx = gx - i0, fy = gy - j0;
        if (i0 < 0) { i0 = 0; fx = 0; } else if (i0 >= tw - 1) { i0 = tw - 1; fx = 0; }
        if (j0 < 0) { j0 = 0; fy = 0; } else if (j0 >= th - 1) { j0 = th - 1; fy = 0; }
        int i1 = Math.min(i0 + 1, tw - 1), j1 = Math.min(j0 + 1, th - 1);
        float a = f[j0 * tw + i0], b = f[j0 * tw + i1], c = f[j1 * tw + i0], d = f[j1 * tw + i1];
        float top = a + (b - a) * fx, bot = c + (d - c) * fx;
        return top + (bot - top) * fy;
    }

    private static float costL1(float[] R, float[] A, int w, int h, int x0, int y0, int ts, int ddx, int ddy) {
        float s = 0;
        for (int v = 0; v < ts; v++) {
            int yr = Filters.clampi(y0 + v, 0, h - 1);
            int ya = Filters.clampi(y0 + v + ddy, 0, h - 1);
            int or = yr * w, oa = ya * w;
            for (int u = 0; u < ts; u++) {
                int xr = Filters.clampi(x0 + u, 0, w - 1);
                int xa = Filters.clampi(x0 + u + ddx, 0, w - 1);
                s += Math.abs(R[or + xr] - A[oa + xa]);
            }
        }
        return s;
    }

    private static float costL2(float[] R, float[] A, int w, int h, int x0, int y0, int ts, int ddx, int ddy) {
        float s = 0;
        for (int v = 0; v < ts; v++) {
            int yr = Filters.clampi(y0 + v, 0, h - 1);
            int ya = Filters.clampi(y0 + v + ddy, 0, h - 1);
            int or = yr * w, oa = ya * w;
            for (int u = 0; u < ts; u++) {
                int xr = Filters.clampi(x0 + u, 0, w - 1);
                int xa = Filters.clampi(x0 + u + ddx, 0, w - 1);
                float d = R[or + xr] - A[oa + xa];
                s += d * d;
            }
        }
        return s;
    }

    public static Align align(Pyramid ref, Pyramid alt) {
        final int L = ref.levels();
        final Align al = new Align(ref.ws[0], ref.hs[0]);
        final int tw = al.tw, th = al.th, n = tw * th;

        // 1) global translation at the coarsest level (handles big hand shake)
        final int top = L - 1;
        final float[] Rc = ref.lv[top], Ac = alt.lv[top];
        final int wc = ref.ws[top], hc = ref.hs[top];
        final int R = Math.max(4, Math.min(20, Math.min(wc, hc) / 5));
        final int side = 2 * R + 1;
        final float[] gcost = new float[side * side];
        Par.range(side * side, (a, b) -> {
            for (int k = a; k < b; k++) {
                int sx = k % side - R, sy = k / side - R;
                double s = 0; int cnt = 0;
                for (int y = Math.max(0, -sy); y < Math.min(hc, hc - sy); y++) {
                    int or = y * wc, oa = (y + sy) * wc;
                    for (int x = Math.max(0, -sx); x < Math.min(wc, wc - sx); x++) {
                        s += Math.abs(Rc[or + x] - Ac[oa + x + sx]); cnt++;
                    }
                }
                gcost[k] = cnt > (wc * hc) / 3 ? (float) (s / cnt) : Float.MAX_VALUE;
            }
        });
        int bestK = 0;
        for (int k = 1; k < gcost.length; k++) if (gcost[k] < gcost[bestK]) bestK = k;
        int gx = bestK % side - R, gy = bestK / side - R;
        al.globalX = gx << top; al.globalY = gy << top;

        int[] ix = new int[n], iy = new int[n];
        Arrays.fill(ix, gx); Arrays.fill(iy, gy);

        // 2) coarse-to-fine tile search
        for (int l = top; l >= 0; l--) {
            if (l < top) { for (int t = 0; t < n; t++) { ix[t] *= 2; iy[t] *= 2; } }
            final float[] Rl = ref.lv[l], Al = alt.lv[l];
            final int wl = ref.ws[l], hl = ref.hs[l];
            final int ts = Math.max(8, TILE >> l);
            final int rad = (l == top) ? 4 : 2;
            final int lvl = l;
            final int[] cx = ix, cy = iy;
            final int[] nx = new int[n], ny = new int[n];
            Par.range(th, (a, b) -> {
                int[] candX = new int[5], candY = new int[5];
                for (int j = a; j < b; j++) {
                    for (int i = 0; i < tw; i++) {
                        int t = j * tw + i;
                        int ccx = ((i * TILE + TILE / 2) >> lvl), ccy = ((j * TILE + TILE / 2) >> lvl);
                        int x0 = ccx - ts / 2, y0 = ccy - ts / 2;
                        int nc = 0;
                        candX[nc] = cx[t]; candY[nc++] = cy[t];
                        if (i > 0) { candX[nc] = cx[t - 1]; candY[nc++] = cy[t - 1]; }
                        if (i < tw - 1) { candX[nc] = cx[t + 1]; candY[nc++] = cy[t + 1]; }
                        if (j > 0) { candX[nc] = cx[t - tw]; candY[nc++] = cy[t - tw]; }
                        if (j < th - 1) { candX[nc] = cx[t + tw]; candY[nc++] = cy[t + tw]; }
                        int bx = candX[0], by = candY[0];
                        float bc = costL1(Rl, Al, wl, hl, x0, y0, ts, bx, by);
                        for (int c = 1; c < nc; c++) {
                            if (candX[c] == bx && candY[c] == by) continue;
                            float cc = costL1(Rl, Al, wl, hl, x0, y0, ts, candX[c], candY[c]);
                            if (cc < bc) { bc = cc; bx = candX[c]; by = candY[c]; }
                        }
                        int sx0 = bx, sy0 = by;
                        for (int sy = -rad; sy <= rad; sy++) {
                            for (int sx = -rad; sx <= rad; sx++) {
                                if (sx == 0 && sy == 0) continue;
                                float cc = costL1(Rl, Al, wl, hl, x0, y0, ts, sx0 + sx, sy0 + sy);
                                if (cc < bc) { bc = cc; bx = sx0 + sx; by = sy0 + sy; }
                            }
                        }
                        nx[t] = bx; ny[t] = by;
                    }
                }
            });
            ix = nx; iy = ny;
        }

        // 3) sub-pixel refinement at level 0 (parabola fit on L2 cost)
        final float[] R0 = ref.lv[0], A0 = alt.lv[0];
        final int w0 = ref.ws[0], h0 = ref.hs[0];
        final int[] fx = ix, fy = iy;
        final float[] rdx = new float[n], rdy = new float[n];
        Par.range(th, (a, b) -> {
            for (int j = a; j < b; j++) {
                for (int i = 0; i < tw; i++) {
                    int t = j * tw + i;
                    int x0 = i * TILE, y0 = j * TILE;
                    int bx = fx[t], by = fy[t];
                    float c0 = costL2(R0, A0, w0, h0, x0, y0, TILE, bx, by);
                    float cxm = costL2(R0, A0, w0, h0, x0, y0, TILE, bx - 1, by);
                    float cxp = costL2(R0, A0, w0, h0, x0, y0, TILE, bx + 1, by);
                    float cym = costL2(R0, A0, w0, h0, x0, y0, TILE, bx, by - 1);
                    float cyp = costL2(R0, A0, w0, h0, x0, y0, TILE, bx, by + 1);
                    float ox = 0, oy = 0;
                    float denx = cxm - 2 * c0 + cxp, deny = cym - 2 * c0 + cyp;
                    if (denx > 1e-6f) ox = Filters.clampf((cxm - cxp) / (2 * denx), -0.5f, 0.5f);
                    if (deny > 1e-6f) oy = Filters.clampf((cym - cyp) / (2 * deny), -0.5f, 0.5f);
                    rdx[t] = bx + ox; rdy[t] = by + oy;
                }
            }
        });

        // 4) 3x3 median smoothing of the field (removes outlier tiles)
        median3(rdx, al.dx, tw, th);
        median3(rdy, al.dy, tw, th);
        return al;
    }

    private static void median3(float[] s, float[] d, int tw, int th) {
        float[] win = new float[9];
        for (int j = 0; j < th; j++) {
            for (int i = 0; i < tw; i++) {
                int c = 0;
                for (int v = -1; v <= 1; v++) for (int u = -1; u <= 1; u++) {
                    int jj = Filters.clampi(j + v, 0, th - 1), ii = Filters.clampi(i + u, 0, tw - 1);
                    win[c++] = s[jj * tw + ii];
                }
                Arrays.sort(win);
                float m = win[4];
                // keep the precise sub-pixel value unless it is an outlier
                float own = s[j * tw + i];
                d[j * tw + i] = Math.abs(own - m) > 1.5f ? m : own;
            }
        }
    }
}
