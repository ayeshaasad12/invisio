package com.example.invisio;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.Log;
import android.util.Pair;

import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.gpu.CompatibilityList;
import org.tensorflow.lite.gpu.GpuDelegate;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class YoloV5TFLiteDetector {

    // === Public detection object ===
    public static class Det {
        public String label;
        public float confidence;

        /** Box in model/input canvas space (inW x inH), after letterbox. */
        public RectF boxModel;

        /** Box mapped back to source bitmap space (srcW x srcH). Used by OverlayView. */
        public RectF boxSrc;

        public String position;
    }

    private final Interpreter interpreter;
    private final int inW, inH;
    private final List<String> labels;

    // Buffers
    private final float[][][][] input;    // [1][inH][inW][3]
    private final int[] rgbBuffer;        // inW * inH
    private final float[][][] output;     // [1][N][C]

    // Thresholds
    private final float confThreshold = 0.25f;
    private final float iouThreshold  = 0.45f;

    // Preprocess canvas (letterbox)
    private final Bitmap modelCanvas;     // ARGB_8888 (inW x inH)
    private final Canvas drawCanvas = new Canvas();
    private final Matrix drawMatrix = new Matrix();

    // Remember last letterbox mapping (model <-> source bitmap)
    private float lastScale = 1f;
    private float lastDx = 0f, lastDy = 0f;
    private int lastSrcW = 0, lastSrcH = 0;

    private static final String TAG = "InVisio";

    public YoloV5TFLiteDetector(Context ctx, String modelAsset, String labelsAsset) throws Exception {
        Interpreter.Options opts = new Interpreter.Options();
        try {
            CompatibilityList cl = new CompatibilityList();
            if (cl.isDelegateSupportedOnThisDevice()) {
                GpuDelegate gpu = new GpuDelegate();
                opts.addDelegate(gpu);
            } else {
                opts.setNumThreads(Math.max(2, Runtime.getRuntime().availableProcessors() / 2));
            }
        } catch (Throwable ignore) {}

        interpreter = new Interpreter(loadModelFile(ctx, modelAsset), opts);

        int[] inShape = interpreter.getInputTensor(0).shape(); // [1, H, W, 3]
        inH = inShape[1];
        inW = inShape[2];

        int[] outShape = interpreter.getOutputTensor(0).shape(); // [1, N, C]
        output = new float[outShape[0]][outShape[1]][outShape[2]];

        input = new float[1][inH][inW][3];
        rgbBuffer = new int[inW * inH];

        modelCanvas = Bitmap.createBitmap(inW, inH, Bitmap.Config.ARGB_8888);
        drawCanvas.setBitmap(modelCanvas);

        labels = loadLabels(ctx, labelsAsset);
    }

    // --- Info/getters ---
    public String getInputSizeString() {
        return "[1," + inH + "," + inW + ",3]";
    }
    public String getOutputSizeString() {
        int[] s = interpreter.getOutputTensor(0).shape();
        return "[" + s[0] + "," + s[1] + "," + s[2] + "]";
    }
    public int getModelW() { return inW; }
    public int getModelH() { return inH; }

    // --- Main detect ---
    public Pair<List<Det>, float[]> detect(Bitmap src) {
        long t0 = System.nanoTime();

        // Letterbox src into modelCanvas (inW x inH), gray pad (114)
        drawCanvas.drawARGB(255, 114, 114, 114);

        lastSrcW = src.getWidth();
        lastSrcH = src.getHeight();

        float scale = Math.min(inW / (float) lastSrcW, inH / (float) lastSrcH);
        int newW = Math.round(lastSrcW * scale);
        int newH = Math.round(lastSrcH * scale);
        float dx = (inW - newW) / 2f;
        float dy = (inH - newH) / 2f;

        lastScale = scale;
        lastDx = dx;
        lastDy = dy;

        drawMatrix.reset();
        drawMatrix.postScale(scale, scale);
        drawMatrix.postTranslate(dx, dy);
        drawCanvas.drawBitmap(src, drawMatrix, null);

        // Pack into float [0..1]
        modelCanvas.getPixels(rgbBuffer, 0, inW, 0, 0, inW, inH);
        int idx = 0;
        for (int y = 0; y < inH; y++) {
            for (int x = 0; x < inW; x++) {
                int p = rgbBuffer[idx++];
                float r = ((p >> 16) & 0xFF) / 255f;
                float g = ((p >> 8)  & 0xFF) / 255f;
                float b = (p & 0xFF) / 255f;
                input[0][y][x][0] = r;
                input[0][y][x][1] = g;
                input[0][y][x][2] = b;
            }
        }

        // Inference
        interpreter.run(input, output);

        int N = output[0].length;
        int C = output[0][0].length;
        Log.i(TAG, "Infer ok. out shape N=" + N + " C=" + C);

        long t1 = System.nanoTime();

        // If your export outputs pixel coords (not normalized), set this to false
        boolean normalizedCoords = true;

        List<Det> raw = parseDetections(output[0], normalizedCoords);
        List<Det> kept = nms(raw, iouThreshold);

        long t2 = System.nanoTime();
        return new Pair<>(kept, new float[]{ (t1 - t0)/1e6f, (t2 - t1)/1e6f });
    }

    private List<Det> parseDetections(float[][] preds, boolean normalizedCoords) {
        List<Det> out = new ArrayList<>();
        for (float[] d : preds) {
            if (d.length < 6) continue;

            float x = d[0], y = d[1], w = d[2], h = d[3];
            float obj = d[4];
            if (obj <= 0f) continue;

            int best = -1; float bestScore = 0f;
            for (int i = 5; i < d.length; i++) {
                if (d[i] > bestScore) { bestScore = d[i]; best = i - 5; }
            }
            if (best < 0 || best >= labels.size()) continue;

            float conf = obj * bestScore;
            if (conf < confThreshold) continue;

            // Convert to model pixel space
            float cx, cy, bw, bh;
            if (normalizedCoords) {
                cx = x * inW; cy = y * inH;
                bw = w * inW; bh = h * inH;
            } else {
                cx = x; cy = y; bw = w; bh = h;
            }

            float x1 = clamp(cx - bw / 2f, 0, inW - 1);
            float y1 = clamp(cy - bh / 2f, 0, inH - 1);
            float x2 = clamp(cx + bw / 2f, 0, inW - 1);
            float y2 = clamp(cy + bh / 2f, 0, inH - 1);

            RectF boxModel = new RectF(x1, y1, x2, y2);

            // Map model-space box -> source bitmap space (invert letterbox)
            float inv = (lastScale == 0f) ? 1f : (1f / lastScale);
            float sx1 = (boxModel.left   - lastDx) * inv;
            float sy1 = (boxModel.top    - lastDy) * inv;
            float sx2 = (boxModel.right  - lastDx) * inv;
            float sy2 = (boxModel.bottom - lastDy) * inv;

            sx1 = clamp(sx1, 0, Math.max(0, lastSrcW - 1));
            sy1 = clamp(sy1, 0, Math.max(0, lastSrcH - 1));
            sx2 = clamp(sx2, 0, Math.max(0, lastSrcW - 1));
            sy2 = clamp(sy2, 0, Math.max(0, lastSrcH - 1));
            RectF boxSrc = new RectF(sx1, sy1, sx2, sy2);

            // Position string uses model width thirds (fine for voice cue)
            String position = positionFor(boxModel, inW);

            Det det = new Det();
            det.label = labels.get(best);
            det.confidence = conf;
            det.boxModel = boxModel;
            det.boxSrc = boxSrc;     // <-- THIS IS THE FIELD OverlayView expects
            det.position = position;

            out.add(det);
        }
        return out;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String positionFor(RectF box, int imgW) {
        float centerX = (box.left + box.right) / 2f;
        if (centerX < imgW / 3f) return "to the left of you";
        if (centerX > imgW * 2f / 3f) return "to the right of you";
        return "in front of you";
    }

    // NMS (no Java 8 lambda to be safe)
    private static List<Det> nms(List<Det> src, float iouThresh) {
        Collections.sort(src, new Comparator<Det>() {
            @Override
            public int compare(Det a, Det b) {
                return Float.compare(b.confidence, a.confidence);
            }
        });

        List<Det> keep = new ArrayList<>();
        boolean[] removed = new boolean[src.size()];
        for (int i = 0; i < src.size(); i++) {
            if (removed[i]) continue;
            keep.add(src.get(i));
            RectF a = src.get(i).boxModel;
            for (int j = i + 1; j < src.size(); j++) {
                if (removed[j]) continue;
                RectF b = src.get(j).boxModel;
                if (iou(a, b) > iouThresh) removed[j] = true;
            }
        }
        return keep;
    }

    private static float iou(RectF a, RectF b) {
        float inter = intersect(a, b);
        float union = area(a) + area(b) - inter;
        if (union <= 0f) return 0f;
        return inter / union;
    }

    private static float area(RectF r) {
        return Math.max(0, r.width()) * Math.max(0, r.height());
    }

    private static float intersect(RectF a, RectF b) {
        float x1 = Math.max(a.left, b.left);
        float y1 = Math.max(a.top, b.top);
        float x2 = Math.min(a.right, b.right);
        float y2 = Math.min(a.bottom, b.bottom);
        return Math.max(0, x2 - x1) * Math.max(0, y2 - y1);
    }

    private static MappedByteBuffer loadModelFile(Context context, String asset) throws Exception {
        AssetFileDescriptor fd = context.getAssets().openFd(asset);
        FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel = fis.getChannel();
        long startOffset = fd.getStartOffset();
        long declaredLength = fd.getDeclaredLength();
        return channel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    private static List<String> loadLabels(Context ctx, String asset) throws Exception {
        List<String> list = new ArrayList<>();
        BufferedReader br = new BufferedReader(new InputStreamReader(ctx.getAssets().open(asset)));
        String line;
        while ((line = br.readLine()) != null) list.add(line.trim());
        br.close();
        return list;
    }

    public void close() { interpreter.close(); }
}
