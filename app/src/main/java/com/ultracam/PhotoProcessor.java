package com.ultracam;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;

import com.ultracam.engine.Params;
import com.ultracam.engine.Pipeline;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Runs the heavy pipeline in the background (survives activity recreation) and saves the 4K JPEG. */
public final class PhotoProcessor {

    public interface Listener {
        void onProgress(String stage, float fraction);
        void onDone(Uri uri, Bitmap thumb, String summary, File before);
        void onError(String msg);
    }

    public static final class Settings {
        public boolean wide;        // 16:9 instead of 4:3
        public boolean zeroNoise = true;
        public boolean bokeh = true;
    }

    private static PhotoProcessor instance;
    public static synchronized PhotoProcessor get() {
        if (instance == null) instance = new PhotoProcessor();
        return instance;
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "uc-pipeline");
        t.setPriority(Thread.NORM_PRIORITY);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile Listener listener;
    private volatile boolean busy;
    private volatile String stage = "";
    private volatile float fraction;

    public void setListener(Listener l) { listener = l; }
    public boolean isBusy() { return busy; }
    public String stage() { return stage; }
    public float fraction() { return fraction; }

    public void process(Context ctx, CameraController.CaptureInfo info, DeviceProfile prof, Settings s) {
        final Context app = ctx.getApplicationContext();
        busy = true;
        exec.execute(() -> {
            long t0 = System.currentTimeMillis();
            DiskFrames frames = new DiskFrames(info.dir, info.jpeg);
            try {
                Params p = new Params();
                p.rotation = info.rotation;
                p.aspect = s.wide ? 16f / 9f : 4f / 3f;
                p.outLong = 3840;
                p.captureEv = info.ev;
                p.night = info.night;
                p.ispProcessed = info.jpeg;
                p.noiseFree = s.zeroNoise ? 1.0f : 0f;
                p.bokeh = s.bokeh ? 0.6f : 0f;
                if (info.night) { p.ghostTolerance = 1.25f; p.clarity = 0.25f; }
                if (prof != null) prof.tune(p);

                Pipeline.Result r = Pipeline.run(frames, p, (st, f) -> {
                    stage = st; fraction = f;
                    Listener l = listener;
                    if (l != null) main.post(() -> { Listener l2 = listener; if (l2 != null) l2.onProgress(st, f); });
                });

                stage = "Salvez în galerie"; fraction = 0.99f;
                Bitmap bmp = Bitmap.createBitmap(r.argb, r.width, r.height, Bitmap.Config.ARGB_8888);
                r.argb = null;
                Uri uri = save(app, bmp);
                int tw = 240, th = Math.max(1, Math.round(240f * r.height / r.width));
                Bitmap thumb = Bitmap.createScaledBitmap(bmp, tw, th, true);
                bmp.recycle();

                File before = new File(app.getCacheDir(), "before.jpg");
                Bitmap b2 = Bitmap.createBitmap(r.before, r.beforeW, r.beforeH, Bitmap.Config.ARGB_8888);
                try (FileOutputStream o = new FileOutputStream(before)) { b2.compress(Bitmap.CompressFormat.JPEG, 92, o); }
                b2.recycle();

                long secs = (System.currentTimeMillis() - t0) / 1000;
                String summary = info.plan + " · " + r.width + "×" + r.height + " · " + secs + " s";
                app.getSharedPreferences("uc", Context.MODE_PRIVATE).edit()
                        .putString("last", uri == null ? null : uri.toString())
                        .putString("lastSummary", summary).apply();
                main.post(() -> { busy = false; Listener l = listener; if (l != null) l.onDone(uri, thumb, summary, before); });
            } catch (Throwable e) {
                String msg = (e instanceof OutOfMemoryError)
                        ? "Memorie insuficientă — încearcă modul AUTO sau închide alte aplicații"
                        : "Procesare eșuată: " + e.getMessage();
                main.post(() -> { busy = false; Listener l = listener; if (l != null) l.onError(msg); });
            } finally {
                frames.deleteAll();
                System.gc();
            }
        });
    }

    private static Uri save(Context ctx, Bitmap bmp) throws Exception {
        String name = "UltraCam_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".jpg";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/UltraCam");
        cv.put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis());
        cv.put(MediaStore.Images.Media.WIDTH, bmp.getWidth());
        cv.put(MediaStore.Images.Media.HEIGHT, bmp.getHeight());
        cv.put(MediaStore.Images.Media.IS_PENDING, 1);
        ContentResolver cr = ctx.getContentResolver();
        Uri uri = cr.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), cv);
        if (uri == null) throw new IllegalStateException("nu pot crea fișierul în galerie");
        try (OutputStream os = cr.openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("nu pot scrie în galerie");
            bmp.compress(Bitmap.CompressFormat.JPEG, 96, os);
        }
        cv.clear();
        cv.put(MediaStore.Images.Media.IS_PENDING, 0);
        cr.update(uri, cv, null, null);
        return uri;
    }
}
