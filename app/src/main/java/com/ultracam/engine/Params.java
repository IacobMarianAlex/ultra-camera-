package com.ultracam.engine;

/** Rendering parameters. Defaults are tuned for small phone sensors. */
public final class Params {
    /** Long edge of the output in pixels (3840 = 4K). */
    public int outLong = 3840;
    /** Output aspect (long/short), e.g. 4/3 or 16/9. */
    public float aspect = 4f / 3f;
    /** Clockwise rotation applied to the sensor image to make it upright: 0/90/180/270. */
    public int rotation = 0;

    public float lumaDenoise = 0.75f;   // 0..1.5
    public float chromaDenoise = 1.0f;  // 0..2
    /** "Zero zgomot": multi-scale noise removal after the merge. 0 = off, 1 = strong, 1.5 = maximum. */
    public float noiseFree = 1.0f;
    /** Richardson-Lucy detail recovery on the subject (0 = off). */
    public float detailRecovery = 0.8f;
    /** Full-frame style background defocus, only where the background is already out of focus (0 = off). */
    public float bokeh = 0.6f;
    public float sharpen = 0.9f;        // 0..2
    public float clarity = 0.35f;       // local contrast boost
    public float vibrance = 0.28f;
    public float hdrStrength = 1.0f;    // 0 = no local tone compression
    public float ghostTolerance = 1.0f; // >1 merges more aggressively
    /** Exposure the burst was shot at relative to normal (e.g. -1 for HDR underexposure). */
    public float captureEv = 0f;
    /** Hint from the capture planner: true for night scenes (keeps the mood dark). */
    public boolean night = false;
    /** JPEG fallback frames are already sharpened/denoised by the ISP. */
    public boolean ispProcessed = false;
}
