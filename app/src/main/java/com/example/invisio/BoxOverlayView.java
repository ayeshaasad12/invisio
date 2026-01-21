package com.example.invisio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Draws a single normalized [0..1] bounding box and a status label.
 * Maps from IMAGE normalized coords -> VIEW coords, accounting for fitCenter letterbox.
 */
public class BoxOverlayView extends View {

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // normalized (image-space) 0..1 coords
    private RectF normBox = null;
    private boolean intact = false;
    private String label = null;

    // The source IMAGE size (camera buffer) that normalized coords are relative to
    private int imageW = 0, imageH = 0;

    public BoxOverlayView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(6f);
        textPaint.setTextSize(40f);
        textPaint.setColor(0xFFFFFFFF);
    }

    /** Call this whenever you know the input image size (from ImageProxy). */
    public synchronized void setImageSize(int w, int h) {
        this.imageW = w;
        this.imageH = h;
        postInvalidateOnAnimation();
    }

    /** Show/update the box in IMAGE normalized coords. */
    public synchronized void showBox(RectF normalizedBox, boolean isIntact, String statusLabel) {
        this.normBox = (normalizedBox != null ? new RectF(normalizedBox) : null);
        this.intact = isIntact;
        this.label = statusLabel;
        postInvalidateOnAnimation();
    }

    public synchronized void clear() {
        this.normBox = null;
        this.label = null;
        postInvalidateOnAnimation();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Label
        if (label != null) {
            textPaint.setColor(0xFFFFFFFF);
            canvas.drawText(label, 20, 60, textPaint);
        }
        if (normBox == null || imageW <= 0 || imageH <= 0) return;

        // Map IMAGE normalized box -> VIEW coords (fitCenter letterbox math)
        float vw = getWidth();
        float vh = getHeight();
        float iw = imageW;
        float ih = imageH;

        // scale is min so entire image fits inside view
        float scale = Math.min(vw / iw, vh / ih);
        float dispW = iw * scale;
        float dispH = ih * scale;

        // letterbox offsets (bars)
        float offX = (vw - dispW) * 0.5f;
        float offY = (vh - dispH) * 0.5f;

        // Convert normalized IMAGE coords (0..1) to IMAGE pixel coords
        float imgLeft   = normBox.left   * iw;
        float imgTop    = normBox.top    * ih;
        float imgRight  = normBox.right  * iw;
        float imgBottom = normBox.bottom * ih;

        // Then to VIEW coords via scale & offsets
        RectF r = new RectF(
                offX + imgLeft * scale,
                offY + imgTop  * scale,
                offX + imgRight * scale,
                offY + imgBottom * scale
        );

        boxPaint.setColor(intact ? 0xFF00C853 : 0xFFFF1744); // green / red
        canvas.drawRect(r, boxPaint);
    }
}
