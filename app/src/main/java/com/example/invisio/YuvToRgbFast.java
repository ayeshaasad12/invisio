package com.example.invisio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

import androidx.camera.core.ImageProxy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

class YuvToRgbFast {

    static Bitmap fromImageProxy(ImageProxy image) {
        try {
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            if (planes == null || planes.length < 3) return null;

            int width = image.getWidth();
            int height = image.getHeight();

            // Build NV21 buffer (Y + interleaved VU)
            byte[] nv21 = yuv420888ToNv21(planes, width, height);

            YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            yuvImage.compressToJpeg(new Rect(0, 0, width, height), 90, out);
            byte[] jpegBytes = out.toByteArray();
            return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.length);
        } catch (Throwable t) {
            return null;
        }
    }

    private static byte[] yuv420888ToNv21(ImageProxy.PlaneProxy[] planes, int width, int height) {
        byte[] y = toByteArray(planes[0].getBuffer());
        byte[] u = toByteArray(planes[1].getBuffer());
        byte[] v = toByteArray(planes[2].getBuffer());

        int ySize = width * height;
        byte[] out = new byte[ySize + 2 * (ySize / 4)];

        // Copy Y
        int yRowStride = planes[0].getRowStride();
        int pos = 0;
        for (int row = 0; row < height; row++) {
            System.arraycopy(y, row * yRowStride, out, pos, width);
            pos += width;
        }

        // Interleave VU
        int chromaHeight = height / 2;
        int chromaWidth = width / 2;
        int uRowStride = planes[1].getRowStride();
        int vRowStride = planes[2].getRowStride();
        int uPixelStride = planes[1].getPixelStride();
        int vPixelStride = planes[2].getPixelStride();

        for (int row = 0; row < chromaHeight; row++) {
            int uRow = row * uRowStride;
            int vRow = row * vRowStride;
            for (int col = 0; col < chromaWidth; col++) {
                int uIdx = uRow + col * uPixelStride;
                int vIdx = vRow + col * vPixelStride;
                out[pos++] = v[vIdx];
                out[pos++] = u[uIdx];
            }
        }
        return out;
    }

    private static byte[] toByteArray(ByteBuffer buffer) {
        buffer.rewind();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }
}
