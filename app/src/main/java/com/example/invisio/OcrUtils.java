package com.example.invisio;

import android.graphics.Bitmap;
import android.graphics.Color;

public class OcrUtils {
    public static Bitmap otsuThreshold(Bitmap src) {
        // Simple grayscale + Otsu (not super-optimized; fine for single frame)
        int w = src.getWidth(), h = src.getHeight();
        int[] pixels = new int[w * h];
        src.getPixels(pixels, 0, w, 0, 0, w, h);

        int[] gray = new int[w * h];
        int[] hist = new int[256];
        for (int i = 0; i < pixels.length; i++) {
            int p = pixels[i];
            int r = (p >> 16) & 0xFF;
            int g = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int gr = (r * 299 + g * 587 + b * 114) / 1000;
            gray[i] = gr;
            hist[gr]++;
        }

        int total = w * h;
        float sum = 0;
        for (int t = 0; t < 256; t++) sum += t * hist[t];

        float sumB = 0;
        int wB = 0;
        float varMax = 0;
        int threshold = 0;

        for (int t = 0; t < 256; t++) {
            wB += hist[t];
            if (wB == 0) continue;
            int wF = total - wB;
            if (wF == 0) break;

            sumB += t * hist[t];
            float mB = sumB / wB;
            float mF = (sum - sumB) / wF;

            float varBetween = (float) wB * wF * (mB - mF) * (mB - mF);
            if (varBetween > varMax) {
                varMax = varBetween;
                threshold = t;
            }
        }

        for (int i = 0; i < pixels.length; i++) {
            int v = gray[i] > threshold ? 255 : 0;
            pixels[i] = Color.rgb(v, v, v);
        }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, w, 0, 0, w, h);
        return out;
    }
}
