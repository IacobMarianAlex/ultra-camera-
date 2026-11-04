package com.ultracam;

import android.app.ActivityManager;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.os.Build;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;

import com.ultracam.engine.Params;

/**
 * Reads the phone (RAM, cores, sensor pixel size, OIS, Camera2 level, manual control)
 * and tunes capture + processing for it. Galaxy A05 has a hand-tuned profile.
 */
public final class DeviceProfile {
    public String name;
    public int heapMb, cores;
    public long ramMb;
    public float pixelUm;          // sensor pixel pitch in micrometres
    public boolean ois, manualSensor, yuvSafe;
    public String hwLevel;
    public int maxStillPixels;     // cap on frame resolution (memory)
    public int dayFrames, lowFrames, nightFrames, maxFrames;
    public long maxExposureNs;     // handheld limit per frame
    public float noiseBoost = 1f, sharpenBoost = 1f, detailBoost = 1f;

    public static DeviceProfile detect(Context ctx, CameraCharacteristics ch) {
        DeviceProfile d = new DeviceProfile();
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        d.heapMb = am.getLargeMemoryClass();
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        d.ramMb = mi.totalMem / (1024 * 1024);
        d.cores = Runtime.getRuntime().availableProcessors();

        SizeF phys = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        Size arr = ch.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        d.pixelUm = (phys != null && arr != null && arr.getWidth() > 0) ? phys.getWidth() * 1000f / arr.getWidth() : 0.8f;

        int[] stab = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        if (stab != null) for (int s : stab) if (s == CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) d.ois = true;
        int[] caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        if (caps != null) for (int c : caps) if (c == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) d.manualSensor = true;
        Integer lvl = ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        int level = lvl == null ? CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY : lvl;
        switch (level) {
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY: d.hwLevel = "LEGACY"; break;
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED: d.hwLevel = "LIMITED"; break;
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL: d.hwLevel = "FULL"; break;
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3: d.hwLevel = "LEVEL_3"; break;
            default: d.hwLevel = "EXTERNAL";
        }
        d.yuvSafe = level != CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;

        // memory -> frame resolution (the pipeline needs ~25 bytes per pixel at peak)
        if (d.heapMb < 300) d.maxStillPixels = 8_500_000;
        else if (d.heapMb < 450) d.maxStillPixels = 12_700_000;
        else d.maxStillPixels = 16_500_000;

        // frames: more cores -> we can afford more frames in a reasonable time
        boolean fast = d.cores >= 8 && d.ramMb > 5500;
        d.dayFrames = fast ? 10 : 8;
        d.lowFrames = fast ? 14 : 12;
        d.nightFrames = fast ? 20 : 15;
        d.maxFrames = fast ? 28 : 20;

        // handheld exposure limit: OIS allows ~2.5x longer per frame
        d.maxExposureNs = d.ois ? 250_000_000L : 100_000_000L;

        // small pixels are noisier -> stronger noise removal; large pixels keep more texture
        if (d.pixelUm < 0.75f) d.noiseBoost = 1.25f;
        else if (d.pixelUm < 1.0f) d.noiseBoost = 1.1f;
        else if (d.pixelUm > 1.4f) d.noiseBoost = 0.8f;

        String model = Build.MODEL == null ? "" : Build.MODEL;
        d.name = Build.MANUFACTURER + " " + model;
        if (model.startsWith("SM-A055")) {
            // Galaxy A05: Helio G85, 4 GB, 50 MP 0.64 um sensor, no OIS
            d.name = "Galaxy A05 (profil dedicat)";
            d.maxStillPixels = Math.min(d.maxStillPixels, 12_700_000);
            d.dayFrames = 8; d.lowFrames = 12; d.nightFrames = 15; d.maxFrames = 20;
            d.maxExposureNs = 90_000_000L;  // no OIS: keep frames sharp
            d.noiseBoost = 1.25f;
            d.sharpenBoost = 1.1f;
            d.detailBoost = 1.15f;          // soft cheap lens -> more deconvolution
        }
        return d;
    }

    /** Applies the profile to rendering parameters. */
    public void tune(Params p) {
        p.lumaDenoise *= noiseBoost;
        p.chromaDenoise *= noiseBoost;
        p.noiseFree *= Math.min(1.3f, noiseBoost);
        p.sharpen *= sharpenBoost;
        p.detailRecovery *= detailBoost;
    }

    public long clampExposure(Range<Long> r, long v) {
        long hi = Math.min(maxExposureNs, r.getUpper());
        return Math.max(r.getLower(), Math.min(hi, v));
    }

    public String describe() {
        return String.format("%s · %d nuclee · RAM %d MB · heap %d MB · pixel %.2f µm · OIS %s · Camera2 %s%s",
                name, cores, ramMb, heapMb, pixelUm, ois ? "da" : "nu", hwLevel, manualSensor ? " · manual" : "");
    }
}
