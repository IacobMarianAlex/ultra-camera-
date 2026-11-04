package com.ultracam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.ultracam.engine.FrameSource;
import com.ultracam.engine.YuvFrame;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Burst frames stored in a folder (raw YUV, or JPEG on cameras that need the fallback). */
final class DiskFrames implements FrameSource {
    private final List<File> files = new ArrayList<>();
    private final boolean jpeg;

    DiskFrames(File dir, boolean jpeg) {
        this.jpeg = jpeg;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) if (f.getName().startsWith("frame_")) files.add(f);
        Collections.sort(files, (a, b) -> Integer.compare(index(a), index(b)));
    }

    private static int index(File f) {
        String n = f.getName();
        try { return Integer.parseInt(n.substring(6, n.indexOf('.'))); } catch (Exception e) { return 0; }
    }

    @Override public int count() { return files.size(); }

    @Override public YuvFrame load(int i) throws IOException {
        File f = files.get(i);
        if (!jpeg) return YuvFrame.read(f);
        Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (bmp == null) throw new IOException("JPEG invalid " + f);
        int w = bmp.getWidth() & ~1, h = bmp.getHeight() & ~1;
        int[] px = new int[bmp.getWidth() * h];
        bmp.getPixels(px, 0, bmp.getWidth(), 0, 0, bmp.getWidth(), h);
        int stride = bmp.getWidth();
        bmp.recycle();
        return YuvFrame.fromArgb(px, stride, w, h);
    }

    void deleteAll() { for (File f : files) //noinspection ResultOfMethodCallIgnored
        f.delete(); }
}
