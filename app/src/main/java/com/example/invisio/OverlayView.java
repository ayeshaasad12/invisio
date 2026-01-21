package com.example.invisio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class OverlayView extends View {

    public static class Detection {
        public final String label;
        public final float confidence;
        // Box in **source bitmap space** (camera frame size)
        public final RectF boxSrc;
        public final String position;

        public Detection(String label, float confidence, RectF boxSrc, String position) {
            this.label = label;
            this.confidence = confidence;
            this.boxSrc = boxSrc;
            this.position = position;
        }
    }

    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fpsPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final List<Detection> detections = new ArrayList<>();
    private float fps = 0f;

    // bitmap->view transform (from ImageView.getImageMatrix())
    private final Matrix imageMatrix = new Matrix();

    // Density scaling so visuals look similar on all screens
    private final float dp;
    private final float sp;

    public OverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        dp = dm.density;
        sp = dm.scaledDensity;

        // Box style
        boxPaint.setStyle(Paint.Style.STROKE);
        boxPaint.setStrokeWidth(2.0f * dp);
        boxPaint.setColor(0xFF00FF00);

        // Text style
        textPaint.setTextSize(14f * sp);
        textPaint.setColor(0xFF222222);  // actual text color is drawn over a light bg
        textPaint.setShadowLayer(2f * dp, 0f, 0f, 0x66000000);

        // Text background (for readability)
        textBgPaint.setStyle(Paint.Style.FILL);
        textBgPaint.setColor(0xCCFFFF99); // semi-opaque light yellow

        // FPS style
        fpsPaint.setTextSize(16f * sp);
        fpsPaint.setColor(0xFFFF0000);
        fpsPaint.setShadowLayer(2f * dp, 0f, 0f, 0x66000000);
    }

    /** Call this whenever the ImageView updates its drawable or size. */
    public synchronized void setTransforms(Matrix imgMatrix) {
        imageMatrix.reset();
        if (imgMatrix != null) {
            imageMatrix.set(imgMatrix);
        }
        postInvalidateOnAnimation();
    }

    public synchronized void setDetections(List<Detection> list) {
        detections.clear();
        detections.addAll(list);
        postInvalidateOnAnimation();
    }

    public synchronized void setFps(float fps) {
        this.fps = fps;
        // no immediate invalidate needed; it’ll redraw on next frame or detection update
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw FPS (top-left)
        canvas.drawText(String.format("FPS: %.2f", fps), 12f * dp, 18f * dp + fpsPaint.getTextSize(), fpsPaint);

        // Draw detections
        RectF mapped = new RectF();
        for (Detection d : detections) {
            if (d.boxSrc == null) continue;

            // Map source-bitmap rect to on-screen view rect
            mapped.set(d.boxSrc);
            imageMatrix.mapRect(mapped);

            // Skip if the rect is empty (e.g., mapping failed)
            if (mapped.width() <= 1f || mapped.height() <= 1f) continue;

            // Box
            canvas.drawRect(mapped, boxPaint);

            // Label text
            String label = d.label + " " + d.position + String.format(" (%.2f)", d.confidence);

            // Measure text and draw a background rounded rect
            float padH = 4f * dp;
            float padW = 6f * dp;
            float textW = textPaint.measureText(label);
            float textH = textPaint.getFontMetrics().bottom - textPaint.getFontMetrics().top;

            float tx = mapped.left;
            float ty = mapped.bottom + textH + 6f * dp;

            // Keep the label inside the view width if needed
            if (tx + textW + 2 * padW > getWidth()) {
                tx = Math.max(6f * dp, getWidth() - textW - 2 * padW);
            }
            if (ty + padH > getHeight()) {
                // If label would go off bottom, place it above the box
                ty = Math.max(textH + 6f * dp, mapped.top - 6f * dp);
            }

            RectF bg = new RectF(
                    tx - padW,
                    ty - textH - padH,
                    tx + textW + padW,
                    ty + padH / 2f
            );
            canvas.drawRoundRect(bg, 4f * dp, 4f * dp, textBgPaint);

            // Baseline for text draw
            float textBaseline = ty - textPaint.getFontMetrics().bottom;
            canvas.drawText(label, tx, textBaseline, textPaint);
        }
    }
}
