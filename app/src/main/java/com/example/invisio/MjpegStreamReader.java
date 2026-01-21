package com.example.invisio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Minimal MJPEG reader that yields decoded Bitmap frames.
 * Caller decides bitmap lifecycle; no recycle here to avoid ownership issues.
 */
public class MjpegStreamReader {

    private final String streamUrl;
    private volatile boolean running = false;
    private HttpURLConnection connection;
    private InputStream input;

    public interface FrameListener {
        void onFrame(Bitmap frame);
        void onError(Exception e);
    }

    public MjpegStreamReader(String url) {
        this.streamUrl = url;
    }

    public void start(FrameListener listener) {
        running = true;
        new Thread(() -> {
            try {
                URL url = new URL(streamUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setReadTimeout(0);
                connection.setConnectTimeout(8000);
                connection.setRequestProperty("User-Agent", "Android");
                connection.connect();
                input = new BufferedInputStream(connection.getInputStream());

                while (running) {
                    Bitmap bmp = readJpegFrame(input);
                    if (bmp != null && running) {
                        // Keep original config; detector converts in its own buffer
                        listener.onFrame(bmp);
                    }
                }

            } catch (Exception e) {
                if (listener != null) listener.onError(e);
            } finally {
                stop();
            }
        }, "MJPEG-Reader").start();
    }

    public void stop() {
        running = false;
        try { if (input != null) input.close(); } catch (Exception ignore) {}
        if (connection != null) connection.disconnect();
    }

    private Bitmap readJpegFrame(InputStream in) throws Exception {
        // Scan for SOI 0xFFD8
        int b;
        while (true) {
            b = in.read();
            if (b == -1) return null;
            if (b == 0xFF && in.read() == 0xD8) break;
        }
        // Collect until EOI 0xFFD9
        FastByteArrayOutputStream baos = new FastByteArrayOutputStream(64 * 1024);
        baos.write(0xFF); baos.write(0xD8);
        int prev = 0;
        while (true) {
            int cur = in.read();
            if (cur == -1) return null;
            baos.write(cur);
            if (prev == 0xFF && cur == 0xD9) break;
            prev = cur;
        }
        byte[] jpeg = baos.toByteArray();
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
    }

    // Lightweight expandable byte buffer
    static class FastByteArrayOutputStream {
        private byte[] buf;
        private int count;
        FastByteArrayOutputStream(int size) { buf = new byte[size]; }
        void write(int b) {
            if (count >= buf.length) {
                byte[] n = new byte[buf.length * 2];
                System.arraycopy(buf, 0, n, 0, count);
                buf = n;
            }
            buf[count++] = (byte) b;
        }
        byte[] toByteArray() {
            byte[] out = new byte[count];
            System.arraycopy(buf, 0, out, 0, count);
            return out;
        }
    }
}
