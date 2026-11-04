package com.ultracam.engine;

import java.io.IOException;

/** Full computational-photography pipeline: pick reference, merge burst, render 4K. */
public final class Pipeline {
    private Pipeline() {}

    public interface Progress { void update(String stage, float fraction); }

    public static final class Result {
        public int[] argb; public int width, height;
        public int[] before; public int beforeW, beforeH;
        public int framesUsed, referenceIndex;
        public long millis;
        public String report;
    }

    public static Result run(FrameSource src, Params p, Progress pr) throws IOException {
        long t0 = System.currentTimeMillis();
        final int n = src.count();
        if (n < 1) throw new IOException("no frames");
        Result res = new Result();

        // 1) choose the sharpest of the first frames as the reference (closest to the shutter moment)
        pr.update("Aleg cel mai clar cadru", 0f);
        int k = Math.min(n, 4), ref = 0;
        float best = -1;
        for (int i = 0; i < k; i++) {
            YuvFrame f = src.load(i);
            float s = Filters.sharpness(f.halfLuma(), f.width / 2, f.height / 2);
            if (s > best) { best = s; ref = i; }
        }
        res.referenceIndex = ref;
        YuvFrame rf = src.load(ref);
        Finisher.Output bo = Finisher.plain(rf, 1920, p.rotation);
        res.before = bo.argb; res.beforeW = bo.width; res.beforeH = bo.height;

        // 2) stream-merge the rest
        Merger mg = new Merger(rf, p.ghostTolerance);
        rf = null;
        StringBuilder rep = new StringBuilder();
        int done = 0;
        for (int i = 0; i < n; i++) {
            if (i == ref) continue;
            pr.update("Aliniez și combin cadrul " + (done + 2) + "/" + n, 0.05f + 0.6f * done / Math.max(1, n - 1));
            YuvFrame f = src.load(i);
            float used = mg.add(f);
            rep.append(String.format("cadru %d: %.0f%% folosit\n", i, used * 100));
            done++;
        }
        Merger.Merged m = mg.finish();
        mg = null;
        res.framesUsed = m.frames;

        // 3) render
        Finisher.Output o = Finisher.render(m, p, (stage, f) -> pr.update(stage, 0.65f + 0.35f * f));
        res.argb = o.argb; res.width = o.width; res.height = o.height;
        res.millis = System.currentTimeMillis() - t0;
        res.report = rep + o.report + "timp: " + res.millis + " ms";
        pr.update("Gata", 1f);
        return res;
    }
}
