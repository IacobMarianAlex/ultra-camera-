package com.ultracam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** Round shutter button; shows a progress ring while a photo is captured / processed. */
public final class ShutterView extends View {
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            prog = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private float progress = -1f;
    private boolean pressedLook;

    public ShutterView(Context c) {
        super(c);
        ring.setStyle(Paint.Style.STROKE); ring.setColor(0xFFFFFFFF);
        fill.setColor(0xFFFFFFFF);
        prog.setStyle(Paint.Style.STROKE); prog.setColor(0xFFFFB300); prog.setStrokeCap(Paint.Cap.ROUND);
        setClickable(true);
    }

    /** progress < 0 hides the ring. */
    public void setProgress(float p) { progress = p; invalidate(); }

    @Override public void setPressed(boolean p) { super.setPressed(p); pressedLook = p; invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f;
        float sw = r * 0.09f;
        ring.setStrokeWidth(sw); prog.setStrokeWidth(sw);
        c.drawCircle(w / 2, h / 2, r - sw, ring);
        fill.setColor(progress >= 0 ? 0xFF555555 : (pressedLook ? 0xFFDDDDDD : 0xFFFFFFFF));
        c.drawCircle(w / 2, h / 2, r * (pressedLook ? 0.68f : 0.74f), fill);
        if (progress >= 0) {
            oval.set(sw, sw, w - sw, h - sw);
            c.drawArc(oval, -90, 360 * Math.min(1f, progress), false, prog);
        }
    }
}
