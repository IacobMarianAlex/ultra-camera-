package com.ultracam;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import com.ultracam.engine.YuvFrame;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Camera2 preview + multi-frame burst capture into a folder of frames. */
public final class CameraController {

    public static final int MODE_AUTO = 0, MODE_NIGHT = 1, MODE_MAX = 2;

    public interface OpenCallback { void onReady(DeviceProfile profile); void onError(String msg); }

    public interface CaptureCallback {
        void onProgress(int got, int total, String hint);
        void onCaptured(CaptureInfo info);
        void onFailed(String msg);
    }

    public static final class CaptureInfo {
        public File dir; public int frames, width, height, rotation;
        public boolean jpeg, front, night;
        public float ev; public String plan;
    }

    private final Context ctx;
    private final CameraManager mgr;
    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread camThread, imgThread;
    private Handler camH, imgH;
    private final ExecutorService seq = Executors.newSingleThreadExecutor();

    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private CameraCharacteristics chars;
    private DeviceProfile profile;
    private Surface previewSurface;
    private Size previewSize, stillSize;
    private CaptureRequest.Builder previewB;
    private boolean front, jpegMode, afContinuous;
    private volatile TotalCaptureResult last;
    private volatile Burst burst;
    private volatile boolean capturing;
    private boolean forceJpeg;

    public CameraController(Context c) {
        ctx = c.getApplicationContext();
        mgr = (CameraManager) ctx.getSystemService(Context.CAMERA_SERVICE);
    }

    public Size previewSize() { return previewSize; }
    public boolean isCapturing() { return capturing; }
    public DeviceProfile profile() { return profile; }

