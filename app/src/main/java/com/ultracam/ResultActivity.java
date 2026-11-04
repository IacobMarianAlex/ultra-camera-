package com.ultracam;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.InputStream;

/** Shows the processed 4K photo; hold the picture to see the original single frame. */
public class ResultActivity extends Activity {
    private Bitmap after, before;
    private ImageView img;
    private TextView label;

    @SuppressLint("ClickableViewAccessibility")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Uri uri = Uri.parse(getIntent().getStringExtra("uri"));
        String beforePath = getIntent().getStringExtra("before");
        String summary = getIntent().getStringExtra("summary");

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        img = new ImageView(this);
        img.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(img, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        label = new TextView(this);
        label.setTextColor(Color.WHITE); label.setTextSize(15); label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setGravity(Gravity.CENTER); label.setShadowLayer(8, 0, 0, Color.BLACK);
        label.setPadding(0, 90, 0, 0);
        root.addView(label, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(24, 24, 24, 48);
        bottom.setBackgroundColor(0x88000000);
        TextView sum = new TextView(this);
        sum.setText(summary == null ? "" : summary);
        sum.setTextColor(0xFFDDDDDD); sum.setGravity(Gravity.CENTER);
        bottom.addView(sum);
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER);
        Button gal = new Button(this); gal.setText("Deschide în Galerie");
        gal.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "image/jpeg");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(i); } catch (Exception ignored) { }
        });
        Button share = new Button(this); share.setText("Trimite");
        share.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("image/jpeg");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Trimite poza"));
        });
        btns.addView(gal); btns.addView(share);
        bottom.addView(btns);
        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);

        after = decode(uri, 2400);
        if (beforePath != null) before = BitmapFactory.decodeFile(beforePath);
        showAfter();
        img.setOnTouchListener((v, e) -> {
            if (before == null) return false;
            if (e.getAction() == MotionEvent.ACTION_DOWN) { img.setImageBitmap(before); label.setText("ORIGINAL (un singur cadru)"); }
            else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) showAfter();
            return true;
        });
    }

    private void showAfter() {
        img.setImageBitmap(after);
        label.setText(before != null ? "ULTRACAM 4K · ține apăsat pentru original" : "ULTRACAM 4K");
    }

    private Bitmap decode(Uri u, int maxSide) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(u)) { BitmapFactory.decodeStream(in, null, o); }
            int s = 1;
            while (Math.max(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = s;
            try (InputStream in = getContentResolver().openInputStream(u)) { return BitmapFactory.decodeStream(in, null, o2); }
        } catch (Exception e) { return null; }
    }
}
