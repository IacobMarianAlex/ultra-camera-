package com.ultracam.engine;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Simple parallel-for over index ranges, using every CPU core. Never nest calls. */
public final class Par {
    private Par() {}

    public static final int THREADS = Math.max(1, Runtime.getRuntime().availableProcessors());

    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "uc-worker");
        t.setDaemon(true);
        return t;
    });

    public interface Body { void run(int start, int end); }

    public static void range(int n, Body body) {
        if (n <= 0) return;
        int chunks = Math.min(n, THREADS * 4);
        if (chunks <= 1 || THREADS == 1) { body.run(0, n); return; }
        Future<?>[] fs = new Future<?>[chunks];
        for (int c = 0; c < chunks; c++) {
            final int s = (int) ((long) n * c / chunks);
            final int e = (int) ((long) n * (c + 1) / chunks);
            fs[c] = POOL.submit(() -> body.run(s, e));
        }
        RuntimeException err = null;
        for (Future<?> f : fs) {
            try { f.get(); } catch (Exception ex) {
                if (err == null) err = new RuntimeException(ex.getCause() != null ? ex.getCause() : ex);
            }
        }
        if (err != null) throw err;
    }
}