    private final CameraCaptureSession.CaptureCallback previewCb = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) { last = res; }
    };

    // ------------------------------------------------------------------ open / close
    @SuppressLint("MissingPermission")
    public void open(SurfaceTexture st, boolean useFront, OpenCallback cb) {
        close();
        front = useFront;
        camThread = new HandlerThread("uc-cam"); camThread.start(); camH = new Handler(camThread.getLooper());
        imgThread = new HandlerThread("uc-img"); imgThread.start(); imgH = new Handler(imgThread.getLooper());
        try {
            String id = pickCamera(useFront);
            if (id == null) { cb.onError("Nu am găsit camera"); return; }
            chars = mgr.getCameraCharacteristics(id);
            profile = DeviceProfile.detect(ctx, chars);
            jpegMode = forceJpeg || !profile.yuvSafe;
            StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            int fmt = jpegMode ? ImageFormat.JPEG : ImageFormat.YUV_420_888;
            stillSize = pickStill(map.getOutputSizes(fmt), profile.maxStillPixels);
            previewSize = pickPreview(map.getOutputSizes(SurfaceTexture.class), stillSize);
            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(st);
            reader = ImageReader.newInstance(stillSize.getWidth(), stillSize.getHeight(), fmt, 4);
            reader.setOnImageAvailableListener(r -> {
                Image img = null;
                try { img = r.acquireNextImage(); if (img != null && burst != null) burst.onImage(img); }
                catch (Exception ignored) { }
                finally { if (img != null) img.close(); }
            }, imgH);
            int[] af = chars.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            afContinuous = contains(af, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            mgr.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { device = d; createSession(cb); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); device = null; }
                @Override public void onError(CameraDevice d, int e) {
                    d.close(); device = null; main.post(() -> cb.onError("Eroare cameră " + e));
                }
            }, camH);
        } catch (Exception e) {
            cb.onError("Nu pot deschide camera: " + e.getMessage());
        }
    }

    private void createSession(OpenCallback cb) {
        try {
            device.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override public void onConfigured(CameraCaptureSession s) {
                            session = s;
                            startPreview();
                            main.post(() -> cb.onReady(profile));
                        }
                        @Override public void onConfigureFailed(CameraCaptureSession s) {
                            if (!jpegMode) {           // YUV at full size not supported -> fall back to JPEG
                                forceJpeg = true;
                                main.post(() -> cb.onError("RETRY_JPEG"));
                            } else main.post(() -> cb.onError("Configurarea camerei a eșuat"));
                        }
                    }, camH);
        } catch (Exception e) { main.post(() -> cb.onError(e.getMessage())); }
    }

    private void startPreview() {
        try {
            previewB = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewB.addTarget(previewSurface);
            if (afContinuous) previewB.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewB.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            previewB.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);
            session.setRepeatingRequest(previewB.build(), previewCb, camH);
        } catch (Exception ignored) { }
    }

    public void close() {
        try { if (session != null) session.close(); } catch (Exception ignored) { }
        session = null;
        if (device != null) device.close();
        device = null;
        if (reader != null) reader.close();
        reader = null;
        if (camThread != null) camThread.quitSafely();
        if (imgThread != null) imgThread.quitSafely();
        camThread = imgThread = null;
    }

    private String pickCamera(boolean wantFront) throws CameraAccessException {
        String fallback = null;
        for (String id : mgr.getCameraIdList()) {
            Integer f = mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (f == null) continue;
            boolean isFront = f == CameraMetadata.LENS_FACING_FRONT;
            if (isFront == wantFront) return id;
            if (fallback == null) fallback = id;
        }
        return fallback;
    }

    private static Size pickStill(Size[] sizes, int maxPx) {
        Size best = null;
        for (Size s : sizes) {   // prefer the largest 4:3 that fits in memory
            long px = (long) s.getWidth() * s.getHeight();
            if (px > maxPx || (s.getWidth() & 1) != 0 || (s.getHeight() & 1) != 0) continue;
            if (Math.abs(s.getWidth() / (float) s.getHeight() - 4f / 3f) > 0.02f) continue;
            if (best == null || px > (long) best.getWidth() * best.getHeight()) best = s;
        }
        if (best != null) return best;
        for (Size s : sizes) {
            long px = (long) s.getWidth() * s.getHeight();
            if (px > maxPx) continue;
            if (best == null || px > (long) best.getWidth() * best.getHeight()) best = s;
        }
        return best != null ? best : sizes[0];
    }

    private static Size pickPreview(Size[] sizes, Size still) {
        float ar = still.getWidth() / (float) still.getHeight();
        Size best = null;
        for (Size s : sizes) {
            if (s.getWidth() > 1920 || s.getHeight() > 1440) continue;
            if (Math.abs(s.getWidth() / (float) s.getHeight() - ar) > 0.03f) continue;
            if (best == null || s.getWidth() > best.getWidth()) best = s;
        }
        return best != null ? best : sizes[0];
    }

    private static boolean contains(int[] a, int v) {
        if (a == null) return false;
        for (int x : a) if (x == v) return true;
        return false;
    }

    public int sensorOrientation() {
        Integer o = chars == null ? null : chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        return o == null ? 90 : o;
    }

    /** Rotation (clockwise) to apply to sensor pixels so the photo is upright. */
    public int photoRotation(int deviceDegrees) {
        int so = sensorOrientation();
        return front ? (so - deviceDegrees + 360) % 360 : (so + deviceDegrees) % 360;
    }

    // ------------------------------------------------------------------ burst capture
    private static final class Plan {
        int frames; float ev; boolean manual, night; long exp; int iso; String name;
    }

    private Plan plan(int mode) {
        Plan p = new Plan();
        TotalCaptureResult r = last;
        Long e = r == null ? null : r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Integer s = r == null ? null : r.get(CaptureResult.SENSOR_SENSITIVITY);
        double light = (e != null && s != null) ? (e / 1e9) * s : 0.5; // bigger = darker scene
        boolean dark = light > 6, dim = light > 0.8;
        if (mode == MODE_NIGHT || (mode == MODE_AUTO && dark)) {
            p.night = true; p.frames = profile.nightFrames; p.ev = -0.3f; p.name = "Noapte";
        } else if (dim) {
            p.frames = profile.lowFrames; p.ev = -0.7f; p.name = "Lumină slabă";
        } else {
            p.frames = profile.dayFrames; p.ev = -1.0f; p.name = "Zi HDR";
        }
        if (mode == MODE_MAX) { p.frames = profile.maxFrames; p.name = "MAX · " + p.name; }

        // manual long exposures with lower ISO for night (less noise per frame)
        if (p.night && profile.manualSensor && e != null && s != null) {
            Range<Long> er = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Range<Integer> sr = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            if (er != null && sr != null) {
                double target = e * (double) s * Math.pow(2, p.ev);
                long exp = profile.clampExposure(er, Math.max(e, e * 4));
                int iso = (int) Math.round(target / exp);
                iso = Math.max(sr.getLower(), Math.min(sr.getUpper(), iso));
                exp = profile.clampExposure(er, (long) (target / iso));
                p.manual = true; p.exp = exp; p.iso = iso;
                p.name += String.format(" · 1/%ds ISO%d", Math.max(1, Math.round(1e9 / exp)), iso);
            }
        }
        return p;
    }

    public void capture(int mode, int rotation, File dir, CaptureCallback cb) {
        if (capturing || session == null) return;
        capturing = true;
        seq.execute(() -> {
            try { doCapture(mode, rotation, dir, cb); }
            catch (Exception e) { main.post(() -> cb.onFailed("Captură eșuată: " + e.getMessage())); }
            finally { capturing = false; }
        });
    }

    private void doCapture(int mode, int rotation, File dir, CaptureCallback cb) throws Exception {
        final Plan p = plan(mode);
        main.post(() -> cb.onProgress(0, p.frames, "Focalizez…"));

        // 1) autofocus lock
        if (afContinuous) {
            previewB.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            session.capture(previewB.build(), previewCb, camH);
            previewB.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            long t0 = System.currentTimeMillis();
            while (System.currentTimeMillis() - t0 < 1500) {
                TotalCaptureResult r = last;
                Integer af = r == null ? null : r.get(CaptureResult.CONTROL_AF_STATE);
                if (af != null && (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED
                        || af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) break;
                Thread.sleep(30);
            }
        }

        // 2) exposure: underexpose for HDR, then lock AE/AWB so every frame matches
        int evSteps = 0;
        Rational step = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        Range<Integer> evr = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        if (!p.manual && step != null && evr != null && step.floatValue() > 0) {
            evSteps = Math.round(p.ev / step.floatValue());
            evSteps = Math.max(evr.getLower(), Math.min(evr.getUpper(), evSteps));
            previewB.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps);
            session.setRepeatingRequest(previewB.build(), previewCb, camH);
            Thread.sleep(450);
        }
        previewB.set(CaptureRequest.CONTROL_AE_LOCK, true);
        previewB.set(CaptureRequest.CONTROL_AWB_LOCK, true);
        session.setRepeatingRequest(previewB.build(), previewCb, camH);
        Thread.sleep(200);
        final float realEv = p.manual ? p.ev : (step != null ? evSteps * step.floatValue() : 0f);

        // 3) build the burst
        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
        b.addTarget(reader.getSurface());
        b.addTarget(previewSurface);
        if (afContinuous) b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        b.set(CaptureRequest.CONTROL_AWB_LOCK, true);
        if (p.manual) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, p.exp);
            b.set(CaptureRequest.SENSOR_SENSITIVITY, p.iso);
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, p.exp + 2_000_000L);
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.CONTROL_AE_LOCK, true);
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps);
        }
        // we do our own noise reduction and sharpening -> ask the ISP for as little as possible
        int[] nr = chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
        int[] ed = chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
        if (!jpegMode) {
            if (contains(nr, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL)) b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_MINIMAL);
            else if (contains(nr, CameraMetadata.NOISE_REDUCTION_MODE_FAST)) b.set(CaptureRequest.NOISE_REDUCTION_MODE, CameraMetadata.NOISE_REDUCTION_MODE_FAST);
            if (contains(ed, CameraMetadata.EDGE_MODE_OFF)) b.set(CaptureRequest.EDGE_MODE, CameraMetadata.EDGE_MODE_OFF);
        } else {
            b.set(CaptureRequest.JPEG_QUALITY, (byte) 100);
            b.set(CaptureRequest.JPEG_ORIENTATION, 0);
        }
        List<CaptureRequest> reqs = new ArrayList<>();
        for (int i = 0; i < p.frames; i++) reqs.add(b.build());

        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
        final Burst bu = new Burst(dir, p.frames, jpegMode, (got) ->
                main.post(() -> cb.onProgress(got, p.frames, "Ține telefonul nemișcat")));
        burst = bu;
        session.captureBurst(reqs, new CameraCaptureSession.CaptureCallback() {
            @Override public void onCaptureFailed(CameraCaptureSession s, CaptureRequest r, CaptureFailure f) { bu.failed(); }
        }, camH);
        long perFrame = (p.manual ? p.exp / 1_000_000L : 60) + 250;
        boolean complete = bu.done.await(4000 + perFrame * p.frames, TimeUnit.MILLISECONDS);
        burst = null;
        bu.writer.shutdown();
        bu.writer.awaitTermination(20, TimeUnit.SECONDS);

        // 4) back to normal preview
        previewB.set(CaptureRequest.CONTROL_AE_LOCK, false);
        previewB.set(CaptureRequest.CONTROL_AWB_LOCK, false);
        previewB.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, 0);
        if (afContinuous) {
            previewB.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            session.capture(previewB.build(), previewCb, camH);
            previewB.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
        }
        session.setRepeatingRequest(previewB.build(), previewCb, camH);

        int saved = bu.saved.get();
        if (saved == 0) {
            if (!jpegMode) forceJpeg = true; // next open uses the JPEG path
            throw new IllegalStateException(complete ? "niciun cadru" : "camera nu a livrat cadre (reîncearcă)");
        }
        CaptureInfo info = new CaptureInfo();
        info.dir = dir; info.frames = saved; info.jpeg = jpegMode; info.front = front;
        info.width = stillSize.getWidth() & ~1; info.height = stillSize.getHeight() & ~1;
        info.rotation = rotation; info.night = p.night; info.ev = realEv;
        info.plan = p.name + " · " + saved + " cadre";
        main.post(() -> cb.onCaptured(info));
    }

    /** Receives burst images, copies them out of the camera buffers and writes them to disk. */
    private static final class Burst {
        interface Tick { void got(int n); }
        final File dir; final int target; final boolean jpeg; final Tick tick;
        final AtomicInteger received = new AtomicInteger(), saved = new AtomicInteger(), failed = new AtomicInteger();
        final CountDownLatch done = new CountDownLatch(1);
        // bounded queue: if the disk is slower than the camera, the camera thread waits
        final ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2), new ThreadPoolExecutor.CallerRunsPolicy());

        Burst(File dir, int target, boolean jpeg, Tick tick) { this.dir = dir; this.target = target; this.jpeg = jpeg; this.tick = tick; }

        void failed() { failed.incrementAndGet(); check(); }

        void check() { if (saved.get() + failed.get() >= target) done.countDown(); }

        void onImage(Image img) {
            final int idx = received.getAndIncrement();
            if (idx >= target) return;
            if (jpeg) {
                ByteBuffer bb = img.getPlanes()[0].getBuffer();
                final byte[] data = new byte[bb.remaining()];
                bb.get(data);
                writer.execute(() -> {
                    try (FileOutputStream o = new FileOutputStream(new File(dir, "frame_" + idx + ".jpg"))) { o.write(data); saved.incrementAndGet(); }
                    catch (Exception e) { failed.incrementAndGet(); }
                    tick.got(saved.get()); check();
                });
            } else {
                final YuvFrame f = toYuv(img);
                writer.execute(() -> {
                    try { f.write(new File(dir, "frame_" + idx + ".yuv")); saved.incrementAndGet(); }
                    catch (Exception e) { failed.incrementAndGet(); }
                    tick.got(saved.get()); check();
                });
            }
        }
    }

    static YuvFrame toYuv(Image img) {
        int w = img.getWidth() & ~1, h = img.getHeight() & ~1;
        YuvFrame f = new YuvFrame(w, h);
        Image.Plane[] pl = img.getPlanes();
        copyPlane(pl[0], w, h, f.y);
        copyPlane(pl[1], w / 2, h / 2, f.u);
        copyPlane(pl[2], w / 2, h / 2, f.v);
        return f;
    }

    private static void copyPlane(Image.Plane p, int w, int h, byte[] dst) {
        ByteBuffer b = p.getBuffer();
        int rs = p.getRowStride(), ps = p.getPixelStride();
        int cap = b.capacity();
        if (ps == 1) {
            for (int y = 0; y < h; y++) { b.position(y * rs); b.get(dst, y * w, w); }
        } else {
            byte[] row = new byte[rs];
            for (int y = 0; y < h; y++) {
                int start = y * rs;
                int len = Math.min(rs, cap - start);
                b.position(start);
                b.get(row, 0, len);
                int o = y * w;
                for (int x = 0; x < w; x++) dst[o + x] = row[x * ps];
            }
        }
    }
}
