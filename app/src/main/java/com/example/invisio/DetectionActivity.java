package com.example.invisio;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Pair;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class DetectionActivity extends AppCompatActivity {

    private static final String TAG = "InVisio";
    private static final String ESP32_STREAM_URL = "http://10.36.99.86:81/stream";

    private ImageView frameView;
    private OverlayView overlayView;
    private Button btnConnect, btnDetect;

    private MjpegStreamReader streamReader;
    private YoloV5TFLiteDetector detector;
    private TextToSpeech tts;

    private final AtomicReference<Bitmap> latestFrame = new AtomicReference<>(null);
    private volatile boolean detectorRunning = false;

    private Bitmap currentDisplayBitmap = null;

    private final Set<String> announced = new HashSet<>();
    private final Queue<String> speakQueue = new ArrayDeque<>();
    private volatile boolean ttsReady = false;

    private final AtomicLong lastFrameTs = new AtomicLong(0);
    private volatile float fps = 0f;

    private long lastInferMs = 0;
    private static final int INFER_INTERVAL_MS = 120; // ~8 FPS

    // Toggle states
    private volatile boolean isConnected = false;
    private volatile boolean detectionEnabled = true; // start ON

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detection);

        frameView = findViewById(R.id.frameView);
        overlayView = findViewById(R.id.overlayView);
        btnConnect = findViewById(R.id.btnConnect);
        btnDetect  = findViewById(R.id.btnDetect);

        tts = new TextToSpeech(this, status -> {
            ttsReady = (status == TextToSpeech.SUCCESS);
            if (ttsReady) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.9f);
            }
        });

        try {
            detector = new YoloV5TFLiteDetector(this, "yolov5.tflite", "yolov5_labels.txt");
            Log.i(TAG, "Model loaded. Input=" + detector.getInputSizeString()
                    + " Output=" + detector.getOutputSizeString());
        } catch (Exception e) {
            Toast.makeText(this, "Model load failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            Log.e(TAG, "Model load failed", e);
            return;
        }

        // Button listeners
        btnConnect.setOnClickListener(v -> {
            if (!isConnected) {
                connectStream();
            } else {
                disconnectStream();
            }
            updateButtons();
        });

        btnDetect.setOnClickListener(v -> {
            detectionEnabled = !detectionEnabled;
            if (!detectionEnabled) {
                overlayView.setDetections(new ArrayList<>());
                Toast.makeText(this, "Detections paused", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Detections resumed", Toast.LENGTH_SHORT).show();
            }
            updateButtons();
        });

        // Background threads
        startSpeakerThread();
        startAnnounceCooldown();
        startDetectorLoop(); // detector thread waits if not connected

        updateButtons();
    }

    private void updateButtons() {
        btnConnect.setText(isConnected ? "Disconnect" : "Connect");
        btnDetect.setText(detectionEnabled ? "Stop Detection" : "Start Detection");
        btnDetect.setEnabled(isConnected);
    }

    private void connectStream() {
        if (isConnected) return;
        startStream();
        isConnected = true;
        Toast.makeText(this, "Connecting to camera…", Toast.LENGTH_SHORT).show();
    }

    private void disconnectStream() {
        if (!isConnected) return;

        if (streamReader != null) {
            streamReader.stop();
            streamReader = null;
        }
        latestFrame.set(null);

        runOnUiThread(() -> {
            frameView.setImageDrawable(null);
            overlayView.setDetections(new ArrayList<>());
            overlayView.setFps(0f);
        });

        isConnected = false;
        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
    }

    private void startStream() {
        streamReader = new MjpegStreamReader(ESP32_STREAM_URL);
        streamReader.start(new MjpegStreamReader.FrameListener() {
            @Override
            public void onFrame(Bitmap frame) {
                long now = System.nanoTime();
                long prev = lastFrameTs.getAndSet(now);
                if (prev != 0) fps = 1_000_000_000f / (now - prev);

                latestFrame.getAndSet(frame);

                runOnUiThread(() -> {
                    if (isFinishing() || !isConnected) return;
                    try {
                        if (currentDisplayBitmap != null && !currentDisplayBitmap.isRecycled()) {
                            currentDisplayBitmap.recycle();
                        }
                        currentDisplayBitmap = frame.copy(Bitmap.Config.RGB_565, false);
                        frameView.setImageBitmap(currentDisplayBitmap);

                        Matrix imageMatrix = frameView.getImageMatrix();
                        overlayView.setTransforms(imageMatrix);

                    } catch (Throwable t) {
                        Log.e(TAG, "setImageBitmap failed", t);
                    }
                });
            }

            @Override
            public void onError(Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(DetectionActivity.this, "Stream error: " + e.getMessage(), Toast.LENGTH_LONG).show()
                );
                Log.e(TAG, "Stream error", e);
            }
        });
    }

    private void startDetectorLoop() {
        if (detectorRunning) return;
        detectorRunning = true;
        new Thread(() -> {
            Log.i(TAG, "Detector loop started");
            while (detectorRunning) {
                if (!isConnected) {
                    try { Thread.sleep(20); } catch (InterruptedException ignored) {}
                    continue;
                }

                Bitmap frame = latestFrame.getAndSet(null);
                if (frame == null) {
                    try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                    continue;
                }

                long nowMs = System.currentTimeMillis();
                if (nowMs - lastInferMs < INFER_INTERVAL_MS) {
                    continue; // throttle
                }
                lastInferMs = nowMs;

                if (detectionEnabled) {
                    runDetection(frame);
                } else {
                    runOnUiThread(() -> overlayView.setFps(fps));
                }
            }
            Log.i(TAG, "Detector loop stopped");
        }, "DetectorLoop").start();
    }

    private void runDetection(Bitmap frame) {
        if (detector == null || frame == null || frame.isRecycled()) return;

        Pair<List<YoloV5TFLiteDetector.Det>, float[]> res;
        try {
            res = detector.detect(frame);
        } catch (Throwable t) {
            Log.e(TAG, "Detection failed", t);
            return;
        }

        List<YoloV5TFLiteDetector.Det> dets = res.first;
        if (dets.isEmpty()) Log.w(TAG, "No detections on this frame");
        else Log.i(TAG, "Detections: " + dets.size());

        List<OverlayView.Detection> overlays = new ArrayList<>();
        for (YoloV5TFLiteDetector.Det d : dets) {
            overlays.add(new OverlayView.Detection(d.label, d.confidence, d.boxSrc, d.position));
        }

        for (YoloV5TFLiteDetector.Det d : dets) {
            String phrase = d.label + " " + d.position;
            if (!announced.contains(phrase)) {
                announced.add(phrase);
                enqueueSpeak(phrase);
            }
        }

        runOnUiThread(() -> {
            overlayView.setDetections(overlays);
            overlayView.setFps(fps);
        });
    }

    private void enqueueSpeak(String text) {
        synchronized (speakQueue) {
            speakQueue.add(text);
            speakQueue.notifyAll();
        }
    }

    private void startSpeakerThread() {
        new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                String msg;
                synchronized (speakQueue) {
                    while (speakQueue.isEmpty()) {
                        try { speakQueue.wait(); } catch (InterruptedException e) { return; }
                    }
                    msg = speakQueue.poll();
                }
                if (ttsReady && msg != null) {
                    tts.speak(msg, TextToSpeech.QUEUE_ADD, null, "ID_" + System.currentTimeMillis());
                }
                try { Thread.sleep(1200); } catch (InterruptedException e) { return; }
            }
        }, "TTS-Thread").start();
    }

    private void startAnnounceCooldown() {
        new Thread(() -> {
            while (detectorRunning) {
                try { Thread.sleep(8000); } catch (InterruptedException ignored) { return; }
                announced.clear();
            }
        }, "AnnounceCooldown").start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        detectorRunning = false;

        if (streamReader != null) { streamReader.stop(); streamReader = null; }
        if (detector != null) detector.close();
        if (tts != null) { tts.stop(); tts.shutdown(); }

        latestFrame.set(null);

        if (currentDisplayBitmap != null && !currentDisplayBitmap.isRecycled()) {
            currentDisplayBitmap.recycle();
            currentDisplayBitmap = null;
        }

        try {
            if (frameView.getDrawable() instanceof BitmapDrawable) {
                Bitmap b = ((BitmapDrawable) frameView.getDrawable()).getBitmap();
                if (b != null && !b.isRecycled()) b.recycle();
            }
        } catch (Throwable ignore) {}
    }
}
