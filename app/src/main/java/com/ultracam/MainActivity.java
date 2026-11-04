package com.ultracam;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

public class MainActivity extends Activity implements PhotoProcessor.Listener {

    private TextureView preview;
    private ShutterView shutter;
    private TextView status, aspectBtn, noiseBtn, bokehBtn, profileTxt;
    private TextView[] modeBtns;
    private ProgressBar bar;
    private ImageView thumb;

    private CameraController cam;
    private OrientationEventListener orient;
    private volatile int deviceDeg = 0;
    private int mode = CameraController.MODE_AUTO;
    private boolean front, resumed;
    private final PhotoProcessor.Settings settings = new PhotoProcessor.Settings();
    private Uri lastUri;
    private File lastBefore;
    private String lastSummary;
    private SharedPreferences prefs;

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = getSharedPreferences("uc", MODE_PRIVATE);
        mode = prefs.getInt("mode", CameraController.MODE_AUTO);
        settings.wide = prefs.getBoolean("wide", false);
        settings.zeroNoise = prefs.getBoolean("zeroNoise", true);
        settings.bokeh = prefs.getBoolean("bokeh", true);
        cam = new CameraController(this);
        buildUi();

        orient = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                deviceDeg = ((o + 45) / 90 * 90) % 360;
            }
        };
        String last = prefs.getString("last", null);
        if (last != null) { lastUri = Uri.parse(last); lastSummary = prefs.getString("lastSummary", ""); loadThumb(lastUri); }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        }
    }

    // ------------------------------------------------------------------ UI
    private TextView chip(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(18));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x55FFFFFF);
        t.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(4), 0, dp(4), 0);
        t.setLayoutParams(lp);
        return t;
    }

    private void setChipOn(TextView t, boolean on) {
        GradientDrawable bg = (GradientDrawable) t.getBackground();
        bg.setColor(on ? 0xCCFFB300 : 0x66000000);
        t.setTextColor(on ? Color.BLACK : Color.WHITE);
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        int sw = getResources().getDisplayMetrics().widthPixels;

        preview = new TextureView(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(sw, sw * 4 / 3);
        plp.topMargin = dp(56);
        root.addView(preview, plp);
        preview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCameraIfReady(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }
        });

        // top bar
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER);
        top.setPadding(0, dp(12), 0, dp(6));
        aspectBtn = chip(settings.wide ? "4K 16:9" : "4K 4:3");
        noiseBtn = chip("ZERO ZGOMOT");
        bokehBtn = chip("BOKEH FF");
        TextView flip = chip("⟲");
        top.addView(aspectBtn); top.addView(noiseBtn); top.addView(bokehBtn); top.addView(flip);
        setChipOn(noiseBtn, settings.zeroNoise); setChipOn(bokehBtn, settings.bokeh);
        aspectBtn.setOnClickListener(v -> {
            settings.wide = !settings.wide;
            aspectBtn.setText(settings.wide ? "4K 16:9" : "4K 4:3");
            prefs.edit().putBoolean("wide", settings.wide).apply();
        });
        noiseBtn.setOnClickListener(v -> {
            settings.zeroNoise = !settings.zeroNoise; setChipOn(noiseBtn, settings.zeroNoise);
            prefs.edit().putBoolean("zeroNoise", settings.zeroNoise).apply();
            toast(settings.zeroNoise ? "Zero zgomot: PORNIT" : "Zero zgomot: oprit (textură naturală)");
        });
        bokehBtn.setOnClickListener(v -> {
            settings.bokeh = !settings.bokeh; setChipOn(bokehBtn, settings.bokeh);
            prefs.edit().putBoolean("bokeh", settings.bokeh).apply();
            toast(settings.bokeh ? "Bokeh full-frame pe fundal: PORNIT" : "Bokeh: oprit");
        });
        flip.setOnClickListener(v -> {
            if (cam.isCapturing()) return;
            front = !front; cam.close(); openCameraIfReady();
        });
        root.addView(top, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56), Gravity.TOP));

        // status over the bottom of the preview
        LinearLayout st = new LinearLayout(this);
        st.setOrientation(LinearLayout.VERTICAL);
        st.setGravity(Gravity.CENTER_HORIZONTAL);
        status = new TextView(this);
        status.setTextColor(Color.WHITE); status.setTextSize(14); status.setGravity(Gravity.CENTER);
        status.setShadowLayer(6, 0, 0, Color.BLACK);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000); bar.setVisibility(View.INVISIBLE);
        st.addView(status, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        st.addView(bar, new LinearLayout.LayoutParams(sw * 2 / 3, dp(6)));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        slp.topMargin = dp(56) + sw * 4 / 3 - dp(64);
        root.addView(st, slp);

        // bottom: modes + shutter row
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout modes = new LinearLayout(this);
        modes.setGravity(Gravity.CENTER);
        String[] names = {"AUTO", "NOAPTE", "MAX"};
        modeBtns = new TextView[3];
        for (int i = 0; i < 3; i++) {
            final int m = i;
            TextView t = new TextView(this);
            t.setText(names[i]); t.setTextSize(14); t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setPadding(dp(14), dp(6), dp(14), dp(6));
            t.setOnClickListener(v -> setMode(m));
            modes.addView(t);
            modeBtns[i] = t;
        }
        bottom.addView(modes);

        FrameLayout row = new FrameLayout(this);
        thumb = new ImageView(this);
        thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable tb = new GradientDrawable(); tb.setColor(0xFF222222); tb.setCornerRadius(dp(10)); tb.setStroke(dp(2), 0xAAFFFFFF);
        thumb.setBackground(tb); thumb.setClipToOutline(true); thumb.setPadding(dp(2), dp(2), dp(2), dp(2));
        thumb.setOnClickListener(v -> openResult());
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL | Gravity.START);
        tlp.leftMargin = dp(28);
        row.addView(thumb, tlp);
        shutter = new ShutterView(this);
        shutter.setOnClickListener(v -> takePhoto());
        row.addView(shutter, new FrameLayout.LayoutParams(dp(84), dp(84), Gravity.CENTER));
        bottom.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(100)));

        profileTxt = new TextView(this);
        profileTxt.setTextColor(0xFF9E9E9E); profileTxt.setTextSize(10); profileTxt.setGravity(Gravity.CENTER);
        profileTxt.setPadding(dp(8), 0, dp(8), dp(4));
        bottom.addView(profileTxt);
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);
        setMode(mode);
    }

    private void setMode(int m) {
        mode = m;
        for (int i = 0; i < modeBtns.length; i++) modeBtns[i].setTextColor(i == m ? 0xFFFFB300 : 0xFFBBBBBB);
        prefs.edit().putInt("mode", m).apply();
        String[] hint = {"AUTO: aleg singur câte cadre și ce expunere", "NOAPTE: 15+ cadre lungi, zero zgomot", "MAX: cele mai multe cadre, procesare lungă"};
        status.setText(hint[m]);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ lifecycle
    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        orient.enable();
        PhotoProcessor pp = PhotoProcessor.get();
        pp.setListener(this);
        if (pp.isBusy()) onProgress(pp.stage(), pp.fraction());
        openCameraIfReady();
    }

    @Override protected void onPause() {
        resumed = false;
        orient.disable();
        PhotoProcessor.get().setListener(null);
        cam.close();
        super.onPause();
    }

    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] g) {
        if (g.length > 0 && g[0] == PackageManager.PERMISSION_GRANTED) openCameraIfReady();
        else status.setText("Am nevoie de permisiunea pentru cameră");
    }

    private void openCameraIfReady() {
        if (!resumed || !preview.isAvailable()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        cam.open(preview.getSurfaceTexture(), front, new CameraController.OpenCallback() {
            @Override public void onReady(DeviceProfile prof) {
                profileTxt.setText(prof.describe());
                fitPreview();
            }
            @Override public void onError(String msg) {
                if ("RETRY_JPEG".equals(msg)) { cam.close(); openCameraIfReady(); return; }
                status.setText(msg);
            }
        });
    }

    /** Keeps the preview undistorted (sensor buffers are landscape, the activity is portrait). */
    private void fitPreview() {
        Size s = cam.previewSize();
        if (s == null) return;
        int sw = getResources().getDisplayMetrics().widthPixels;
        int h = Math.round(sw * (Math.max(s.getWidth(), s.getHeight()) / (float) Math.min(s.getWidth(), s.getHeight())));
        ViewGroup.LayoutParams lp = preview.getLayoutParams();
        if (lp.height != h) { lp.height = h; preview.setLayoutParams(lp); }
    }

    // ------------------------------------------------------------------ capture
    private void takePhoto() {
        if (PhotoProcessor.get().isBusy()) { toast("Încă procesez poza anterioară…"); return; }
        if (cam.isCapturing()) return;
        File dir = new File(getCacheDir(), "burst");
        int rotation = cam.photoRotation(deviceDeg);
        shutter.setProgress(0f);
        cam.capture(mode, rotation, dir, new CameraController.CaptureCallback() {
            @Override public void onProgress(int got, int total, String hint) {
                status.setText(hint + "  " + got + "/" + total);
                shutter.setProgress(total == 0 ? 0 : got / (float) total * 0.3f);
            }
            @Override public void onCaptured(CameraController.CaptureInfo info) {
                status.setText("Poți mișca telefonul — procesez " + info.plan);
                PhotoProcessor.get().process(MainActivity.this, info, cam.profile(), settings);
            }
            @Override public void onFailed(String msg) {
                shutter.setProgress(-1f);
                status.setText(msg);
            }
        });
    }

    @Override public void onProgress(String stage, float f) {
        bar.setVisibility(View.VISIBLE);
        bar.setProgress((int) (f * 1000));
        shutter.setProgress(0.3f + 0.7f * f);
        status.setText(stage + "  " + Math.round(f * 100) + "%");
    }

    @Override public void onDone(Uri uri, Bitmap t, String summary, File before) {
        bar.setVisibility(View.INVISIBLE);
        shutter.setProgress(-1f);
        lastUri = uri; lastBefore = before; lastSummary = summary;
        thumb.setImageBitmap(t);
        status.setText("Gata! " + summary + "\nAtinge miniatura pentru înainte / după");
    }

    @Override public void onError(String msg) {
        bar.setVisibility(View.INVISIBLE);
        shutter.setProgress(-1f);
        status.setText(msg);
    }

    private void loadThumb(Uri u) {
        new Thread(() -> {
            try {
                Bitmap b = getContentResolver().loadThumbnail(u, new Size(240, 240), null);
                runOnUiThread(() -> thumb.setImageBitmap(b));
            } catch (Exception ignored) { }
        }).start();
    }

    private void openResult() {
        if (lastUri == null) return;
        Intent i = new Intent(this, ResultActivity.class);
        i.putExtra("uri", lastUri.toString());
        if (lastBefore != null && lastBefore.exists()) i.putExtra("before", lastBefore.getAbsolutePath());
        i.putExtra("summary", lastSummary);
        startActivity(i);
    }
}
